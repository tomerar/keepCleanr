// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.restore

import io.github.tomerar.keepclear.backup.GatewayBackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.move.InMemoryMoveJobStore
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.Manifest
import io.github.tomerar.keepclear.engine.move.MoveEngine
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** MOVE-17: restore from a backup, round trip and hostile manifests (E-SEC-5). */
class RestoreEngineTest {
    private val phone = "/storage/emulated/0"
    private val usb = "/storage/USB-1"
    private val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(phone, usb)).apply {
        mkdirs(usb)
    }
    private val target = GatewayBackupTarget(fake, KPath.Local(usb), "usb-1", "SanDisk", TargetCaps(canReadBack = true))
    private val store = InMemoryMoveJobStore()
    private val items = (0 until 4).map { i ->
        val path = "$phone/DCIM/Camera/IMG_2021010${i}_120000.jpg"
        fake.addFile(path, ByteArray(10_000 + i) { (it * (i + 3)).toByte() }, mtime = 1_610_000_000_000L + i)
        ScanItem(KPath.Local(path), path, (10_000 + i).toLong(), 1_610_000_000_000L + i)
    }
    private val originals = items.associate { it.path to fake.readAllBytes(it.path).toList() }
    private var ids = 0

    private fun engine() = RestoreEngine(target, fake, ProtectedPaths(), phone, newId = { "t${++ids}" })

    /** A real backup made by the MOVE engine, then the originals gone (as after "Free up space" + trash purge). */
    private suspend fun backUpAndLoseOriginals(): String {
        val rootRel = MovePlanner.rootRel("OnePlus 10T")
        store.putJob(MoveJob("j", target.id, target.label, rootRel, 1))
        MovePlanner.plan("j", items, "OnePlus 10T", "Camera", target.caps).forEach { store.putFile(it) }
        MoveEngine(fake, target, store, clock = { 5L }).run("j").state shouldBe JobState.DONE
        items.forEach { fake.delete(it.ref) }
        return rootRel
    }

    @Test
    fun `MOVE then delete then restore gives back identical bytes at the original paths`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        engine().backups() shouldBe listOf(rootRel)
        val catalog = engine().catalog(rootRel)!!
        catalog shouldHaveSize 4
        val report = engine().restore(catalog)
        report.restored shouldHaveSize 4
        report.redirected shouldBe 0
        originals.forEach { (path, bytes) -> fake.readAllBytes(path).toList() shouldBe bytes }
        // No temporary file is left behind.
        fake.files(phone).filter { ".kc-restore-" in it }.shouldBeEmpty()
    }

    @Test
    fun `a file already on the phone is left alone, a different one is never overwritten`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val catalog = engine().catalog(rootRel)!!
        // Same content back in place: nothing to do.
        fake.addFile(items[0].path, originals.getValue(items[0].path).toByteArray())
        // Something else now has the name: the restored copy goes to the restore folder.
        fake.addFile(items[1].path, "someone else's file".toByteArray())
        val report = engine().restore(catalog)
        report.alreadyThere shouldBe 1
        report.redirected shouldBe 1
        String(fake.readAllBytes(items[1].path)) shouldBe "someone else's file"
        val moved = report.restored.single { "KeepClear Restored" in it }
        moved shouldStartWith "$phone/Pictures/KeepClear Restored/Camera/"
        fake.readAllBytes(moved).toList() shouldBe originals.getValue(items[1].path)
    }

    @Test
    fun `a copy that changed on the drive is never restored`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val catalog = engine().catalog(rootRel)!!
        val victim = catalog.first()
        val onDrive = "$usb/${victim.targetRel}"
        val bytes = fake.readAllBytes(onDrive).also { it[0] = (it[0] + 1).toByte() }
        fake.delete(KPath.Local(onDrive))
        fake.addFile(onDrive, bytes)
        val report = engine().restore(listOf(victim))
        report.damaged shouldBe 1
        report.restored.shouldBeEmpty()
        fake.exists(victim.line.src) shouldBe false
        fake.files(phone).filter { ".kc-restore-" in it }.shouldBeEmpty()
    }

    @Test
    fun `a hostile manifest can't send files into protected places or out of the backup (E-SEC-5)`(): Unit =
        runBlocking {
            val rootRel = backUpAndLoseOriginals()
            val real = engine().catalog(rootRel)!!.first()
            val jsonl = "$usb/$rootRel/${Manifest.JSONL}"
            val header = String(fake.readAllBytes(jsonl)).lineSequence().first()

            fun line(rel: String, src: String) =
                """{"rel":"$rel","src":"$src","size":${real.line.size},"mtime":1,"sha256":"${real.line.sha256}",""" +
                    """"verifiedAt":1,"nameChanged":false}"""
            val hostile = listOf(
                // Escapes the backup folder: dropped by the strict parse.
                line("../../etc/passwd", "$phone/x"),
                // Points into WhatsApp's database folder: restored only into the restore folder.
                line(real.line.rel, "$phone/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14"),
                // Points into KeepClear's trash or off the phone.
                line(real.line.rel.replace(".jpg", "-b.jpg"), "$phone/.KeepClearTrash/x.jpg"),
                line(real.line.rel.replace(".jpg", "-c.jpg"), "/data/data/other.app/x.jpg"),
            )
            fake.delete(KPath.Local(jsonl))
            fake.addFile(jsonl, (listOf(header) + hostile).joinToString("\n").toByteArray())
            // Give the two extra names the same bytes on the drive so only the destination is under test.
            val bytes = fake.readAllBytes("$usb/${real.targetRel}")
            fake.addFile("$usb/${real.targetRel.replace(".jpg", "-b.jpg")}", bytes)
            fake.addFile("$usb/${real.targetRel.replace(".jpg", "-c.jpg")}", bytes)

            val catalog = engine().catalog(rootRel)!!
            catalog shouldHaveSize 3
            val report = engine().restore(catalog)
            report.restored shouldHaveSize 3
            report.restored.forEach { it shouldStartWith "$phone/Pictures/KeepClear Restored/" }
            fake.exists("$phone/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14") shouldBe false
            fake.files("$phone/.KeepClearTrash").shouldBeEmpty()
        }

    @Test
    fun `restoring the same selection again copies nothing twice (PR 26 review)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val catalog = engine().catalog(rootRel)!!
        fake.addFile(items[1].path, "someone else's file".toByteArray())
        engine().restore(catalog).redirected shouldBe 1
        val again = engine().restore(catalog)
        again.restored.shouldBeEmpty()
        again.alreadyThere shouldBe 4
        fake.files("$phone/Pictures/KeepClear Restored").filter { "(2)" in it }.shouldBeEmpty()
    }

    @Test
    fun `a full phone fails that file cleanly, leaves no partial file, and goes on (PR 26 review)`(): Unit =
        runBlocking {
            val rootRel = backUpAndLoseOriginals()
            val catalog = engine().catalog(rootRel)!!
            var writes = 0
            val full = object : io.github.tomerar.keepclear.core.storage.StorageGateway by fake {
                override suspend fun create(dir: KPath, name: String): java.io.OutputStream {
                    val real = fake.create(dir, name)
                    // The first restored file hits "no space left" halfway (a raw IOException from the stream).
                    if (++writes > 1) return real
                    return object : java.io.OutputStream() {
                        override fun write(b: Int) = throw java.io.IOException("ENOSPC")

                        override fun write(b: ByteArray, off: Int, len: Int) = throw java.io.IOException("ENOSPC")

                        override fun close() = real.close()
                    }
                }
            }
            val report = RestoreEngine(target, full, ProtectedPaths(), phone, newId = { "t${++ids}" }).restore(catalog)
            report.failed shouldBe 1
            report.restored shouldHaveSize 3
            fake.files(phone).filter { ".kc-restore-" in it }.shouldBeEmpty()
        }
}
