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

    private fun engine(drive: FakeCapacityDrive) = MoveEngine(fake, drive, store, clock = { 5L }, bootId = { boot })

    @Test
    fun `a fake-capacity stick passes the first check from cache but never frees originals`(): Unit = runBlocking {
        val drive = FakeCapacityDrive(realBytes = 25_000)
        val id = job(drive)
        engine(drive).run(id).state shouldBe JobState.DONE
        // Every file "verified" from the page cache...
        store.files(id).all { it.state == MoveFileState.VERIFIED } shouldBe true
        // ...but nothing may be freed before a second, uncached pass.
        engine(drive).originalsPlan(id).shouldBeEmpty()

        drive.eject()
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
    fun `a second pass in the same boot without unplugging is refused`(): Unit = runBlocking {
        val drive = FakeCapacityDrive(realBytes = 1_000_000)
        val id = job(drive)
        engine(drive).run(id)
        shouldThrow<IllegalStateException> { engine(drive).confirm(id, FreshRead.REBOOTED) }
        engine(drive).originalsPlan(id).shouldBeEmpty()
        boot = "boot-2"
        engine(drive).confirm(id, FreshRead.REBOOTED).ok shouldBe 6
        engine(drive).originalsPlan(id).size shouldBe 6
    }

    @Test
    fun `the copy is re-hashed at free time, not only its size`(): Unit = runBlocking {
        val drive = FakeCapacityDrive(realBytes = 1_000_000)
        val id = job(drive)
        engine(drive).run(id)
        drive.eject()
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
}
