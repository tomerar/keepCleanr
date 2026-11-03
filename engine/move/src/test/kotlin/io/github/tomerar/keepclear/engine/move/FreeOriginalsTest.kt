// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.BackupProof
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.OpResult
import io.github.tomerar.keepclear.core.safety.PermanentKinds
import io.github.tomerar.keepclear.core.safety.PermanentReason
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SkipReason
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Owner decision P1 (2026-11-03): after a verified backup, "Free up space" deletes the originals for good, only when
 * the second check passed, the copy hashes right again at free time and the original is unchanged right before its
 * delete; through the one choke point, in batches, with test mode, PROTECTED and the session confirmation unchanged.
 */
class FreeOriginalsTest {
    private fun FreeRig.originalsLeft() = rig.items.count { fake.exists(it.path) }

    private fun FreeRig.assertCopiesMatchOriginalsOf(before: Map<String, List<Byte>>) {
        for (f in files()) {
            Sha256.of(fake.readAllBytes(copyOf(f))) shouldBe Sha256.of(before.getValue(f.sourcePath).toByteArray())
        }
    }

    @Test
    fun `after the second check the originals are deleted for good and the copies stay`(): Unit = runBlocking {
        val r = FreeRig(files = 5)
        val before = r.rig.sourceSnapshot()
        val id = r.backup()
        val progress = mutableListOf<FreeProgress>()
        val report = r.freer().free(id, r.rig.items.map { it.path }.toSet()) { progress += it }
        report.deletedForGood.size shouldBe 5
        report.deletedForGood.map { it.permanentReason }.toSet() shouldBe setOf(PermanentReason.VERIFIED_BACKUP)
        report.trashed.shouldBeEmpty()
        report.skippedCount shouldBe 0
        r.originalsLeft() shouldBe 0
        r.index.all().shouldBeEmpty()
        r.assertCopiesMatchOriginalsOf(before)
        // Batches with progress ("Deleted 4 of 5"): monotonic, ends with every file looked at.
        progress.last() shouldBe FreeProgress(processed = 5, freed = 5, total = 5)
        progress.zipWithNext().all { (a, b) -> b.processed >= a.processed && b.freed >= a.freed } shouldBe true
        progress.map { it.freed }.distinct().size shouldBe 6
    }

    @Test
    fun `without the second check nothing is deleted for good`(): Unit = runBlocking {
        val r = FreeRig(files = 3)
        val id = r.backup(secondCheck = false)
        val report = r.freer().free(id, r.rig.items.map { it.path }.toSet())
        report.deletedForGood.shouldBeEmpty()
        report.skipped shouldBe mapOf(FreeSkip.NOT_CONFIRMED to 3)
        r.originalsLeft() shouldBe 3
        // At the choke point too: an op without a second check (no proof) is skipped, never deleted or trashed.
        val confirmed = FreeRig(files = 1)
        val cid = confirmed.backup()
        val op = confirmed.rig.engine().originalsPlan(cid).single().copy(backupProof = null)
        val ran = confirmed.executor().run(ActionPlan("p", listOf(op), "move").confirm(1, "s")) as ExecutionReport.Ran
        ran.skipped.single().reason shouldBe SkipReason.COPY_NOT_CONFIRMED
        confirmed.originalsLeft() shouldBe 1
        confirmed.index.all().shouldBeEmpty()
    }

    @Test
    fun `a copy that no longer hashes right at free time is skipped and its original kept`(): Unit = runBlocking {
        val r = FreeRig(files = 4)
        val id = r.backup()
        val files = r.files()
        // One copy damaged on the drive (same size), one gone, after the second check passed.
        val damaged = r.copyOf(files[0])
        r.fake.modify(damaged, FakeStorageGateway.Content.Bytes(ByteArray(files[0].sourceSize.toInt())))
        r.fake.delete(io.github.tomerar.keepclear.core.storage.KPath.Local(r.copyOf(files[1])))
        val report = r.freer().free(id, r.rig.items.map { it.path }.toSet())
        report.skipped shouldBe mapOf(FreeSkip.COPY_DIFFERS to 1, FreeSkip.COPY_MISSING to 1)
        report.deletedForGood.map { it.displayPath } shouldBe listOf(files[2].sourcePath, files[3].sourcePath)
        r.fake.exists(files[0].sourcePath) shouldBe true
        r.fake.exists(files[1].sourcePath) shouldBe true
    }

