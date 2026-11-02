// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.engine.move.PauseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The MOVE service's job bookkeeping without Android (MOVE-12), so its rules are unit-tested: one job at a time, a
 * start that arrives while a job winds down runs next, and Android 15's `dataSync` time limit (E2).
 *
 * On the time limit Android gives the service a few seconds before it ends the app, and a blocked network read may
 * never notice a cancellation. So [onTimeout] does not wait for the job: it cancels it, writes the job's checkpoint
 * itself (paused, "time limit"), drops any start that was waiting, and stops the service right away.
 */
class MoveRunner(
    /** Where the jobs run (the service's scope). */
    private val scope: CoroutineScope,
    /** Where the timeout checkpoint is written; outlives the service (the app's scope). */
    private val checkpointScope: CoroutineScope,
    /** Runs one job to its end; [reason] says why, if it is cancelled from outside. */
    private val runJob: suspend (jobId: String, reason: () -> PauseReason) -> Unit,
    /** Writes "paused for [PauseReason]" for the job (only if it is still marked running). */
    private val checkpoint: suspend (jobId: String, reason: PauseReason) -> Unit,
    /** stopForeground + stopSelf. */
    private val stopService: () -> Unit,
    private val onStarted: (jobId: String) -> Unit = {},
    private val onEnded: (jobId: String) -> Unit = {},
) {
    @Volatile private var stopReason: PauseReason = PauseReason.PAUSED_BY_USER

    @Volatile private var work: Job? = null

    @Volatile private var current: String? = null

    /** Guards the hand-over from an ending job to the next start (E2: a start is never lost or stopped under). */
    private val lock = Any()

    /** A job runs or is still winding down (a cancelled job counts until its checkpoint and clean-up are done). */
    @Volatile var busy = false
        private set

    /** A start that arrived while the previous job was still ending; it runs next (never after a timeout). */
    @Volatile var pending: String? = null
        private set

    /** Android ended the time this service may run; nothing more starts in this service instance. */
    @Volatile var timedOut = false
        private set

    /** Returns false when the job can't start here (the time limit was reached): the caller stops the service. */
    fun start(jobId: String): Boolean = synchronized(lock) {
        if (timedOut) return false
        // A job paused a moment ago is cancelled but still ending; this one waits for it, so the ending one can't
        // stop the service under it (E2).
        if (busy) {
            pending = jobId
            return true
        }
        launch(jobId)
        return true
    }

    /** Pauses (or stops) the running job; the engine checkpoints and the job ends. */
    fun pause(reason: PauseReason) {
        stopReason = reason
        work?.cancel()
    }

    /** Android 15+: the `dataSync` time limit ended (E2). */
    fun onTimeout() {
        synchronized(lock) {
            timedOut = true
            pending = null
        }
        stopReason = PauseReason.SYSTEM_TIME_LIMIT
        val id = current
        work?.cancel()
        if (id != null) {
            checkpointScope.launch {
                runCatching { checkpoint(id, PauseReason.SYSTEM_TIME_LIMIT) }
            }
        }
        stopService()
    }

    private fun launch(jobId: String) {
        busy = true
        current = jobId
        stopReason = PauseReason.PAUSED_BY_USER
        onStarted(jobId)
        val job = scope.launch { runJob(jobId) { stopReason } }
        work = job
        // On completion, not in a finally: a job paused before it even started never runs its body, and the
        // hand-over must happen anyway.
        job.invokeOnCompletion { ended(jobId) }
    }

    private fun ended(jobId: String) {
        current = null
        onEnded(jobId)
        synchronized(lock) {
            val next = if (timedOut) null else pending
            pending = null
            if (next != null) {
                launch(next)
            } else {
                busy = false
                if (!timedOut) stopService() // after the time limit it has already stopped
            }
        }
    }
}
