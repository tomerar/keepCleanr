// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * The no-loss invariant (edge-cases.md) under random kill points: kill the process at any storage
 * operation during trash / restore / purge, restart (new manager + recover), and every file the user
 * didn't purge must exist exactly once, byte-identical, either at its original path or in the trash.
 */
class TrashKillPointPropertyTest {
    private class Killed : RuntimeException()

    @Test
    fun `no file is ever lost or duplicated across 2000 random crashes`(): Unit = runBlocking {
        val random = Random(7)
        repeat(2_000) { iteration ->
            val root = "/storage/emulated/0"
            val fake = FakeStorageGateway(caseInsensitive = true)
            val index = InMemoryTrashIndex()
            val clock = FakeClock()
            val files = (0 until 4).associate { i ->
                val path = "$root/DCIM/f$i.jpg"
                val content = ByteArray(100 + i) { (it * (i + 3)).toByte() }
                fake.addFile(path, content, mtime = 1)
                path to Sha256.of(content)
            }

            fun manager() = TrashManager(fake, index, FakeSigner(), clock, ProtectedPaths(), listOf(root))
            val purged = mutableSetOf<String>()

            var opsBeforeKill = random.nextInt(40)
            fake.beforeOp = { _, _ -> if (opsBeforeKill-- == 0) throw Killed() }
            try {
                val m = manager()
                for (step in 0 until 6) {
                    val choice = random.nextInt(3)
                    val active = index.all().filter { it.state == TrashState.ACTIVE }
                    when {
                        choice == 0 || active.isEmpty() -> {
                            val candidates = files.keys.filter { fake.exists(it) }
                            if (candidates.isEmpty()) continue
                            val path = candidates[random.nextInt(candidates.size)]
                            val s = fake.stat(KPath.Local(path))!!
                            m.perform(
                                PlannedOp(s.path, path, OpKind.TRASH, false, s.size, s.mtimeMillis, s.identity),
                                fake,
                            )
                        }

                        choice == 1 -> {
                            val e = active[random.nextInt(active.size)]
                            m.perform(
                                PlannedOp(
                                    KPath.Local(e.trashedPath),
                                    e.trashedPath,
                                    OpKind.RESTORE,
                                    false,
                                    e.size,
                                    e.mtimeMillis,
                                ),
                                fake,
                            )
                        }

                        else -> {
                            val e = active[random.nextInt(active.size)]
                            purged += e.originalPath // intent to purge: allowed to be gone
                            m.perform(
                                PlannedOp(
                                    KPath.Local(e.trashedPath),
                                    e.trashedPath,
                                    OpKind.PURGE,
                                    false,
                                    e.size,
                                    e.mtimeMillis,
                                ),
                                fake,
                            )
                        }
                    }
                }
            } catch (e: Killed) {
                // process died here
            }
            fake.beforeOp = null
            manager().recover()

            val trashed = index.all().filter { it.state == TrashState.ACTIVE }.associateBy { it.originalPath }
            for ((path, hash) in files) {
                val atOriginal = files.keys.contains(path) && fake.exists(path)
                val restoredCopies = fake.files("$root/DCIM").filter {
                    it.startsWith(path.substringBeforeLast('.') + " (restored")
                }
                val inTrash = trashed[path]?.let { fake.exists(it.trashedPath) } == true
                val copies = listOf(atOriginal, inTrash).count { it } + restoredCopies.size
                if (path in purged && copies == 0) continue
                check(copies == 1) {
                    "iteration $iteration: $path has $copies copies (original=$atOriginal trash=$inTrash restored=$restoredCopies)"
                }
                val where = when {
                    atOriginal -> path
                    inTrash -> trashed.getValue(path).trashedPath
                    else -> restoredCopies.single()
                }
                check(Sha256.of(fake.readAllBytes(where)) == hash) { "iteration $iteration: $path content changed" }
            }
            // Every index entry points at a real file (no dangling rows after recovery).
            index.all().forEach {
                check(it.state == TrashState.ACTIVE && fake.exists(it.trashedPath)) { "dangling $it" }
            }
        }
    }
}
