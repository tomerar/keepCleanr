// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class TrashManagerTest {
    private val root = "/storage/emulated/0"
    private val sd = "/storage/1234-ABCD"
    private val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(root, sd))
    private val index = InMemoryTrashIndex()
    private val clock = FakeClock()
    private val signer = FakeSigner()
    private val protectedPaths = ProtectedPaths()
    private var ids = 0

    private fun manager(signer: SidecarSigner = this.signer) = TrashManager(
        fake,
        index,
        signer,
        clock,
        protectedPaths,
        listOf(root, sd),
        newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
    )

    private val trash = manager()
    private val photo = "$root/DCIM/Camera/IMG_1.jpg"
    private val bytes = ByteArray(5_000) { (it % 97).toByte() }

    init {
        fake.addFile(photo, bytes, mtime = 10)
    }

    private suspend fun op(path: String, kind: OpKind = OpKind.TRASH): PlannedOp {
        val s = fake.stat(KPath.Local(path))!!
        return PlannedOp(s.path, path, kind, s.isDirectory, s.size, s.mtimeMillis, s.identity)
    }

    private suspend fun run(vararg ops: PlannedOp): ExecutionReport.Ran {
        val executor = ActionExecutor(fake, SafetyGuard(protectedPaths), protectedPaths, trash, "s")
        return executor.run(ActionPlan("p", ops.toList(), "test").confirm(1, "s")) as ExecutionReport.Ran
    }

    private suspend fun trashOne(path: String = photo): TrashEntry {
        run(op(path)).done shouldHaveSize 1
        return index.all().single { it.originalPath == path }
    }

    @Test
    fun `trash moves the file into a hidden, nomedia trash and restore puts it back byte-exact`(): Unit = runBlocking {
        val entry = trashOne()
        fake.exists(photo) shouldBe false
        entry.trashedPath shouldContain "/.KeepClearTrash/"
        fake.exists("$root/.KeepClearTrash/.nomedia") shouldBe true
        fake.exists(entry.trashedPath) shouldBe true
        entry.state shouldBe TrashState.ACTIVE
        entry.expiresAt - entry.deletedAt shouldBe TrashPolicy.RETENTION_MILLIS

        run(op(entry.trashedPath, OpKind.RESTORE)).done shouldHaveSize 1
        fake.readAllBytes(photo) shouldBe bytes
        index.all().shouldBeEmpty()
        fake.files("$root/.KeepClearTrash").filterNot { it.endsWith(".nomedia") }.shouldBeEmpty()
    }

    @Test
    fun `restore never overwrites (E-DEL-6)`(): Unit = runBlocking {
        val entry = trashOne()
        fake.addFile(photo, "new photo".toByteArray())
        run(op(entry.trashedPath, OpKind.RESTORE))
        fake.readAllBytes(photo) shouldBe "new photo".toByteArray()
        fake.readAllBytes("$root/DCIM/Camera/IMG_1 (restored).jpg") shouldBe bytes
    }

    @Test
    fun `restore recreates a deleted folder (E-DEL-7)`(): Unit = runBlocking {
        val entry = trashOne()
        fake.delete(KPath.Local("$root/DCIM/Camera"))
        run(op(entry.trashedPath, OpKind.RESTORE))
        fake.readAllBytes(photo) shouldBe bytes
    }

    @Test
    fun `purge deletes only inside the trash`(): Unit = runBlocking {
        val entry = trashOne()
        run(op(entry.trashedPath, OpKind.PURGE)).done shouldHaveSize 1
        fake.exists(entry.trashedPath) shouldBe false
        index.all().shouldBeEmpty()
    }

    @Test
    fun `purge waits 30 days of real time (TRASH-2)`(): Unit = runBlocking {
        trashOne()
        clock.advance(TrashPolicy.RETENTION_MILLIS - 1)
        trash.purgeExpired() shouldBe 0
        clock.advance(2)
        trash.purgeExpired() shouldBe bytes.size.toLong()
        index.all().shouldBeEmpty()
    }

    @Test
    fun `a same-boot clock jump is refused and logged (E-DEL-10)`(): Unit = runBlocking {
        val jumps = mutableListOf<ClockJump>()
        val m = TrashManager(fake, index, signer, clock, protectedPaths, listOf(root, sd), onClockJump = { jumps += it })
        trashOne()
        m.purgeExpired() shouldBe 0
        clock.wall += 365L * 24 * 3_600_000 // clock jumps a year, uptime barely moves
        clock.uptime += 25L * 3_600_000
        clock.elapsed += 25L * 3_600_000
        m.purgeExpired() shouldBe 0
        jumps.single().sameBoot shouldBe true
        // Next run: no new jump, but 30 days of uptime still haven't passed.
        m.purgeExpired() shouldBe 0
        fake.exists(index.all().single().trashedPath) shouldBe true
    }

    @Test
    fun `reboot plus a clock set forward can't empty the trash early (M2, E-DEL-10)`(): Unit = runBlocking {
        trashOne()
        trash.purgeExpired() shouldBe 0
        clock.reboot()
        clock.wall += TrashPolicy.RETENTION_MILLIS + 1 // set forward after the reboot; not detectable as a jump
        clock.advance(25L * 3_600_000)
        trash.purgeExpired() shouldBe 0
        index.all() shouldHaveSize 1
        // Real time catches up: purged.
        clock.advance(TrashPolicy.RETENTION_MILLIS)
        trash.purgeExpired() shouldBe bytes.size.toLong()
    }

    @Test
    fun `a clock set backwards is refused and logged (E-DEL-10)`(): Unit = runBlocking {
        val jumps = mutableListOf<ClockJump>()
        val m = TrashManager(fake, index, signer, clock, protectedPaths, listOf(root, sd), onClockJump = { jumps += it })
        trashOne()
        m.purgeExpired()
        clock.wall -= 2 * TrashPolicy.CLOCK_JUMP_TOLERANCE
        m.purgeExpired() shouldBe 0
        jumps.single().wallDeltaMillis shouldBe -2 * TrashPolicy.CLOCK_JUMP_TOLERANCE
    }

    @Test
    fun `power-off time only delays a purge`(): Unit = runBlocking {
        trashOne()
        clock.advance(10L * 86_400_000)
        clock.reboot(offMillis = 20L * 86_400_000) // wall says 30 days, phone was on for 10
        trash.purgeExpired() shouldBe 0
        clock.advance(20L * 86_400_000)
        trash.purgeExpired() shouldBe bytes.size.toLong()
    }

    @Test
    fun `a recovered entry with no uptime stamp still waits at least 24 h of real time (M2)`(): Unit = runBlocking {
        val entry = trashOne()
        index.put(entry.copy(deletedAtUptime = null, bootId = "recovered"))
        clock.reboot()
        clock.wall += TrashPolicy.RETENTION_MILLIS * 2
        trash.purgeExpired() shouldBe 0
        index.all().single().deletedAtUptime shouldNotBe null
        clock.advance(TrashPolicy.MIN_ELAPSED_BEFORE_PURGE + 1)
        trash.purgeExpired() shouldBe bytes.size.toLong()
    }

    @Test
    fun `missing SD card is skipped (E-STO-2), its trash stays on the card (E-DEL-14)`(): Unit = runBlocking {
        fake.addFile("$sd/DCIM/a.jpg", size = 10)
        val entry = trashOne("$sd/DCIM/a.jpg")
        entry.trashedPath.startsWith("$sd/.KeepClearTrash/") shouldBe true
        fake.disconnect(sd)
        clock.advance(TrashPolicy.RETENTION_MILLIS + 1)
        trash.purgeExpired() shouldBe 0
        fake.reconnect(sd)
        trash.purgeExpired() shouldBe 10
    }

    @Test
    fun `lost index is rebuilt from signed sidecars (E-DEL-8)`(): Unit = runBlocking {
        val entry = trashOne()
        index.entries.clear()
        val report = manager().recover()
        report.rebuiltFromSidecars shouldBe 1
        val rebuilt = index.all().single()
        rebuilt.trusted shouldBe true
        rebuilt.originalPath shouldBe photo
        manager().restoreDestination(rebuilt) shouldBe photo
        rebuilt.trashedPath shouldBe entry.trashedPath
    }

    @Test
    fun `sidecar signed with another key is untrusted and restores to the safe folder (E-SEC-2)`(): Unit = runBlocking {
        trashOne()
        index.entries.clear()
        val other = manager(FakeSigner("reinstalled-app-key"))
        other.recover().untrusted shouldBe 1
        val entry = index.all().single()
        entry.trusted shouldBe false
        other.restoreDestination(entry) shouldBe "$root/Download/KeepClear Restored/IMG_1.jpg"
    }

    @Test
    fun `tampered sidecar pointing into WhatsApp Databases never restores there (E-SEC-1)`(): Unit = runBlocking {
        val entry = trashOne()
        index.entries.clear()
        val sidecarPath = KPath.Local(entry.trashedPath).parent!!.parent!!.child("${entry.id}.json")
        val evil = Sidecar(
            id = entry.id,
            originalPath = "$root/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14",
            trashedName = "IMG_1.jpg",
            size = 5000,
            mtimeMillis = 10,
            reason = "TRASH",
            deletedAt = 0,
            expiresAt = 0,
        )
        fake.delete(sidecarPath)
        // Even correctly signed (attacker knows nothing, but assume the worst), the destination is refused.
        fake.addFile(sidecarPath.absolute, Sidecar.encode(evil, signer))
        manager().recover()
        val rebuilt = index.all().single()
        // A PROTECTED name is refused even in the fallback folder; the file in our trash gives the name instead.
        manager().restoreDestination(rebuilt) shouldBe "$root/Download/KeepClear Restored/IMG_1.jpg"
        run(op(rebuilt.trashedPath, OpKind.RESTORE))
        fake.exists("$root/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14") shouldBe false
        fake.exists("$root/Download/KeepClear Restored/msgstore.db.crypt14") shouldBe false
        fake.readAllBytes("$root/Download/KeepClear Restored/IMG_1.jpg") shouldBe bytes
    }

    @Test
    fun `path traversal and outside-volume destinations are refused (E-SEC-1)`(): Unit = runBlocking {
        val v = RestorePathValidator(protectedPaths)
        v.check("$root/DCIM/../Android/media/com.whatsapp/WhatsApp/Databases/x", root, fake) shouldNotBe null
        v.check("/data/data/other.app/x", root, fake) shouldNotBe null
        v.check("$sd/DCIM/x.jpg", root, fake) shouldNotBe null
        v.check("$root/.KeepClearTrash/x", root, fake) shouldNotBe null
        v.check("relative/x", root, fake) shouldNotBe null
        v.check("$root/DCIM/Camera/x.jpg", root, fake) shouldBe null
    }

    @Test
    fun `restore refuses a symlinked ancestor`(): Unit = runBlocking {
        fake.addSymlink("$root/Pictures/link", "$root/Android/media/com.whatsapp/WhatsApp/Databases")
        RestorePathValidator(protectedPaths).check("$root/Pictures/link/x.jpg", root, fake) shouldNotBe null
    }

    @Test
    fun `hostile sidecars never crash recovery (E-SEC-3)`(): Unit = runBlocking {
        trashOne()
        val day = fake.files("$root/.KeepClearTrash").first { it.endsWith(".json") }.substringBeforeLast('/')
        fake.addFile("$day/00000000-0000-4000-8000-000000000999.json", "{not json".toByteArray())
        fake.addFile("$day/00000000-0000-4000-8000-000000000998.json", ByteArray(Sidecar.MAX_BYTES + 10))
        fake.addFile("$day/not-a-uuid.json", "{}".toByteArray())
        index.entries.clear()
        manager().recover().rebuiltFromSidecars shouldBe 1
    }

    @Test
    fun `entries whose files vanished are dropped (E-DEL-9, TRASH-6)`(): Unit = runBlocking {
        val entry = trashOne()
        fake.delete(KPath.Local(entry.trashedPath))
        manager().recover().missingDropped shouldBe 1
        index.all().shouldBeEmpty()
    }

    @Test
    fun `ENOSPC while writing the sidecar leaves the original untouched (LOW-3, E-LOW-3)`(): Unit = runBlocking {
        fake.fail(FakeStorageGateway.Op.CREATE, matches = { it.toString().contains(".KeepClearTrash/2") }) {
            StorageException.NoSpace(it)
        }
        val report = run(op(photo))
        report.failed.single().error.shouldBeInstanceOf<StorageException.NoSpace>()
        fake.readAllBytes(photo) shouldBe bytes
        index.all().shouldBeEmpty()
    }

    @Test
    fun `rename failure rolls back the journal`(): Unit = runBlocking {
        fake.fail(FakeStorageGateway.Op.RENAME)
        run(op(photo)).failed shouldHaveSize 1
        fake.readAllBytes(photo) shouldBe bytes
        index.all().shouldBeEmpty()
        fake.files("$root/.KeepClearTrash").filter { it.endsWith(".json") }.shouldBeEmpty()
    }

    @Test
    fun `restoring a missing trashed file reports it`(): Unit = runBlocking {
        val entry = trashOne()
        val restoreOp = op(entry.trashedPath, OpKind.RESTORE)
        fake.delete(KPath.Local(entry.trashedPath))
        shouldThrow<StorageException.NotFound> { trash.perform(restoreOp, fake) }
        index.all().shouldBeEmpty()
    }

    @Test
    fun `an untrusted recovered entry with a forged expiry is never purged automatically (SEC-1)`(): Unit =
        runBlocking {
            val entry = trashOne()
            index.entries.clear()
            val sidecarPath = KPath.Local(entry.trashedPath).parent!!.parent!!.child("${entry.id}.json")
            val forged = Sidecar(
                id = entry.id,
                originalPath = photo,
                trashedName = "IMG_1.jpg",
                size = 5000,
                mtimeMillis = 10,
                reason = "TRASH",
                deletedAt = 0,
                expiresAt = 0,
            )
            fake.delete(sidecarPath)
            fake.addFile(sidecarPath.absolute, Sidecar.encode(forged, FakeSigner("attacker")))
            val m = manager()
            m.recover().untrusted shouldBe 1
            clock.advance(400L * 86_400_000L)
            m.purgeExpired() shouldBe 0
            fake.exists(entry.trashedPath) shouldBe true
            index.all().single().trusted shouldBe false
        }

    @Test
    fun `an unsafe fallback destination stops the restore (SEC-1)`(): Unit = runBlocking {
        val entry = trashOne()
        // Download/ is a symlink elsewhere; the original location is protected, so the fallback is used.
        fake.addSymlink("$root/Download", "$root/Android/media/com.whatsapp/WhatsApp/Databases")
        val tampered = entry.copy(originalPath = "$root/Android/media/com.whatsapp/WhatsApp/Databases/x.crypt14")
        index.put(tampered)
        val report = run(op(entry.trashedPath, OpKind.RESTORE))
        report.done.shouldBeEmpty()
        fake.exists(entry.trashedPath) shouldBe true
        index.all().single().id shouldBe entry.id
    }

    @Test
    fun `forgetMissing drops entries whose file vanished after recovery (TRASH-6)`(): Unit = runBlocking {
        val entry = trashOne()
        fake.delete(KPath.Local(entry.trashedPath))
        trash.forgetMissing(index.all()) shouldBe 1
        index.all().shouldBeEmpty()
    }

    @Test
    fun `restore refuses a trashed file whose size or content no longer matches the record (T1-6)`(): Unit =
        runBlocking {
            val entry = trashOne()
            fake.modify(entry.trashedPath, FakeStorageGateway.Content.Bytes(ByteArray(7)), mtime = 99)
            run(op(entry.trashedPath, OpKind.RESTORE)).done.shouldBeEmpty()
            fake.exists(photo) shouldBe false
            index.all() shouldHaveSize 1

            val other = "$root/DCIM/Camera/IMG_2.jpg"
            fake.addFile(other, ByteArray(100) { 1 }, mtime = 10)
            val e2 = trashOne(other).let { index.put(it.copy(sha256 = "0".repeat(64))); index.get(it.id)!! }
            run(op(e2.trashedPath, OpKind.RESTORE)).done.shouldBeEmpty()
            fake.exists(other) shouldBe false
        }

    @Test
    fun `a sidecar from a newer version is surfaced as untrusted, not left as an orphan (E-MIG-4)`(): Unit =
        runBlocking {
            val entry = trashOne()
            index.entries.clear()
            val sidecarPath = KPath.Local(entry.trashedPath).parent!!.parent!!.child("${entry.id}.json")
            val newer = """{"body":{"v":2,"id":"${entry.id}","originalPath":"$photo","trashedName":"IMG_1.jpg",""" +
                """"size":5000,"mtimeMillis":10,"sha256":null,"ruleId":null,"reason":"TRASH","deletedAt":1,""" +
                """"expiresAt":2,"verifiedCopy":null,"futureField":{"x":1}},"mac":"m","futureTop":true}"""
            fake.delete(sidecarPath)
            fake.addFile(sidecarPath.absolute, newer.toByteArray())
            manager().recover().untrusted shouldBe 1
            index.all().single().trusted shouldBe false
        }

    @Test
    fun `an old-version sidecar with unknown keys is still rejected (SEC-1)`() {
        val bytes = """{"body":{"v":1,"id":"x","originalPath":"/a","trashedName":"a","size":1,"mtimeMillis":1,""" +
            """"reason":"r","deletedAt":1,"expiresAt":2,"evil":1},"mac":"m"}"""
        Sidecar.decode(bytes.toByteArray(), signer).shouldBeInstanceOf<Sidecar.Companion.Decoded.Rejected>()
    }
}
