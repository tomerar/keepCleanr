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
import io.github.tomerar.keepclear.engine.move.MoveFile
import io.github.tomerar.keepclear.engine.move.MoveFileState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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

    /** Plenty of room unless a test says otherwise (D1). */
    private var space: Space? = Space(allocatable = 50_000_000_000, total = 128_000_000_000)

    /** D2: the MOVE records of "this phone" (the in-memory job store the backup was made with). */
    private val records = MoveRecords { src, sha ->
        store.files.values.any { it.sourcePath == src && it.sourceSha256 == sha }
    }

    private fun engine(
        gateway: io.github.tomerar.keepclear.core.storage.StorageGateway = fake,
        records: MoveRecords = this.records,
        backup: io.github.tomerar.keepclear.backup.BackupTarget = target,
        probe: SpaceProbe = SpaceProbe { space },
        dryRun: Boolean = false,
    ) = RestoreEngine(backup, gateway, ProtectedPaths(), phone, probe, records, { dryRun }, newId = { "t${++ids}" })

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
        // Owner brief H3 item 4: the activity history shows the restored size.
        report.restoredBytes shouldBe originals.values.sumOf { it.size.toLong() }
        report.redirected shouldBe 0
        originals.forEach { (path, bytes) -> fake.readAllBytes(path).toList() shouldBe bytes }
        // No temporary file is left behind.
        fake.files(phone).filter { ".kc-restore-" in it }.shouldBeEmpty()
    }

    @Test
    fun `README html next to the manifest is never restored or parsed as a backup file (H1 item 10)`(): Unit =
        runBlocking {
            val rootRel = MovePlanner.rootRel("OnePlus 10T")
            store.putJob(MoveJob("j", target.id, target.label, rootRel, 1))
            MovePlanner.plan("j", items, "OnePlus 10T", "Camera", target.caps).forEach { store.putFile(it) }
            val texts = io.github.tomerar.keepclear.engine.move.BackupReadme.Texts("en", "ltr", "T", "W", "O", "R")
            MoveEngine(fake, target, store, clock = { 5L }, readme = listOf(texts)).run("j").state shouldBe
                JobState.DONE
            fake.exists("$usb/$rootRel/README.html") shouldBe true
            // A hostile manifest line naming it is dropped too.
            val jsonl = "$usb/$rootRel/${Manifest.JSONL}"
            val sha = "b".repeat(64)
            val evil = "{\"rel\":\"README.html\",\"src\":\"$phone/README.html\",\"size\":1,\"mtime\":1," +
                "\"sha256\":\"$sha\",\"verifiedAt\":1,\"nameChanged\":false}\n"
            val text = fake.readAllBytes(jsonl).toString(Charsets.UTF_8) + evil
            fake.delete(KPath.Local(jsonl))
            fake.addFile(jsonl, text.toByteArray())
            val catalog = engine().catalog(rootRel)!!
            catalog shouldHaveSize 4
            catalog.none { it.line.rel.contains("README") } shouldBe true
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
        // Only what was written counts, not the file that was already there.
        report.restoredBytes shouldBe items.drop(1).sumOf { originals.getValue(it.path).size.toLong() }
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
            val report = engine(gateway = full).restore(catalog)
            report.failed shouldBe 1
            report.restored shouldHaveSize 3
            fake.files(phone).filter { ".kc-restore-" in it }.shouldBeEmpty()
        }

    // ------------------------------------------------------------------ D1: never fill the phone

    @Test
    fun `the reserve is 5 percent of the storage, at least 1 GB (D1)`() {
        Space(allocatable = 0, total = 128_000_000_000).reserve shouldBe 6_400_000_000
        Space(allocatable = 0, total = 16_000_000_000).reserve shouldBe 1_000_000_000
        Space(allocatable = 500, total = 16_000_000_000).room shouldBe 0
    }

    @Test
    fun `a selection that doesn't fit with the reserve left free writes nothing (D1)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val catalog = engine().catalog(rootRel)!!
        // Room for 30,000 bytes above the 1 GB reserve; the four files need 40,006.
        space = Space(allocatable = 1_000_030_000, total = 16_000_000_000)
        val report = engine().restore(catalog)
        report.notEnoughRoom!!.fits shouldBe false
        report.notEnoughRoom.needed shouldBe 40_006
        report.restored.shouldBeEmpty()
        items.forEach { fake.exists(it.path) shouldBe false }
        fake.files(phone).filter { "KeepClear Restored" in it || ".kc-restore-" in it }.shouldBeEmpty()
    }

    @Test
    fun `free space that can't be measured restores nothing (D1)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        space = null
        val report = engine().restore(engine().catalog(rootRel)!!)
        report.notEnoughRoom!!.fits shouldBe false
        report.restored.shouldBeEmpty()
        items.forEach { fake.exists(it.path) shouldBe false }
    }

    @Test
    fun `free space falling to the reserve stops cleanly before the next file (D1)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val catalog = engine().catalog(rootRel)!!
        var calls = 0
        // Plenty for the check at the start and the first file; then something else filled the phone.
        val shrinking = SpaceProbe {
            if (++calls <= 2) space else Space(allocatable = 1_000_005_000, total = 16_000_000_000)
        }
        val report = engine(probe = shrinking).restore(catalog)
        report.stoppedLowSpace shouldBe true
        report.restored shouldHaveSize 1
        fake.files(phone).filter { ".kc-restore-" in it }.shouldBeEmpty()
        items.drop(1).forEach { fake.exists(it.path) shouldBe false }
    }

    @Test
    fun `a drive that sends more than the manifest's size is cut off (D1)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val victim = engine().catalog(rootRel)!!.first()
        var sent = 0L
        val endless = object : io.github.tomerar.keepclear.backup.BackupTarget by target {
            override suspend fun openRead(rel: String): java.io.InputStream {
                if (rel != victim.targetRel) return target.openRead(rel)
                // A hostile server: the right size in the listing, then far more bytes than that.
                return object : java.io.InputStream() {
                    override fun read(): Int = if (sent++ < LIMIT) 0 else -1

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (sent >= LIMIT) return -1
                        java.util.Arrays.fill(b, off, off + len, 0)
                        sent += len
                        return len
                    }
                }
            }
        }
        val report = engine(backup = endless).restore(listOf(victim))
        report.damaged shouldBe 1
        report.restored.shouldBeEmpty()
        (sent <= victim.line.size + 256 * 1024) shouldBe true
        fake.exists(victim.line.src) shouldBe false
        fake.files(phone).filter { ".kc-restore-" in it }.shouldBeEmpty()
    }

    @Test
    fun `in test mode restore writes nothing and says what it would do (D1, SAFETY-9)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val catalog = engine().catalog(rootRel)!!
        val report = engine(dryRun = true).restore(catalog)
        report.simulated shouldBe RestorePreview(toOriginal = 4, toRestoreFolder = 0, skipped = 0, bytes = 40_006)
        report.restored.shouldBeEmpty()
        items.forEach { fake.exists(it.path) shouldBe false }
        fake.exists("$phone/Pictures") shouldBe false
    }

    // ------------------------------------------------------------------ PR #42 review: what is on the phone now

    @Test
    fun `an occupied original place is shown as the restore folder in the plan and in test mode`(): Unit =
        runBlocking {
            val rootRel = backUpAndLoseOriginals()
            val catalog = engine().catalog(rootRel)!!
            fake.addFile(items[1].path, "someone else's file".toByteArray())
            val plan = engine().plan(catalog).associateBy { it.entry.line.src }
            plan.getValue(items[1].path).where shouldBe Where.RESTORE_FOLDER
            plan.getValue(items[1].path).path shouldStartWith "$phone/Pictures/KeepClear Restored/Camera/"
            plan.getValue(items[0].path).where shouldBe Where.ORIGINAL
            val simulated = engine(dryRun = true).restore(catalog).simulated!!
            simulated.toOriginal shouldBe 3
            simulated.toRestoreFolder shouldBe 1
            // And that is what restoring then does.
            engine().restore(catalog).redirected shouldBe 1
        }

    @Test
    fun `files already on the phone are not counted as written in the plan, test mode or the room check`(): Unit =
        runBlocking {
            val rootRel = backUpAndLoseOriginals()
            val catalog = engine().catalog(rootRel)!!
            fake.addFile(items[0].path, originals.getValue(items[0].path).toByteArray())
            engine().plan(catalog).single { it.entry.line.src == items[0].path }.where shouldBe Where.ALREADY_THERE
            val simulated = engine(dryRun = true).restore(catalog).simulated!!
            simulated shouldBe RestorePreview(
                toOriginal = 3,
                toRestoreFolder = 0,
                skipped = 0,
                bytes = 40_006 - 10_000,
                alreadyThere = 1,
            )
            // Room for exactly the three files still missing: the one already there needs none.
            space = Space(allocatable = 1_000_030_006, total = 16_000_000_000)
            val report = engine().restore(catalog)
            report.notEnoughRoom shouldBe null
            report.restored shouldHaveSize 3
            report.alreadyThere shouldBe 1
        }

    @Test
    fun `a same-size file with other content still needs room (only a matching SHA-256 counts)`(): Unit =
        runBlocking {
            val rootRel = backUpAndLoseOriginals()
            val catalog = engine().catalog(rootRel)!!
            fake.addFile(items[0].path, ByteArray(10_000) { 7 })
            space = Space(allocatable = 1_000_030_006, total = 16_000_000_000)
            engine().restore(catalog).notEnoughRoom!!.needed shouldBe 40_006
        }

    @Test
    fun `restoring the same selection again isn't refused for room when everything is back`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val catalog = engine().catalog(rootRel)!!
        fake.addFile(items[1].path, "someone else's file".toByteArray())
        engine().restore(catalog).restored shouldHaveSize 4
        // The phone is now nearly full, but nothing needs writing (one copy sits in the restore folder).
        space = Space(allocatable = 1_000_000_000, total = 16_000_000_000)
        val again = engine().restore(catalog)
        again.notEnoughRoom shouldBe null
        again.alreadyThere shouldBe 4
        again.restored.shouldBeEmpty()
    }

    // ------------------------------------------------------------------ D2: the manifest's src is only a hint

    /** Puts a file on the drive and its line in the manifest; [recorded] adds a MOVE record for [src] too. */
    private suspend fun putOnDrive(
        rootRel: String,
        rel: String,
        src: String,
        bytes: ByteArray,
        recorded: Boolean = true,
    ) {
        val sha = io.github.tomerar.keepclear.core.hash.Sha256.of(bytes)
        fake.addFile("$usb/$rootRel/$rel", bytes)
        val jsonl = "$usb/$rootRel/${Manifest.JSONL}"
        val text = String(fake.readAllBytes(jsonl)).trimEnd('\n') + "\n" +
            """{"rel":"$rel","src":"$src","size":${bytes.size},"mtime":1,"sha256":"$sha",""" +
            """"verifiedAt":1,"nameChanged":false}""" + "\n"
        fake.delete(KPath.Local(jsonl))
        fake.addFile(jsonl, text.toByteArray())
        if (recorded) {
            val index = 100 + store.files.size
            store.files["j" to index] = MoveFile(
                "j",
                index,
                KPath.Local(src),
                src,
                bytes.size.toLong(),
                1,
                null,
                "$rootRel/$rel",
                MoveFileState.VERIFIED,
                sha,
            )
        }
    }

    @Test
    fun `the original place is used only when this phone moved that file from there (D2)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val catalog = engine().catalog(rootRel)!!
        // Another phone's backup, or a reinstalled app: no MOVE records here.
        val report = engine(records = MoveRecords.NONE).restore(catalog)
        report.restored shouldHaveSize 4
        report.redirected shouldBe 4
        report.restored.forEach { it shouldStartWith "$phone/Pictures/KeepClear Restored/Camera/" }
        items.forEach { fake.exists(it.path) shouldBe false }
    }

    @Test
    fun `a record for the path with other content doesn't count (D2)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val catalog = engine().catalog(rootRel)!!
        store.files.replaceAll { _, f -> f.copy(sourceSha256 = "0".repeat(64)) }
        val report = engine().restore(catalog)
        report.redirected shouldBe 4
        items.forEach { fake.exists(it.path) shouldBe false }
    }

    @Test
    fun `the name in src must be the backed-up name (D2)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        // Even with a matching record, "photo.jpg" is never written under another name.
        putOnDrive(rootRel, "Camera/photo.jpg", "$phone/DCIM/Camera/other.jpg", byteArrayOf(1, 2, 3))
        val entry = engine().catalog(rootRel)!!.single { it.name == "photo.jpg" }
        engine().placement(entry).where shouldBe Where.RESTORE_FOLDER
        val report = engine().restore(listOf(entry))
        report.restored shouldContainExactly listOf("$phone/Pictures/KeepClear Restored/Camera/photo.jpg")
        fake.exists("$phone/DCIM/Camera/other.jpg") shouldBe false
    }

    @Test
    fun `hidden files and programs are never written anywhere (D2)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        putOnDrive(rootRel, "Camera/.nomedia", "$phone/DCIM/Camera/.nomedia", ByteArray(0))
        putOnDrive(rootRel, "Camera/update.apk", "$phone/DCIM/Camera/update.apk", byteArrayOf(80, 75))
        putOnDrive(rootRel, "Camera/run.sh", "$phone/DCIM/Camera/run.sh", "#!/bin/sh".toByteArray())
        val hostile = engine().catalog(rootRel)!!.filter { it.name in setOf(".nomedia", "update.apk", "run.sh") }
        hostile shouldHaveSize 3
        engine().plan(hostile).map { it.skip }.toSet() shouldBe setOf(RestoreSkip.HIDDEN_FILE, RestoreSkip.PROGRAM)
        val report = engine().restore(hostile)
        report.skipped shouldBe 3
        report.restored.shouldBeEmpty()
        fake.files(phone).filter { it.endsWith(".nomedia") || it.endsWith(".apk") || it.endsWith(".sh") }
            .shouldBeEmpty()
    }

    @Test
    fun `a file that isn't a photo, video, audio or document goes only to the restore folder (D2)`(): Unit =
        runBlocking {
            val rootRel = backUpAndLoseOriginals()
            putOnDrive(rootRel, "Camera/page.html", "$phone/DCIM/Camera/page.html", "<script>".toByteArray())
            val entry = engine().catalog(rootRel)!!.single { it.name == "page.html" }
            val report = engine().restore(listOf(entry))
            report.restored shouldContainExactly listOf("$phone/Pictures/KeepClear Restored/Camera/page.html")
            fake.exists("$phone/DCIM/Camera/page.html") shouldBe false
        }

    @Test
    fun `a hidden group folder can't hide restored files (D2)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        putOnDrive(rootRel, ".hidden/a.jpg", "$phone/x/a.jpg", byteArrayOf(9), recorded = false)
        val entry = engine().catalog(rootRel)!!.single { it.name == "a.jpg" }
        engine().placement(entry).path shouldBe "$phone/Pictures/KeepClear Restored/hidden/a.jpg"
    }

    @Test
    fun `which names may be restored (D2)`() {
        val table = mapOf(
            "IMG_1.JPG" to (null to true),
            "clip.mp4" to (null to true),
            "PTT-2021.opus" to (null to true),
            "letter.pdf" to (null to true),
            "archive.zip" to (null to false),
            "noext" to (null to false),
            ".nomedia" to (RestoreSkip.HIDDEN_FILE to false),
            ".hidden.jpg" to (RestoreSkip.HIDDEN_FILE to false),
            "app.APK" to (RestoreSkip.PROGRAM to false),
            "x.apk." to (RestoreSkip.PROGRAM to false),
            "photo.jpg.exe" to (RestoreSkip.PROGRAM to false),
            "tool.sh" to (RestoreSkip.PROGRAM to false),
        )
        table.forEach { (name, expected) ->
            (RestoreNames.skipReason(name) to RestoreNames.isKnownMediaType(name)) shouldBe expected
        }
    }

    private companion object {
        const val LIMIT = 16L * 1024 * 1024
    }

    // ------------------------------------------------------------ H1c: one file back from the restore grid

    @Test
    fun `one file of a backup is restored alone and the others are not written (H1c)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val catalog = engine().catalog(rootRel)!!
        val one = catalog.single { it.line.src == items[1].path }
        val report = engine().restore(listOf(one))
        report.restored shouldContainExactly listOf(items[1].path)
        fake.readAllBytes(items[1].path).toList() shouldBe originals.getValue(items[1].path)
        items.filter { it != items[1] }.forEach { fake.exists(it.path) shouldBe false }
    }

    @Test
    fun `in test mode one file is only simulated and nothing is written (H1c, SAFETY-9)`(): Unit = runBlocking {
        val rootRel = backUpAndLoseOriginals()
        val one = engine().catalog(rootRel)!!.first()
        val before = fake.files(phone).toSet()
        val report = engine(dryRun = true).restore(listOf(one))
        report.simulated!!.files shouldBe 1
        report.restored.shouldBeEmpty()
        fake.files(phone).toSet() shouldBe before
    }

    @Test
    fun `a single file whose place changed since the catalog is never overwritten (H1c, SAFETY-6)`(): Unit =
        runBlocking {
            val rootRel = backUpAndLoseOriginals()
            val catalog = engine().catalog(rootRel)!!
            val one = catalog.single { it.line.src == items[2].path }
            engine().plan(listOf(one)).single().where shouldBe Where.ORIGINAL
            // After the catalog was shown, another file took the original place.
            val other = ByteArray(77) { 7 }
            fake.addFile(items[2].path, other)
            val report = engine().restore(listOf(one))
            report.redirected shouldBe 1
            fake.readAllBytes(items[2].path).toList() shouldBe other.toList()
            report.restored.single() shouldStartWith "$phone/${RestoreEngine.RESTORE_DIR}/"
            // And restoring it again finds it already there: nothing written twice.
            engine().restore(listOf(one)).alreadyThere shouldBe 1
        }
}