    @Test
    fun `a copy whose reads come back wrong only at free time is skipped`(): Unit = runBlocking {
        val r = FreeRig(files = 2)
        val id = r.backup()
        r.rig.corruptReads = 1
        val report = r.freer().free(id, r.rig.items.map { it.path }.toSet())
        report.skipped shouldBe mapOf(FreeSkip.COPY_DIFFERS to 1)
        r.originalsLeft() shouldBe 1
    }

    @Test
    fun `an original that changed is skipped and kept, also when only its content changed`(): Unit = runBlocking {
        val r = FreeRig(files = 3)
        val id = r.backup()
        val items = r.rig.items
        // Content changed, same size and date: only the executor's strict re-hash (ADR 0007) sees it.
        r.fake.modify(
            items[0].path,
            FakeStorageGateway.Content.Bytes(ByteArray(items[0].size.toInt()) { 7 }),
            mtime = items[0].mtimeMillis,
        )
        r.fake.setMtime(items[1].path, items[1].mtimeMillis + 1)
        val report = r.freer().free(id, items.map { it.path }.toSet())
        report.skipped shouldBe mapOf(FreeSkip.ORIGINAL_CHANGED to 2)
        report.deletedForGood.map { it.displayPath } shouldBe listOf(items[2].path)
        r.fake.exists(items[0].path) shouldBe true
        r.fake.exists(items[1].path) shouldBe true
    }

    @Test
    fun `PROTECTED originals are refused`(): Unit = runBlocking {
        val r = FreeRig(files = 2)
        val id = r.backup()
        r.protectedPaths = ProtectedPaths(extraRoots = listOf("$PHONE/DCIM/Camera"))
        val report = r.freer().free(id, r.rig.items.map { it.path }.toSet())
        report.skipped shouldBe mapOf(FreeSkip.PROTECTED to 2)
        r.originalsLeft() shouldBe 2
    }

    @Test
    fun `test mode deletes nothing and says what would have been freed`(): Unit = runBlocking {
        val r = FreeRig(files = 3)
        val id = r.backup()
        r.dryRun = true
        val report = r.freer().free(id, r.rig.items.map { it.path }.toSet())
        report.simulated.size shouldBe 3
        report.simulated.all { it.kind == OpKind.DELETE_FOR_GOOD } shouldBe true
        report.simulated.sumOf { it.expectedSize } shouldBe r.rig.items.sumOf { it.size }
        report.deletedForGood.shouldBeEmpty()
        r.originalsLeft() shouldBe 3
        r.index.all().shouldBeEmpty()
    }

    @Test
    fun `with the keep-in-trash setting the originals go to the 30-day trash`(): Unit = runBlocking {
        val r = FreeRig(files = 3)
        val id = r.backup()
        r.forGood = false
        val report = r.freer().free(id, r.rig.items.map { it.path }.toSet())
        report.trashed.size shouldBe 3
        report.deletedForGood.shouldBeEmpty()
        r.originalsLeft() shouldBe 0
        val entries = r.index.all()
        entries.map { it.originalPath }.toSet() shouldBe r.rig.items.map { it.path }.toSet()
        entries.all { r.fake.exists(it.trashedPath) && it.verifiedCopy!!.startsWith("SanDisk: ") } shouldBe true
    }

    @Test
    fun `the confirmation in this session is still required`(): Unit = runBlocking {
        val r = FreeRig(files = 3)
        val id = r.backup()
        val paths = r.rig.items.map { it.path }.toSet()
        r.freer(confirmedIn = "an earlier session").free(id, paths).rejected shouldBe true
        // A tap that showed only one file can't free the other two.
        r.freer(shown = listOf(r.rig.items[0].path)).free(id, paths).rejected shouldBe true
        r.originalsLeft() shouldBe 3
        // Only what the user chose is freed (the free step's options left the rest on the phone).
        val one = r.freer(shown = listOf(r.rig.items[1].path)).free(id, setOf(r.rig.items[1].path))
        one.deletedForGood.map { it.displayPath } shouldBe listOf(r.rig.items[1].path)
        r.originalsLeft() shouldBe 2
    }

