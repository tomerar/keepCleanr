// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.ActionHandler
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.RetainedMember
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.safety.SkipReason
import io.github.tomerar.keepclear.core.safety.VisualRetention
import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * G4b (GAL-8, AC-G6, C229): the no-loss and keep-one invariant for similar-photos groups under random plans, stale
 * screens, other apps' edits and kill points at any storage operation, with the real [TrashManager].
 *
 * Setting: six photos in four **overlapping** groups. Each plan is what the app builds from one group's screen: a
 * random non-empty choice of that group's members (sometimes all of them), each op carrying the evidence of every group
 * its photo belongs to, the kept members taken either from now or from the first scan (a stale screen, a second
 * session). Between plans another app may edit a photo (new time, same path). The process is killed at a random storage
 * operation, then restarted (new manager + recover).
 *
 * Invariant, precisely:
 * 1. **Keep-one at every act.** Whenever the trash is asked to move a photo, every group that photo belongs to still
 *    has another member at its original path that the executor just found unchanged against the plan's evidence.
 * 2. **Keep-one after any interruption.** After the restart, every group has at least one member at its original
 *    path (other apps here only edit, never delete, so no group may be emptied by KeepClear).
 * 3. **No loss.** Every photo exists exactly once, at its original path or in KeepClear's trash, with the bytes it
 *    had last (its own or another app's edit), and nothing was deleted for good.
 * 4. A plan that chooses every member of a group, or acts on a member another chosen photo's group keeps, is refused
 *    before anything runs.
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class VisualGroupKillPointPropertyTest {
    private class Killed : RuntimeException()

    private val root = "/storage/emulated/0"
    private val photos = (0 until 6).map { "$root/Pictures/Saved/p$it.jpg" }
    private val groups = listOf(setOf(0, 1, 2), setOf(2, 3), setOf(3, 4, 5), setOf(1, 5))
        .map { g -> g.map { photos[it] }.toSet() }

    @Test
    fun `no similar group is ever emptied and no photo is lost across 1500 random runs with crashes`(): Unit =
        runBlocking {
            var acted = 0
            var refusedLive = 0
            var rejectedPlans = 0
            checkAll(PropTestConfig(iterations = 1_500, seed = 23), Arb.long()) { seed ->
                val random = Random(seed)
                val fake = FakeStorageGateway(caseInsensitive = true)
                val index = InMemoryTrashIndex()
                val clock = FakeClock()
                val content = mutableMapOf<String, String>()
                for (p in photos) {
                    val bytes = ByteArray(200 + p.length) { (it * p.length).toByte() }
                    fake.addFile(p, bytes, mtime = 1)
                    content[p] = Sha256.of(bytes)
                }
                val firstScan = photos.associateWith { fake.stat(KPath.Local(it))!! }

                fun manager() = TrashManager(fake, index, FakeSigner(), clock, ProtectedPaths(), listOf(root))
                var opsBeforeKill = random.nextInt(80)
                fake.beforeOp = { _, _ -> if (opsBeforeKill-- == 0) throw Killed() }
                try {
                    val trash = manager()
                    val checking = object : ActionHandler by trash {
                        override suspend fun perform(op: PlannedOp, gateway: StorageGateway) {
                            // Invariant 1, checked with no kill point (a read-only look).
                            val saved = fake.beforeOp
                            fake.beforeOp = null
                            for (g in groups.filter { op.displayPath in it }) {
                                check(g.any { it != op.displayPath && fake.exists(it) }) {
                                    "seed $seed: ${op.displayPath} handed over as the last of $g"
                                }
                            }
                            check(op.kind == OpKind.TRASH && op.permanentReason == null)
                            fake.beforeOp = saved
                            acted++
                            trash.perform(op, gateway)
                        }
                    }
                    val executor = ActionExecutor(fake, SafetyGuard(ProtectedPaths()), ProtectedPaths(), checking, "s")
                    repeat(3) { n ->
                        if (random.nextInt(3) == 0) {
                            // Another app edits a photo that is still in place (E-G07).
                            val p = photos[random.nextInt(photos.size)]
                            val saved = fake.beforeOp
                            fake.beforeOp = null
                            if (fake.exists(p)) {
                                val bytes = ByteArray(300) { (it + n + 7).toByte() }
                                fake.modify(p, FakeStorageGateway.Content.Bytes(bytes), mtime = 1_000L + n)
                                content[p] = Sha256.of(bytes)
                            }
                            fake.beforeOp = saved
                        }
                        val plan = planFor(random, fake, firstScan, n)
                        when (val report = executor.run(plan)) {
                            is ExecutionReport.Rejected -> {
                                rejectedPlans++
                            }

                            is ExecutionReport.Ran -> {
                                refusedLive += report.skipped.count { it.reason == SkipReason.RETAINED_MEMBER_GONE }
                                check(report.deletedForGood.isEmpty())
                            }
                        }
                    }
                } catch (e: Killed) {
                    // the process died here
                }
                fake.beforeOp = null
                manager().recover()

                // Invariant 2.
                for (g in groups) check(g.any { fake.exists(it) }) { "seed $seed: group $g emptied" }
                // Invariant 3.
                val trashed = index.all().filter { it.state == TrashState.ACTIVE }.associateBy { it.originalPath }
                for (p in photos) {
                    val atOriginal = fake.exists(p)
                    val inTrash = trashed[p]?.let { fake.exists(it.trashedPath) } == true
                    check(listOf(atOriginal, inTrash).count { it } == 1) { "seed $seed: $p lost or doubled" }
                    val where = if (atOriginal) p else trashed.getValue(p).trashedPath
                    check(Sha256.of(fake.readAllBytes(where)) == content[p]) { "seed $seed: $p changed" }
                }
            }
            println("visual kill points: acted=$acted refusedLive=$refusedLive rejected=$rejectedPlans")
            // The property exercised every path: acts, live refusals and structural refusals.
            check(acted > 100 && refusedLive > 20 && rejectedPlans > 100) {
                "acted=$acted refusedLive=$refusedLive rejected=$rejectedPlans"
            }
        }

    /**
     * One plan as the app builds it from one group's screen. The kept members' facts come from now or from the first
     * scan (stale); a photo that is gone is described as the first scan saw it (the screen still shows it).
     */
    private suspend fun planFor(
        random: Random,
        fake: FakeStorageGateway,
        firstScan: Map<String, Stat>,
        n: Int,
    ): ActionPlan {
        val saved = fake.beforeOp
        fake.beforeOp = null
        val stale = random.nextBoolean()
        val seen = photos.associateWith { p ->
            if (stale) {
                firstScan.getValue(p)
            } else {
                fake.stat(KPath.Local(p))
                    ?: firstScan.getValue(p)
            }
        }
        val group = groups[random.nextInt(groups.size)].toList()
        val chosen = group.filter { random.nextBoolean() }.ifEmpty { listOf(group[random.nextInt(group.size)]) }
            .let { if (random.nextInt(6) == 0) group else it } // sometimes every member
        val ops = chosen.map { p ->
            val evidence = groups.withIndex().filter { p in it.value }.map { (i, g) ->
                VisualRetention(
                    "g$i",
                    "v$i",
                    (g - chosen.toSet()).sorted().map { m ->
                        val s = seen.getValue(m)
                        RetainedMember(KPath.Local(m), m, s.size, s.mtimeMillis, s.identity)
                    },
                )
            }
            val s = seen.getValue(p)
            PlannedOp(
                KPath.Local(p),
                p,
                OpKind.TRASH,
                false,
                s.size,
                s.mtimeMillis,
                s.identity,
                ruleId = "ai_images",
                visualGroups = evidence,
            )
        }
        fake.beforeOp = saved
        return ActionPlan("p$n", ops, "gallery").confirm(1, "s")
    }
}
