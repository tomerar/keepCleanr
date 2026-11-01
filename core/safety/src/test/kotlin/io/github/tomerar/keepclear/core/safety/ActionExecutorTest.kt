// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ActionExecutorTest {
    private val fake = FakeStorageGateway(caseInsensitive = true)
    private val protectedPaths = ProtectedPaths()
    private val handler = RecordingHandler(moveToTrash = true)

    private fun executor(dryRun: Boolean = false, h: ActionHandler = handler) =
        ActionExecutor(fake, SafetyGuard(protectedPaths), protectedPaths, h, SESSION, dryRun = dryRun)

    private val a = "$ROOT/DCIM/Camera/a.jpg"
    private val b = "$ROOT/DCIM/Camera/b.jpg"

    init {
        fake.addFile(a, size = 1000, mtime = 10)
        fake.addFile(b, size = 2000, mtime = 20)
    }

    private fun ran(report: ExecutionReport) = report as ExecutionReport.Ran

    @Test
    fun `performs confirmed ops through the handler`(): Unit = runBlocking {
        val report = ran(executor().run(plan(fake.op(a), fake.op(b))))
        report.done.size shouldBe 2
        fake.exists(a) shouldBe false
        fake.files("$ROOT/.KeepClearTrash").size shouldBe 2
    }

    @Test
    fun `unconfirmed plan never reaches the handler (SAFETY-1)`(): Unit = runBlocking {
        executor().run(ActionPlan("p", listOf(fake.op(a)), "t")).shouldBeInstanceOf<ExecutionReport.Rejected>()
        handler.performed.shouldBeEmpty()
        fake.exists(a) shouldBe true
    }

    @Test
    fun `dry run simulates and changes nothing (SAFETY-9)`(): Unit = runBlocking {
        val report = ran(executor(dryRun = true).run(plan(fake.op(a))))
        report.results.single().shouldBeInstanceOf<OpResult.Simulated>()
        handler.performed.shouldBeEmpty()
        fake.exists(a) shouldBe true
    }

    @Test
    fun `turning dry run on mid-plan stops every remaining operation (SAFETY-9)`(): Unit = runBlocking {
        var testMode = false
        val executor = ActionExecutor(
            fake,
            SafetyGuard(protectedPaths),
            protectedPaths,
            handler,
            SESSION,
            isDryRun = { testMode },
            onResult = { testMode = true }, // switched on right after the first operation
        )
        val report = ran(executor.run(plan(fake.op(a), fake.op(b))))
        report.results[0].shouldBeInstanceOf<OpResult.Done>()
        report.results[1].shouldBeInstanceOf<OpResult.Simulated>()
        handler.performed.size shouldBe 1
        fake.exists(b) shouldBe true
    }

    @Test
    fun `changed size or mtime is skipped (SAFETY-6, E-DEL-1)`(): Unit = runBlocking {
        val opA = fake.op(a)
        val opB = fake.op(b)
        fake.modify(a, FakeStorageGateway.Content.Virtual(1, 1001), mtime = 10)
        fake.setMtime(b, 21)
        val report = ran(executor().run(plan(opA, opB)))
        report.skipped.map { it.reason } shouldBe listOf(SkipReason.CHANGED, SkipReason.CHANGED)
        handler.performed.shouldBeEmpty()
    }

    @Test
    fun `missing file is skipped`(): Unit = runBlocking {
        val opA = fake.op(a)
        fake.delete(KPath.Local(a))
        ran(executor().run(plan(opA))).skipped.single().reason shouldBe SkipReason.MISSING
    }

    @Test
    fun `file swapped for a symlink is skipped (SEC-4, E-SEC-7)`(): Unit = runBlocking {
        val opA = fake.op(a)
        fake.delete(KPath.Local(a))
        fake.addSymlink(a, "$ROOT/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14")
        ran(executor().run(plan(opA))).skipped.single().reason shouldBe SkipReason.SYMLINK
        handler.performed.shouldBeEmpty()
    }

    @Test
    fun `file replaced by another with same size and mtime is skipped by identity`(): Unit = runBlocking {
        val opA = fake.op(a)
        fake.delete(KPath.Local(a))
        fake.addFile(a, size = 1000, mtime = 10, seed = 999)
        ran(executor().run(plan(opA))).skipped.single().reason shouldBe SkipReason.IDENTITY_CHANGED
    }

    @Test
    fun `directory containing a protected file is refused at the choke point`(): Unit = runBlocking {
        val dir = "$ROOT/Download/old phone"
        fake.addFile("$dir/photo.jpg", size = 10)
        fake.addFile("$dir/WhatsApp/Databases/msgstore.db.crypt14", size = 10)
        ran(executor().run(plan(fake.op(dir)))).skipped.single().reason shouldBe SkipReason.CONTAINS_PROTECTED
        fake.exists("$dir/WhatsApp/Databases/msgstore.db.crypt14") shouldBe true
    }

    @Test
    fun `directory that changed after confirmation is skipped (SAFETY-6)`(): Unit = runBlocking {
        var now = 100L
        val g = FakeStorageGateway(caseInsensitive = true, clock = { now++ })
        g.mkdirs("$ROOT/Download/empty")
        val dirOp = g.op("$ROOT/Download/empty")
        g.addFile("$ROOT/Download/empty/new.pdf", size = 5)
        val ex = ActionExecutor(g, SafetyGuard(protectedPaths), protectedPaths, handler, SESSION)
        ran(ex.run(plan(dirOp))).skipped.single().reason shouldBe SkipReason.CHANGED
        g.exists("$ROOT/Download/empty/new.pdf") shouldBe true
    }

    @Test
    fun `directory containing a symlink is refused`(): Unit = runBlocking {
        val dir = "$ROOT/Download/links"
        fake.addSymlink("$dir/l", "$ROOT/DCIM")
        ran(executor().run(plan(fake.op(dir)))).skipped.single().reason shouldBe SkipReason.CONTAINS_PROTECTED
    }

    @Test
    fun `strict mode re-hashes MOVE originals (SEC-4, Q13)`(): Unit = runBlocking {
        val sha = fake.openRead(KPath.Local(a)).use { Sha256.of(it) }
        val opA = fake.op(a, OpKind.MOVE_DELETE_ORIGINAL, sha = sha)
        // Same size and mtime, different bytes: only the re-hash can catch it.
        fake.modify(a, FakeStorageGateway.Content.Virtual(12345, 1000), mtime = 10)
        ran(executor().run(plan(opA))).skipped.single().reason shouldBe SkipReason.HASH_MISMATCH
        fake.exists(a) shouldBe true
    }

    @Test
    fun `strict mode passes an unchanged original`(): Unit = runBlocking {
        val sha = fake.openRead(KPath.Local(a)).use { Sha256.of(it) }
        ran(executor().run(plan(fake.op(a, OpKind.MOVE_DELETE_ORIGINAL, sha = sha)))).done.size shouldBe 1
    }

    @Test
    fun `ENOSPC in the handler fails one op and the run continues (LOW-4)`(): Unit = runBlocking {
        var calls = 0
        val flaky = ActionHandler { op, gateway ->
            calls++
            if (calls == 1) throw StorageException.NoSpace(op.ref)
            handler.perform(op, gateway)
        }
        val report = ran(executor(h = flaky).run(plan(fake.op(a), fake.op(b))))
        report.failed.single().error.shouldBeInstanceOf<StorageException.NoSpace>()
        report.done.size shouldBe 1
    }

    @Test
    fun `revoked permission stops the remaining ops (E-PERM-2)`(): Unit = runBlocking {
        val opA = fake.op(a)
        val opB = fake.op(b)
        fake.revoke("$ROOT/DCIM")
        val report = ran(executor().run(plan(opA, opB)))
        report.failed.single().error.shouldBeInstanceOf<StorageException.PermissionDenied>()
        report.skipped.single().reason shouldBe SkipReason.STOPPED
    }

    @Test
    fun `handler sees ops only after the guard`(): Unit = runBlocking {
        val sneaky = object : StorageGateway by fake {}
        val ex = ActionExecutor(sneaky, SafetyGuard(protectedPaths), protectedPaths, handler, SESSION)
        val db = "$ROOT/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14"
        fake.addFile(db, size = 5)
        ex.run(plan(fake.op(db))).shouldBeInstanceOf<ExecutionReport.Rejected>()
        handler.performed.shouldBeEmpty()
    }

    @Test
    fun `a duplicate whose witness vanished or changed is skipped (H2, SAFETY-8)`(): Unit = runBlocking {
        val witness = Witness(KPath.Local(b), "b", size = 2000)
        val report = ran(executor().run(plan(fake.op(a).copy(witnesses = listOf(witness)))))
        report.done.size shouldBe 1

        fake.addFile(a, size = 1000, mtime = 10)
        fake.modify(b, FakeStorageGateway.Content.Bytes(ByteArray(5)), mtime = 21)
        val changed = ran(executor().run(plan(fake.op(a).copy(witnesses = listOf(witness)))))
        (changed.results.single() as OpResult.Skipped).reason shouldBe SkipReason.WITNESS_GONE
        fake.delete(KPath.Local(b))
        val gone = ran(executor().run(plan(fake.op(a).copy(witnesses = listOf(witness)))))
        (gone.results.single() as OpResult.Skipped).reason shouldBe SkipReason.WITNESS_GONE
        fake.exists(a) shouldBe true
    }

    private data class Dialog(override val ops: List<PlannedOp>) : ConsentRequest

    /** Stands in for the MediaStore trash: Android asks the user itself (M12). */
    private class ConsentHandler : ActionHandler {
        val asked = mutableListOf<List<PlannedOp>>()

        override suspend fun perform(op: PlannedOp, gateway: StorageGateway) = error("must not run without consent")

        override fun needsConsent(op: PlannedOp) = true

        override suspend fun requestConsent(ops: List<PlannedOp>): List<ConsentRequest> {
            asked += ops
            return listOf(Dialog(ops))
        }
    }

    @Test
    fun `items Android must confirm are checked first, then handed over in one batch (M12)`(): Unit = runBlocking {
        val h = ConsentHandler()
        fake.modify(b, FakeStorageGateway.Content.Bytes(ByteArray(3)), mtime = 99) // changed since the scan
        val report =
            ran(executor(h = h).run(plan(fake.op(a), fake.op(b).copy(expectedSize = 2000, expectedMtime = 20))))
        report.results[0].shouldBeInstanceOf<OpResult.NeedsConsent>()
        (report.results[1] as OpResult.Skipped).reason shouldBe SkipReason.CHANGED
        h.asked.single().map { it.displayPath } shouldBe listOf(a)
        report.consent.single().ops.map { it.displayPath } shouldBe listOf(a)
        fake.exists(a) shouldBe true
    }

    @Test
    fun `in test mode nothing is handed to Android (SAFETY-9, M12)`(): Unit = runBlocking {
        val h = ConsentHandler()
        val report = ran(executor(dryRun = true, h = h).run(plan(fake.op(a))))
        report.results.single().shouldBeInstanceOf<OpResult.Simulated>()
        h.asked.shouldBeEmpty()
        report.consent.shouldBeEmpty()
    }
}
