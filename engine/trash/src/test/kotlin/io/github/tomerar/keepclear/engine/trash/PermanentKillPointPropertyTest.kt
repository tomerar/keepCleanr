// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * The no-loss invariant with the owner's decision of 2026-11-02: plans mix the two permanent kinds with every other
 * kind, under random rule labels, SAFE/REVIEW flags and kill points at any storage operation. After a restart
 * (new manager + recover), every file whose kind is **not** one of the two (by this test's own labels, not by
 * `PermanentKinds`) exists exactly once, byte-identical, at its original path or in the trash. Only a file that is
 * truly one of the two kinds, labelled with its own rule and classified SAFE, may be gone.
 */
class PermanentKillPointPropertyTest {
    private class Killed : RuntimeException()

    private val root = "/storage/emulated/0"
    private val now = 1_800_000_000_000L
    private val old = now - 3L * 24 * 3_600_000

    /** Path → the rule that really describes it, and whether that kind may be deleted for good. */
    private val candidates = listOf(
        Triple("$root/DCIM/.thumbnails/1.jpg", "thumbnails", true),
        Triple("$root/Download/a.tmp", "temp_files", true),
        Triple("$root/Download/.DS_Store", "os_junk", true),
        Triple("$root/WhatsApp/Media/.Statuses/s.jpg", "wa_status_old", true),
        Triple("$root/DCIM/Camera/.trashed-1700000000-IMG_7.jpg", "other_app_bin", true),
        Triple("$root/.FileManagerRecycler/IMG_6.jpg", "other_app_bin", true),
        Triple("$root/DCIM/Camera/IMG_1.jpg", "camera", false),
        Triple("$root/Pictures/Screenshots/s.png", "screenshots", false),
        Triple("$root/Download/a.zip", "zip_extracted", false),
        Triple("$root/LOST.DIR/1", "lost_dir", false),
        Triple("$root/Download/Old/b.txt", "user_junk", false),
        Triple("$root/DCIM/Camera/.trashed-abc-IMG_5.jpg", "other_app_bin", false),
    )
    private val rules = candidates.map { it.second }.distinct()

    @Test
    fun `non-permanent kinds are never lost across 1500 random crashes`(): Unit = runBlocking {
        val random = Random(11)
        repeat(1_500) { iteration ->
            val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(root))
            val index = InMemoryTrashIndex()
            val clock = FakeClock(wall = now)
            val hashes = candidates.associate { (path, _, _) ->
                val content = ByteArray(50 + path.length) { (it * path.length).toByte() }
                fake.addFile(path, content, mtime = old)
                path to Sha256.of(content)
            }
            val mayBeLost = mutableSetOf<String>()

            fun manager() = TrashManager(fake, index, FakeSigner(), clock, ProtectedPaths(), listOf(root))
            var opsBeforeKill = random.nextInt(60)
            fake.beforeOp = { _, _ -> if (opsBeforeKill-- == 0) throw Killed() }
            try {
                val m = manager()
                val executor = ActionExecutor(fake, SafetyGuard(ProtectedPaths()), ProtectedPaths(), m, "s") { now }
                repeat(3) {
                    val picked = candidates.filter { random.nextBoolean() && fake.exists(it.first) }
                    if (picked.isEmpty()) return@repeat
                    val ops = picked.map { (path, rule, permanent) ->
                        // Sometimes the right rule, sometimes a wrong one; sometimes SAFE, sometimes REVIEW.
                        val label = if (random.nextInt(3) == 0) rules[random.nextInt(rules.size)] else rule
                        val safe = random.nextInt(4) != 0
                        if (permanent && label == rule && safe) mayBeLost += path
                        val s = fake.stat(KPath.Local(path))!!
                        PlannedOp(
                            s.path,
                            path,
                            OpKind.TRASH,
                            false,
                            s.size,
                            s.mtimeMillis,
                            s.identity,
                            ruleId = label,
                            classifiedSafe = safe,
                        )
                    }
                    executor.run(ActionPlan("p$it", ops, "t").confirm(1, "s"))
                }
            } catch (e: Killed) {
                // the process died here
            }
            fake.beforeOp = null
            manager().recover()

            val trashed = index.all().filter { it.state == TrashState.ACTIVE }.associateBy { it.originalPath }
            for ((path, hash) in hashes) {
                val atOriginal = fake.exists(path)
                val inTrash = trashed[path]?.let { fake.exists(it.trashedPath) } == true
                val copies = listOf(atOriginal, inTrash).count { it }
                if (path in mayBeLost && copies == 0) continue
                check(copies == 1) { "iteration $iteration: $path has $copies copies" }
                val where = if (atOriginal) path else trashed.getValue(path).trashedPath
                check(Sha256.of(fake.readAllBytes(where)) == hash) { "iteration $iteration: $path changed" }
            }
        }
    }
}
