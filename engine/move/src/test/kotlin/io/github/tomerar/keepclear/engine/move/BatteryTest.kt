// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Owner decision P3 (2026-11-03): a backup may start at 10 % or more on battery (a calm warning below 20 %), refuses
 * below 10 % unless charging, pauses itself with a checkpoint at 10 % while not charging, and goes on once the phone
 * charges or is back at 15 %, without copying a verified file again. Fake battery source throughout.
 */
class BatteryTest {
    /** Battery percent → not charging: start verdict (table, positive and negative). */
    private val startTable = listOf(
        0 to BatteryPolicy.Start.REFUSE,
        9 to BatteryPolicy.Start.REFUSE,
        10 to BatteryPolicy.Start.WARN,
        15 to BatteryPolicy.Start.WARN,
        19 to BatteryPolicy.Start.WARN,
        20 to BatteryPolicy.Start.OK,
        100 to BatteryPolicy.Start.OK,
    )

    @Test
    fun `start, pause and resume thresholds`() {
        for ((percent, verdict) in startTable) {
            BatteryPolicy.atStart(BatteryLevel(percent, charging = false)) shouldBe verdict
            BatteryPolicy.atStart(BatteryLevel(percent, charging = true)) shouldBe BatteryPolicy.Start.OK
        }
        BatteryPolicy.atStart(null) shouldBe BatteryPolicy.Start.OK
        BatteryPolicy.mustPause(BatteryLevel(10, false)) shouldBe true
        BatteryPolicy.mustPause(BatteryLevel(3, false)) shouldBe true
        BatteryPolicy.mustPause(BatteryLevel(11, false)) shouldBe false
        BatteryPolicy.mustPause(BatteryLevel(5, true)) shouldBe false
        BatteryPolicy.mustPause(null) shouldBe false
        BatteryPolicy.canResume(BatteryLevel(14, false)) shouldBe false
        BatteryPolicy.canResume(BatteryLevel(15, false)) shouldBe true
        BatteryPolicy.canResume(BatteryLevel(5, true)) shouldBe true
    }

    @Test
    fun `preflight refuses below 10 percent and warns calmly below 20`(): Unit = runBlocking {
        val rig = Rig(files = 1)

        suspend fun issues(percent: Int, charging: Boolean) =
            MovePlanner.preflight(emptyList(), rig.target, emptySet(), percent, charging)
        issues(9, false) shouldContain MovePlanner.Issue.BatteryLow
        issues(9, true) shouldNotContain MovePlanner.Issue.BatteryLow
        issues(10, false) shouldNotContain MovePlanner.Issue.BatteryLow
        issues(10, false) shouldContain MovePlanner.Issue.BatteryWarning
        issues(19, false) shouldContain MovePlanner.Issue.BatteryWarning
        issues(20, false) shouldNotContain MovePlanner.Issue.BatteryWarning
        issues(12, true) shouldNotContain MovePlanner.Issue.BatteryWarning
        MovePlanner.Issue.BatteryWarning.blocking shouldBe false
        MovePlanner.Issue.BatteryLow.blocking shouldBe true
    }

    @Test
    fun `the job pauses at 10 percent and resumes when charging without copying verified files again`(): Unit =
        runBlocking {
            val rig = Rig(files = 5)
            var level = BatteryLevel(12, charging = false)
            val id = rig.newJob()
            var verifiedSeen = 0
            val copying = mutableListOf<Int>()

            fun engine() = MoveEngine(
                rig.fake,
                rig.target,
                rig.store,
                clock = { rig.now },
                battery = { level },
                onProgress = { f ->
                    if (f.state == MoveFileState.COPYING) copying += f.index
                    if (f.state == MoveFileState.VERIFIED && ++verifiedSeen == 2) level = BatteryLevel(10, false)
                },
            )
            val paused = engine().run(id)
            paused.state shouldBe JobState.PAUSED
            paused.pauseReason shouldBe PauseReason.BATTERY_LOW
            val verifiedBefore = rig.store.files.values.filter { it.state == MoveFileState.VERIFIED }.map { it.index }
            verifiedBefore.size shouldBe 2
            copying shouldBe verifiedBefore
            copying.clear()

            // Waits while the battery stays low and not charging; the charger goes in after three polls.
            var polls = 0
            val end = BatteryPolicy.runWithPauses(
                battery = { level },
                wait = { if (++polls == 3) level = BatteryLevel(10, charging = true) },
                run = { engine().run(id) },
            )
            end.state shouldBe JobState.DONE
            polls shouldBe 3
            rig.store.files.values.all { it.state == MoveFileState.VERIFIED } shouldBe true
            // Only the three files not yet verified were copied after the pause; the two verified ones were not redone.
            copying.sorted() shouldBe (0 until 5).filter { it !in verifiedBefore }
        }

    @Test
    fun `a pause for another reason is returned at once, without waiting for the battery`(): Unit = runBlocking {
        val rig = Rig(files = 2)
        val id = rig.newJob()
        rig.fake.disconnect(USB)
        var polls = 0
        val end = BatteryPolicy.runWithPauses(
            battery = { BatteryLevel(5, false) },
            wait = { polls++ },
            run = { rig.engine().run(id) },
        )
        end.pauseReason shouldBe PauseReason.TARGET_DISCONNECTED
        polls shouldBe 0
    }

    @Test
    fun `an unknown battery level never pauses a job`(): Unit = runBlocking {
        val rig = Rig(files = 2)
        val id = rig.newJob()
        MoveEngine(rig.fake, rig.target, rig.store, clock = { rig.now }, battery = { null }).run(id).state shouldBe
            JobState.DONE
    }
}