    @Test
    fun `the drive unplugged mid-free stops the free and touches nothing else`(): Unit = runBlocking {
        val r = FreeRig(files = 6)
        val id = r.backup()
        var reads = 0
        r.fake.beforeOp = { op, path ->
            if (op == FakeStorageGateway.Op.OPEN_READ && path.toString().startsWith(USB) && ++reads == 3) {
                r.fake.disconnect(USB)
            }
        }
        val report = r.freer().free(id, r.rig.items.map { it.path }.toSet())
        report.interrupted shouldBe true
        report.deletedForGood.size shouldBe 2
        r.originalsLeft() shouldBe 4
    }

    @Test
    fun `copies checked before the drive went away in the same batch are not freed`(): Unit = runBlocking {
        val r = FreeRig(files = 6)
        val id = r.backup()
        var reads = 0
        r.fake.beforeOp = { op, path ->
            if (op == FakeStorageGateway.Op.OPEN_READ && path.toString().startsWith(USB) && ++reads == 3) {
                r.fake.disconnect(USB)
            }
        }
        // One batch of all six: two copies are checked, then the drive goes away while checking the third.
        val report = r.freer(batchFiles = 200).free(id, r.rig.items.map { it.path }.toSet())
        report.interrupted shouldBe true
        report.deletedForGood.size shouldBe 0
        r.originalsLeft() shouldBe 6
    }

    @Test
    fun `the drive unplugged in the middle of a later batch frees only the batches before it`(): Unit =
        runBlocking {
            val r = FreeRig(files = 6)
            val id = r.backup()
            var reads = 0
            r.fake.beforeOp = { op, path ->
                if (op == FakeStorageGateway.Op.OPEN_READ && path.toString().startsWith(USB) && ++reads == 4) {
                    r.fake.disconnect(USB)
                }
            }
            val report = r.freer(batchFiles = 2).free(id, r.rig.items.map { it.path }.toSet())
            report.interrupted shouldBe true
            report.deletedForGood.size shouldBe 2
            r.originalsLeft() shouldBe 4
        }

    @Test
    fun `a copy check that is too old, or for other content, doesn't count`(): Unit = runBlocking {
        val r = FreeRig(files = 1)
        val id = r.backup()
        val op = r.rig.engine().originalsPlan(id).single()
        val proof = op.backupProof!!
        val stale = op.copy(
            backupProof = proof.copy(copyCheckedAt = r.rig.now - PermanentKinds.MAX_PROOF_AGE_MILLIS - 1),
        )
        val run = r.executor().run(ActionPlan("p", listOf(stale), "move").confirm(1, "s")) as ExecutionReport.Ran
        run.skipped.single().reason shouldBe SkipReason.COPY_NOT_CONFIRMED
        val noSecond = op.copy(backupProof = proof.copy(secondCheckAt = 0))
        (r.executor().run(ActionPlan("p", listOf(noSecond), "move").confirm(1, "s")) as ExecutionReport.Ran)
            .skipped.single().reason shouldBe SkipReason.COPY_NOT_CONFIRMED
        // A proof for other content, or on an op that isn't a MOVE original, is a structural error: rejected.
        val other = op.copy(backupProof = proof.copy(copySha256 = "0".repeat(64)))
        r.executor().run(ActionPlan("p", listOf(other), "move").confirm(1, "s"))
            .shouldBeInstanceOf<ExecutionReport.Rejected>()
        val onTrash = op.copy(kind = OpKind.TRASH)
        r.executor().run(ActionPlan("p", listOf(onTrash), "move").confirm(1, "s"))
            .shouldBeInstanceOf<ExecutionReport.Rejected>()
        r.originalsLeft() shouldBe 1
        // The same op with its fresh proof is deleted for good.
        (r.executor().run(ActionPlan("p", listOf(op), "move").confirm(1, "s")) as ExecutionReport.Ran)
            .results.single().shouldBeInstanceOf<OpResult.Done>()
        r.originalsLeft() shouldBe 0
    }

    @Test
    fun `each op carries the copy check of this free`(): Unit = runBlocking {
        val r = FreeRig(files = 2)
        val id = r.backup()
        val ops = r.rig.engine().originalsPlan(id)
        for ((op, f) in ops.zip(r.files())) {
            op.backupProof shouldBe BackupProof(
                targetId = r.rig.target.id,
                copyRel = f.relTarget,
                copySha256 = f.sourceSha256!!,
                secondCheckAt = f.confirmedAt!!,
                copyCheckedAt = r.rig.now,
            )
        }
    }
}
