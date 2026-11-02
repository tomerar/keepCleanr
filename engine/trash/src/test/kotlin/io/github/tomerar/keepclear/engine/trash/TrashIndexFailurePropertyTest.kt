// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.OpResult
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * D6: the trash index is a Room database, and a full disk makes it throw `SQLiteFullException` (a
 * `RuntimeException`, not a storage error). Here the index throws random non-storage exceptions while the app keeps
 * running: every clean, restore and purge goes through the [ActionExecutor], which must never throw, must report
 * the failure, and the no-loss invariant must hold once the database works again and the trash is recovered.
 */
class TrashIndexFailurePropertyTest {
    /** Fails like Room does on a full disk: before anything is written, with a non-storage exception. */
    private class FailingIndex(private val inner: TrashIndex, private val random: Random) : TrashIndex {
        var failRate = 0.0

        private fun maybeFail() {
            if (random.nextDouble() >= failRate) return
            throw if (random.nextBoolean()) {
                IllegalStateException("database or disk is full (code 13 SQLITE_FULL)")
            } else {
                RuntimeException("Room: cannot commit transaction")
            }
        }

        override suspend fun put(entry: TrashEntry) {
            maybeFail()
            inner.put(entry)
        }

        override suspend fun get(id: String): TrashEntry? {
            maybeFail()
            return inner.get(id)
        }

        override suspend fun byTrashedPath(path: String): TrashEntry? {
            maybeFail()
            return inner.byTrashedPath(path)
        }

        override suspend fun all(): List<TrashEntry> {
            maybeFail()
            return inner.all()
        }

        override suspend fun remove(id: String) {
            maybeFail()
            inner.remove(id)
        }
    }

    @Test
    fun `random database errors during clean, restore and purge never crash and never lose a file`(): Unit =
        runBlocking {
            val random = Random(11)
            var failuresReported = 0
            repeat(1_000) { iteration ->
                val root = "/storage/emulated/0"
                val fake = FakeStorageGateway(caseInsensitive = true)
                val stored = InMemoryTrashIndex()
                val index = FailingIndex(stored, random)
                val files = (0 until 4).associate { i ->
                    val path = "$root/DCIM/f$i.jpg"
                    val content = ByteArray(100 + i) { (it * (i + 5)).toByte() }
                    fake.addFile(path, content, mtime = 1)
                    path to Sha256.of(content)
                }
                val protectedPaths = ProtectedPaths()

                fun manager() = TrashManager(fake, index, FakeSigner(), FakeClock(), protectedPaths, listOf(root))
                val executor = ActionExecutor(fake, SafetyGuard(protectedPaths), protectedPaths, manager(), "s")
                val purged = mutableSetOf<String>()

                index.failRate = 0.05 + random.nextDouble() * 0.4
                for (step in 0 until 8) {
                    val active = stored.all().filter { it.state == TrashState.ACTIVE }
                    val choice = random.nextInt(3)
                    val op = when {
                        choice == 0 || active.isEmpty() -> {
                            val candidates = files.keys.filter { fake.exists(it) }
                            if (candidates.isEmpty()) continue
                            val path = candidates[random.nextInt(candidates.size)]
                            val s = fake.stat(KPath.Local(path))!!
                            PlannedOp(s.path, path, OpKind.TRASH, false, s.size, s.mtimeMillis, s.identity)
                        }

                        else -> {
                            val e = active[random.nextInt(active.size)]
                            val s = fake.stat(KPath.Local(e.trashedPath)) ?: continue
                            val kind = if (choice == 1) OpKind.RESTORE else OpKind.PURGE
                            if (kind == OpKind.PURGE) purged += e.originalPath
                            PlannedOp(s.path, e.trashedPath, kind, false, s.size, s.mtimeMillis, s.identity)
                        }
                    }
                    val plan = ActionPlan("p$step", listOf(op), "test").confirm(now = 1, session = "s")
                    // Must never throw: a database error is a reported failure (D6).
                    val report = executor.run(plan) as ExecutionReport.Ran
                    failuresReported += report.results.count { it is OpResult.Failed }
                }

                // The database works again; the next start recovers the trash.
                index.failRate = 0.0
                manager().recover()

                val trashed = stored.all().filter { it.state == TrashState.ACTIVE }.associateBy { it.originalPath }
                for ((path, hash) in files) {
                    val atOriginal = fake.exists(path)
                    val restored = fake.files("$root/DCIM").filter {
                        it.startsWith(path.substringBeforeLast('.') + " (restored")
                    }
                    val inTrash = trashed[path]?.let { fake.exists(it.trashedPath) } == true
                    val copies = listOf(atOriginal, inTrash).count { it } + restored.size
                    if (path in purged && copies == 0) continue
                    check(copies == 1) {
                        "iteration $iteration: $path has $copies copies (original=$atOriginal trash=$inTrash " +
                            "restored=$restored)"
                    }
                    val where = when {
                        atOriginal -> path
                        inTrash -> trashed.getValue(path).trashedPath
                        else -> restored.single()
                    }
                    check(Sha256.of(fake.readAllBytes(where)) == hash) { "iteration $iteration: $path changed" }
                }
                // No file in the trash without a record (it could never be restored or purged).
                val recorded = stored.all().map { it.trashedPath.lowercase() }.toSet()
                fake.files("$root/.KeepClearTrash").filter { it.endsWith(".jpg") }.forEach {
                    check(it.lowercase() in recorded) { "iteration $iteration: orphan in the trash $it" }
                }
                stored.all().forEach {
                    check(it.state == TrashState.ACTIVE && fake.exists(it.trashedPath)) { "dangling $it" }
                }
            }
            check(failuresReported > 0) { "the property test never hit a database error" }
        }
}
