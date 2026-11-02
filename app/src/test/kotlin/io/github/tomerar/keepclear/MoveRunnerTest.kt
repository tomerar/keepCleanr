// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.engine.move.PauseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** E2, MOVE-12: the MOVE service's rules, including Android 15's dataSync time limit (MoveService.onTimeout). */
class MoveRunnerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stops = java.util.concurrent.atomic.AtomicInteger()
    private val started = Collections.synchronizedList(mutableListOf<String>())
    private val checkpoints = Collections.synchronizedList(mutableListOf<Pair<String, PauseReason>>())
    private val reasons = Collections.synchronizedList(mutableListOf<PauseReason>())

    @After
    fun close() = scope.cancel()

    private fun runner(job: suspend (String, () -> PauseReason) -> Unit) = MoveRunner(
        scope = scope,
        checkpointScope = scope,
        runJob = { id, reason ->
            started += id
            try {
                job(id, reason)
            } finally {
                reasons += reason()
            }
        },
        checkpoint = { id, reason -> checkpoints += id to reason },
        stopService = { stops.incrementAndGet() },
    )

    private fun eventually(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun `the time limit stops the service at once, even while a read is stuck`() {
        // A network read that never notices a cancellation (blocked in the socket).
        val stuck = CountDownLatch(1)
        val r = runner { _, _ -> withContext(NonCancellable) { stuck.await(30, TimeUnit.SECONDS) } }
        assertTrue(r.start("a"))
        eventually("job a runs") { started.toList() == listOf("a") }
        r.start("b") // Continue tapped meanwhile: waits for a
        assertEquals("b", r.pending)

        r.onTimeout()

        // Stopped right away, without waiting for the job.
        assertEquals(1, stops.get())
        // The waiting start is dropped and nothing new may start in this service.
        assertNull(r.pending)
        assertFalse(r.start("c"))
        // The checkpoint is written by the runner itself: paused for the time limit.
        eventually("checkpoint") { checkpoints.toList() == listOf("a" to PauseReason.SYSTEM_TIME_LIMIT) }

        stuck.countDown()
        eventually("job a ends") { !r.busy }
        Thread.sleep(50)
        assertEquals(listOf("a"), started.toList()) // b never ran
        assertEquals(1, stops.get()) // and the service isn't stopped twice
        assertEquals(listOf(PauseReason.SYSTEM_TIME_LIMIT), reasons.toList())
    }

    @Test
    fun `a job that notices the cancellation ends paused for the time limit`() {
        val r = runner { _, _ -> awaitCancellation() }
        r.start("a")
        eventually("job a runs") { started.toList() == listOf("a") }
        r.onTimeout()
        eventually("job a ends") { !r.busy }
        eventually("reason") { reasons.isNotEmpty() }
        assertEquals(listOf(PauseReason.SYSTEM_TIME_LIMIT), reasons.toList())
        assertEquals(1, stops.get())
    }

    @Test
    fun `a job paused before it started still hands over to the next start`() {
        val r = runner { _, _ -> awaitCancellation() }
        r.start("a")
        r.pause(PauseReason.PAUSED_BY_USER) // at once, maybe before job a's code ran
        r.start("b")
        eventually("job b runs") { "b" in started.toList() }
        r.pause(PauseReason.CANCELLED)
        // Stopped once b ended (once more if a had fully ended before b was started).
        eventually("stopped") { stops.get() >= 1 && !r.busy && reasons.size == started.size }
        assertNull(r.pending)
    }

    @Test
    fun `a start while a job winds down runs right after it, then the service stops`() {
        // Job a's checkpoint after the pause takes a moment; b is tapped during it. Without the latch, a could end
        // before b's start, and then the service would (rightly) stop once in between.
        val windingDown = CountDownLatch(1)
        val r = runner { id, _ ->
            try {
                awaitCancellation()
            } finally {
                if (id == "a") withContext(NonCancellable) { windingDown.await(10, TimeUnit.SECONDS) }
            }
        }
        r.start("a")
        eventually("job a runs") { started.toList() == listOf("a") }
        r.pause(PauseReason.PAUSED_BY_USER)
        r.start("b")
        assertEquals("b", r.pending) // a is still ending: b waits for it
        windingDown.countDown()
        eventually("job b runs") { started.toList() == listOf("a", "b") }
        assertEquals(0, stops.get())
        r.pause(PauseReason.CANCELLED)
        eventually("stopped") { stops.get() == 1 && reasons.size == 2 }
        assertEquals(listOf(PauseReason.PAUSED_BY_USER, PauseReason.CANCELLED), reasons.toList())
    }
}
