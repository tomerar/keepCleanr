// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

/** The phone's battery: [percent] 0–100 and whether it is charging now. */
data class BatteryLevel(val percent: Int, val charging: Boolean)

/**
 * Owner decision P3 (2026-11-03): the USB-C drive often sits in the phone's only port, so a backup may start on
 * battery. It starts at [START_MIN] percent or more (with a calm warning below [WARN_BELOW]), pauses by itself with a
 * checkpoint at [PAUSE_AT] percent while not charging, and goes on once the phone charges or is back at [RESUME_AT] or
 * more. Charging always allows. An unknown level (null) never blocks or pauses: Android didn't say.
 */
object BatteryPolicy {
    const val START_MIN = 10
    const val WARN_BELOW = 20
    const val PAUSE_AT = 10

    /** Above [PAUSE_AT] with a margin, so a level that wobbles around 10 % doesn't start and stop over and over. */
    const val RESUME_AT = 15

    enum class Start { OK, WARN, REFUSE }

    fun atStart(level: BatteryLevel?): Start = when {
        level == null || level.charging -> Start.OK
        level.percent < START_MIN -> Start.REFUSE
        level.percent < WARN_BELOW -> Start.WARN
        else -> Start.OK
    }

    fun mustPause(level: BatteryLevel?): Boolean = level != null && !level.charging && level.percent <= PAUSE_AT

    fun canResume(level: BatteryLevel?): Boolean = level == null || level.charging || level.percent >= RESUME_AT

    /**
     * Runs a job ([run], e.g. `MoveEngine.run`) and, each time it ends paused for [PauseReason.BATTERY_LOW], waits
     * ([wait], e.g. a delay; cancelling it stops waiting) until [canResume], then runs it again. The engine's
     * checkpoints mean a run after a battery pause starts at the first file not yet verified. Returns the job as it
     * ended for any other reason.
     */
    suspend fun runWithPauses(
        battery: () -> BatteryLevel?,
        wait: suspend () -> Unit,
        onWaiting: suspend (MoveJob) -> Unit = {},
        run: suspend () -> MoveJob,
    ): MoveJob {
        while (true) {
            val end = run()
            if (end.state != JobState.PAUSED || end.pauseReason != PauseReason.BATTERY_LOW) return end
            onWaiting(end)
            while (!canResume(battery())) wait()
        }
    }
}
