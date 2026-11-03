// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * H1: the first read-back can come from the phone's page cache, not the drive. Originals may be freed only after
 * a second pass that can't be served from RAM (the drive was unplugged and plugged back in, or the phone rebooted),
 * and the copy is re-hashed again at free time.
 */
class SecondPassTest {
    private val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(PHONE))
    private val store = InMemoryMoveJobStore()
    private var boot = "boot-1"
    private val items = (0 until 6).map { i ->
        val path = "$PHONE/DCIM/Camera/IMG_2021010${i}_120000.jpg"
        fake.addFile(path, ByteArray(10_000) { (it * (i + 3) + i).toByte() }, mtime = 1_610_000_000_000L + i)
        ScanItem(KPath.Local(path), path, 10_000, 1_610_000_000_000L + i)
    }

    private suspend fun job(drive: FakeCapacityDrive): String {
        store.putJob(MoveJob("j", drive.id, drive.label, MovePlanner.rootRel("Phone"), 1))
        MovePlanner.plan("j", items, "Phone", "Camera", drive.caps).forEach { store.putFile(it) }
        return "j"
    }

    private var attach = AttachTracker("process-1")

    private fun engine(drive: FakeCapacityDrive) = MoveEngine(
        fake,
        drive,
        store,
        clock = { 5L },
        bootId = { boot },
        attachGeneration = { attach.generation("USB-1") },
    )

    /** D4: unplugged and plugged back in, with both volume events seen by the app. */
    private fun reattach(drive: FakeCapacityDrive) {
        drive.eject()
        attach.onState("USB-1", mounted = false)
        attach.onState("USB-1", mounted = true)
    }

    @Test
    fun `a fake-capacity stick passes the first check from cache but never frees originals`(): Unit = runBlocking {
        val drive = FakeCapacityDrive(realBytes = 25_000)
        val id = job(drive)
        engine(drive).run(id).state shouldBe JobState.DONE
        // Every file "verified" from the page cache...
        store.files(id).all { it.state == MoveFileState.VERIFIED } shouldBe true
        // ...but nothing may be freed before a second, uncached pass.
        engine(drive).originalsPlan(id).shouldBeEmpty()

        reattach(drive)
        val result = engine(drive).confirm(id, FreshRead.REATTACHED)
        val intact = store.files(id).filter { f ->
            Sha256.of(drive.openRead(f.relTarget)) == f.sourceSha256
        }.map { it.index }.toSet()
        result.ok shouldBe intact.size
        result.damaged shouldBe 6 - intact.size
        (result.damaged > 0) shouldBe true
        val planned = engine(drive).originalsPlan(id).map { op ->
            store.files(id).single {
                it.sourcePath ==
                    op.displayPath
            }.index
        }
        planned.toSet() shouldBe intact
        // Damaged copies go back to "copy again"; they are never offered.
        store.files(id).filter {
            it.index !in intact
        }.all { it.state == MoveFileState.PENDING && it.confirmedAt == null } shouldBe
            true
    }

    @Test
    fun `a second pass in the same boot without unplugging proves nothing`(): Unit = runBlocking {
        val drive = FakeCapacityDrive(realBytes = 1_000_000)
        val id = job(drive)
        engine(drive).run(id)
        val same = engine(drive).confirm(id, FreshRead.REBOOTED)
        same.ok shouldBe 0
        same.unproven shouldBe 6
        engine(drive).originalsPlan(id).shouldBeEmpty()
        boot = "boot-2"
        engine(drive).confirm(id, FreshRead.REBOOTED).ok shouldBe 6
        engine(drive).originalsPlan(id).size shouldBe 6
    }

    @Test
    fun `a new connection counts as a fresh read only for a network drive (M15)`(): Unit = runBlocking {
        val usb = FakeCapacityDrive(realBytes = 1_000_000)
        val id = job(usb)
        engine(usb).run(id)
        // A drive on the phone may answer from the phone's cache: "new connection" proves nothing there.
        shouldThrow<IllegalStateException> { engine(usb).confirm(id, FreshRead.NEW_SESSION) }
        engine(usb).originalsPlan(id).shouldBeEmpty()

        val nas = FakeCapacityDrive(realBytes = 1_000_000, id = "nas", network = true)
        store.putJob(MoveJob("n", nas.id, nas.label, MovePlanner.rootRel("Phone"), 1))
        MovePlanner.plan("n", items, "Phone", "Camera", nas.caps).forEach { store.putFile(it) }
        engine(nas).run("n")
        engine(nas).confirm("n", FreshRead.NEW_SESSION).ok shouldBe 6
        engine(nas).originalsPlan("n").size shouldBe 6
    }

    @Test
    fun `the copy is re-hashed at free time, not only its size`(): Unit = runBlocking {
        val drive = FakeCapacityDrive(realBytes = 1_000_000)
        val id = job(drive)
        engine(drive).run(id)
        reattach(drive)
        engine(drive).confirm(id, FreshRead.REATTACHED).ok shouldBe 6
        // Same size, different bytes (the drive went bad after the check).
        val victim = store.files(id).first()
        drive.delete(victim.relTarget)
        drive.create(victim.relTarget).use { it.write(ByteArray(10_000) { 9 }) }
        drive.eject()
        val planned = engine(drive).originalsPlan(id).map { it.displayPath }
        (victim.sourcePath in planned) shouldBe false
        planned.size shouldBe 5
    }

    @Test
    fun `the drive merely failing to open once proves nothing, only real unmount and mount events do (D4)`(): Unit =
        runBlocking {
            val drive = FakeCapacityDrive(realBytes = 25_000)
            val id = job(drive)
            engine(drive).run(id)
            // The app once couldn't open the drive (a hiccup), but Android never unmounted it: the cache may still
            // hold every copy, so nothing may be read as "fresh".
            engine(drive).unprovenNow(id) shouldBe 6
            val hiccup = engine(drive).confirm(id, FreshRead.REATTACHED)
            hiccup.ok shouldBe 0
            hiccup.unproven shouldBe 6
            store.files(id).none { it.confirmedAt != null } shouldBe true
            // Another drive coming back proves nothing for this one.
            attach.onState("OTHER-9", mounted = false)
            attach.onState("OTHER-9", mounted = true)
            engine(drive).confirm(id, FreshRead.REATTACHED).unproven shouldBe 6
            engine(drive).originalsPlan(id).shouldBeEmpty()
        }

    @Test
    fun `a re-run paused mid-way can't be confirmed from the page cache (D4, per-file boot id)`(): Unit =
        runBlocking {
            val drive = FakeCapacityDrive(realBytes = 1_000_000)
            val id = job(drive)
            // First run in boot-1 stops after three files (the job never finishes, so the job has no boot id).
            var verified = 0
            val partial = MoveEngine(
                fake,
                drive,
                store,
                clock = { 5L },
                bootId = { boot },
                onProgress = { if (it.state == MoveFileState.VERIFIED && ++verified == 3) throw StopHere() },
            )
            runCatching { partial.run(id) }
            store.files(id).count { it.state == MoveFileState.VERIFIED } shouldBe 3
            store.job(id)!!.verifyBootId shouldBe null
            // Same boot: those three were checked in this boot and may still be in the phone's memory.
            val same = engine(drive).confirm(id, FreshRead.REBOOTED)
            same.ok shouldBe 0
            same.unproven shouldBe 3
            // The rest is copied in boot-2; a "reboot" second pass may confirm only the first three.
            boot = "boot-2"
            engine(drive).run(id)
            engine(drive).unprovenNow(id) shouldBe 3
            val verifiedInBoot1 = store.files(id).filter { it.verifyBootId == "boot-1" }.map { it.sourcePath }.toSet()
            verifiedInBoot1.size shouldBe 3
            val result = engine(drive).confirm(id, FreshRead.REBOOTED)
            result.ok shouldBe 3
            result.unproven shouldBe 3
            // Exactly the files first checked in boot-1 are confirmed (their baseline is now this check, PR #54).
            store.files(id).filter { it.confirmedAt != null }.map { it.sourcePath }.toSet() shouldBe verifiedInBoot1
            store.files(id).filter { it.confirmedAt != null }.map { it.verifyBootId }.toSet() shouldBe setOf("boot-2")
        }

    private class StopHere : RuntimeException()

    @Test
    fun `attach generations compare within and across processes (D4)`() {
        val t = AttachTracker("p1")
        t.onState("1234-ABCD", mounted = true) // first sighting: not a re-attach
        t.generation("1234-abcd") shouldBe AttachGeneration("p1", 0)
        t.onState("1234-abcd", mounted = false)
        t.generation("1234-ABCD").count shouldBe 0 // away, not back yet
        t.onState("1234-ABCD", mounted = true)
        t.generation("1234-ABCD") shouldBe AttachGeneration("p1", 1)
        AttachGeneration("p1", 1).reattachedSince(AttachGeneration("p1", 0)) shouldBe true
        AttachGeneration("p1", 1).reattachedSince(AttachGeneration("p1", 1)) shouldBe false
        // An earlier process ended before this one began: any re-attach seen now came after it.
        AttachGeneration("p2", 1).reattachedSince(AttachGeneration("p1", 5)) shouldBe true
        AttachGeneration("p2", 0).reattachedSince(AttachGeneration("p1", 5)) shouldBe false
        AttachGeneration.decode(AttachGeneration("p1", 3).encode()) shouldBe AttachGeneration("p1", 3)
        listOf(null, "", "x@p", "-1@p", "3@", "3").forEach { AttachGeneration.decode(it) shouldBe null }
    }

    /** Rows as an older version left them: verified, no per-file record; [jobBoot] = the job's own boot id. */
    private suspend fun legacyJob(drive: FakeCapacityDrive, jobBoot: String?): String {
        val id = job(drive)
        engine(drive).run(id)
        store.putJob(store.job(id)!!.copy(verifyBootId = jobBoot))
        store.files(id).forEach { store.putFile(it.copy(verifyBootId = null, verifyAttach = null)) }
        return id
    }

    @Test
    fun `older verified files get a baseline, then a real unplug and plug-in proves them (PR 43 review)`(): Unit =
        runBlocking {
            val drive = FakeCapacityDrive(realBytes = 1_000_000)
            // An interrupted older job: no boot id on the job, none per file.
            val id = legacyJob(drive, jobBoot = null)
            val first = engine(drive).confirm(id, FreshRead.REATTACHED)
            first.ok shouldBe 0
            first.unproven shouldBe 6
            // The baseline is now: this boot and the drive's current generation.
            store.files(id).all { it.verifyBootId == boot && it.verifyAttach == "0@process-1" } shouldBe true
            // Without a real event nothing changes.
            drive.eject()
            engine(drive).confirm(id, FreshRead.REATTACHED).unproven shouldBe 6
            reattach(drive)
            engine(drive).confirm(id, FreshRead.REATTACHED).ok shouldBe 6
            engine(drive).originalsPlan(id).size shouldBe 6
        }

    @Test
    fun `the app's pre-check records the baseline, so the unplug it asks for counts (PR 43 review)`(): Unit =
        runBlocking {
            val drive = FakeCapacityDrive(realBytes = 1_000_000)
            // A completed older job whose boot id is the current boot: nothing proven yet.
            val id = legacyJob(drive, jobBoot = boot)
            engine(drive).unprovenNow(id) shouldBe 6
            reattach(drive)
            engine(drive).unprovenNow(id) shouldBe 0
            engine(drive).confirm(id, FreshRead.REATTACHED).ok shouldBe 6
        }

    @Test
    fun `an older file's job boot id alone no longer proves a fresh read, a reboot after the baseline does`(): Unit =
        runBlocking {
            val drive = FakeCapacityDrive(realBytes = 1_000_000)
            val id = legacyJob(drive, jobBoot = "boot-0")
            engine(drive).confirm(id, FreshRead.REBOOTED).unproven shouldBe 6
            boot = "boot-2"
            engine(drive).confirm(id, FreshRead.REBOOTED).ok shouldBe 6
        }

    @Test
    fun `a re-check after an app restart in the same boot needs a fresh read of confirmed copies (PR 54)`(): Unit =
        runBlocking {
            val drive = FakeCapacityDrive(realBytes = 1_000_000)
            val id = job(drive)
            engine(drive).run(id).state shouldBe JobState.DONE
            reattach(drive)
            engine(drive).confirm(id, FreshRead.REATTACHED).ok shouldBe 6
            // KeepClear's process restarts; the phone doesn't reboot. Months later the reminder asks for a re-check.
            attach = AttachTracker("process-2")
            engine(drive).unprovenNow(id) shouldBe 0
            // The re-check must not read at once: none of the confirmed copies has a fresh-read proof now.
            engine(drive).unprovenNow(id, includeConfirmed = true) shouldBe 6
            reattach(drive)
            engine(drive).unprovenNow(id, includeConfirmed = true) shouldBe 0
            engine(drive).confirm(id, FreshRead.REATTACHED).ok shouldBe 6
            // That read is the new baseline: another re-check in this process needs another unplug first.
            engine(drive).unprovenNow(id, includeConfirmed = true) shouldBe 6
        }
}
