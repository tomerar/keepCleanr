// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.move.FreeReport
import io.github.tomerar.keepclear.engine.restore.RestorePreview
import io.github.tomerar.keepclear.engine.restore.RestoreReport
import io.github.tomerar.keepclear.engine.scan.ScanItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Owner brief H3 item 4: "What KeepClear did" and „פיניתם עד היום”. The store's format (C163), which results count
 * as space really freed, and that the app records from its real result paths, never in test mode (C164).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H3HistoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Memory(var text: String? = null) : ActivityHistory.Backend {
        var writes = 0

        override fun read() = text

        override fun write(text: String) {
            writes++
            this.text = text
        }
    }

    private fun op(kind: OpKind, size: Long, path: String = "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg") =
        PlannedOp(KPath.Local(path), path, kind, false, size, 1)

    // ------------------------------------------------------------------------------------- format and total

    @Test
    fun `the store writes counts, sizes, times and kinds only, and reads them back`() {
        val memory = Memory()
        val history = ActivityHistory(memory)
        val ops =
            listOf(
                op(OpKind.DELETE_FOR_GOOD, 300),
                op(OpKind.TRASH, 700, "/storage/emulated/0/Documents/divorce papers.pdf"),
            )
        history.record(HistoryEvents.fromOps(ops, at = 1_000))
        val text = memory.text!!
        assertTrue(text.startsWith(HistoryFormat.HEADER))
        // Never a path or a name.
        assertTrue(text, '/' !in text && "divorce" !in text && "IMG" !in text)
        val back = ActivityHistory(Memory(text)).load()
        assertEquals(listOf(HistoryEvent(1_000, HistoryKind.CLEANED, 2, 1_000, freedBytes = 300)), back.events)
        assertEquals(300L, back.totalFreed)
    }

    @Test
    fun `only space that really came back counts in the total`() {
        val history = ActivityHistory(Memory())
        // To the trash: nothing freed yet. Emptying it: freed. Restoring: nothing.
        history.record(HistoryEvents.fromOps(listOf(op(OpKind.TRASH, 5_000)), at = 1))
        assertEquals(0L, history.state.value.totalFreed)
        history.record(HistoryEvents.fromOps(listOf(op(OpKind.PURGE, 5_000)), at = 2))
        assertEquals(5_000L, history.state.value.totalFreed)
        history.record(HistoryEvents.fromOps(listOf(op(OpKind.RESTORE, 9_000)), at = 3))
        assertEquals(5_000L, history.state.value.totalFreed)
        // P1: deleted for good after a checked backup frees; to the trash with the setting doesn't.
        history.record(
            HistoryEvents.fromFree(
                FreeReport(total = 2, deletedForGood = listOf(op(OpKind.MOVE_DELETE_ORIGINAL, 40))),
                4,
            ),
        )
        history.record(
            HistoryEvents.fromFree(FreeReport(total = 1, trashed = listOf(op(OpKind.MOVE_DELETE_ORIGINAL, 60))), 5),
        )
        assertEquals(5_040L, history.state.value.totalFreed)
        history.record(HistoryEvents.fromTrim(123, 6) + HistoryEvents.fromExpired(1_000, 7))
        assertEquals(6_163L, history.state.value.totalFreed)
        // A copy frees nothing.
        history.record(HistoryEvents.fromBackup(files = 3, bytes = 9_999, at = 8))
        assertEquals(6_163L, history.state.value.totalFreed)
        assertEquals(
            listOf(
                HistoryKind.BACKED_UP,
                HistoryKind.TRASH_EXPIRED,
                HistoryKind.CACHES_TRIMMED,
                HistoryKind.ORIGINALS_TO_TRASH,
                HistoryKind.FREED_AFTER_BACKUP,
                HistoryKind.RESTORED_FROM_TRASH,
                HistoryKind.TRASH_EMPTIED,
                HistoryKind.CLEANED,
            ),
            history.state.value.events.map { it.kind },
        )
    }

    @Test
    fun `test-mode results record nothing`() {
        val memory = Memory()
        val history = ActivityHistory(memory)
        history.record(
            HistoryEvents.fromFree(FreeReport(total = 2, simulated = listOf(op(OpKind.MOVE_DELETE_ORIGINAL, 40))), 1),
        )
        history.record(HistoryEvents.fromRestore(RestoreReport(simulated = RestorePreview(1, 0, 0, 10, 0)), 1))
        history.record(HistoryEvents.fromTrim(0, 1))
        history.record(HistoryEvents.fromOps(emptyList(), 1))
        assertEquals(0, memory.writes)
        assertEquals(HistoryState(), history.state.value)
    }

    @Test
    fun `old lines are dropped past the limit, the total never is`() {
        val history = ActivityHistory(Memory())
        repeat(HistoryFormat.MAX_EVENTS + 20) { i -> history.record(HistoryEvents.fromTrim(10, at = i.toLong())) }
        assertEquals(HistoryFormat.MAX_EVENTS, history.state.value.events.size)
        assertEquals(10L * (HistoryFormat.MAX_EVENTS + 20), history.state.value.totalFreed)
        assertEquals((HistoryFormat.MAX_EVENTS + 19).toLong(), history.state.value.events.first().at)
    }

    @Test
    fun `an unknown version or a bad line is never guessed`() {
        assertEquals(HistoryState(), HistoryFormat.read("keepclear-history v=9\ntotal=5\n1|CLEANED|1|1|1\n"))
        val read = HistoryFormat.read(
            "${HistoryFormat.HEADER}\ntotal=50\n1|CLEANED|1|10|0\n2|SOMETHING_NEW|1|1|1\n3|CLEANED|x|1|1\n4|TRASH_EMPTIED|1|-5|0\n",
        )
        assertEquals(listOf(HistoryEvent(1, HistoryKind.CLEANED, 1, 10, 0)), read.events)
        assertEquals(50L, read.totalFreed)
    }

    @Test
    fun `stored kind names never change (MIG-2)`() {
        assertEquals(
            listOf(
                "CLEANED",
                "CACHES_TRIMMED",
                "TRASH_EMPTIED",
                "TRASH_EXPIRED",
                "BACKED_UP",
                "FREED_AFTER_BACKUP",
                "ORIGINALS_TO_TRASH",
                "RESTORED_FROM_TRASH",
                "RESTORED_FROM_BACKUP",
            ),
            HistoryKind.entries.map { it.name },
        )
    }

    // -------------------------------------------------------------------- the app records from its result paths

    private lateinit var t: TestApp

    @After
    fun tearDown() {
        if (::t.isInitialized) t.close()
    }

    private fun app(testMode: Boolean): TestApp {
        t = TestApp(tmp.newFolder("phone"))
        t.app.historyBackend = Memory()
        if (testMode) t.app.testMode.turnOn() else t.app.testMode.turnOff()
        return t
    }

    private fun classified(p: String) = runBlocking {
        val s = t.app.localGateway.stat(KPath.Local(p))!!
        Classifier().classify(
            ScanItem(s.path, p, s.size, s.mtimeMillis, identity = s.identity),
            now = System.currentTimeMillis(),
        )
    }

    private fun awaitHistory(what: String, done: (HistoryState) -> Boolean) =
        TestApp.awaitMain(what) { done(t.app.history.state.value) }

    @Test
    fun `a clean is recorded, and a move to the trash frees nothing yet`() {
        app(testMode = false)
        val p = t.file("Pictures/Old/a.jpg", ByteArray(1_500) { 3 }).absolutePath
        val vm = t.viewModel(HomeViewModel::class.java)
        vm.clean(listOf(classified(p)), userConfirmed(listOf(p)))
        awaitHistory("clean recorded") { it.events.any { e -> e.kind == HistoryKind.CLEANED } }
        TestApp.awaitMain("trash loaded") { !vm.state.value.busy && vm.state.value.trash.entries.size == 1 }
        val cleaned = t.app.history.state.value.events.single()
        assertEquals(1, cleaned.files)
        assertEquals(1_500L, cleaned.bytes)
        assertEquals(0L, cleaned.freedBytes) // to the trash: nothing freed yet
        // Emptying the trash runs the same trash-plan path as restoring (next test); the guard only purges inside a
        // real volume's trash, which Robolectric's temporary folder isn't, so the purge's own rule is tested above.
        assertTrue(!File(p).exists())
    }

    @Test
    fun `restoring from the trash is recorded and frees nothing`() {
        app(testMode = false)
        val p = t.file("Pictures/Old/b.jpg", ByteArray(800) { 4 }).absolutePath
        runBlocking {
            val s = t.app.localGateway.stat(KPath.Local(p))!!
            val op = PlannedOp(s.path, p, OpKind.TRASH, false, s.size, s.mtimeMillis, s.identity)
            t.app.executor().run(
                ActionPlan(
                    UUID.randomUUID().toString(),
                    listOf(op),
                    "clean",
                ).confirm(userConfirmed(listOf(p)), t.app.sessionId),
            )
        }
        val entries = runBlocking { t.app.trashManager.entries() }
        val vm = t.viewModel(HomeViewModel::class.java)
        vm.restore(entries, userConfirmed(listOf(p)))
        awaitHistory("restore recorded") { it.events.any { e -> e.kind == HistoryKind.RESTORED_FROM_TRASH } }
        assertTrue(File(p).exists())
        assertEquals(0L, t.app.history.state.value.totalFreed)
    }

    @Test
    fun `in test mode a clean records nothing`() {
        app(testMode = true)
        val p = t.file("Pictures/Old/c.jpg", ByteArray(900) { 5 }).absolutePath
        val vm = t.viewModel(HomeViewModel::class.java)
        vm.clean(listOf(classified(p)), userConfirmed(listOf(p)))
        TestApp.awaitMain("simulated") { vm.state.value.cleanResult?.simulated == true && !vm.state.value.busy }
        TestApp.settle(300)
        assertTrue(File(p).exists())
        assertEquals(HistoryState(), t.app.history.state.value)
    }
}
