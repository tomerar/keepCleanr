// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E7 notifications at most once a second; E13 the time-left estimate starts again after a pause. */
class ProgressRulesTest {
    @Test
    fun `the notification updates at most once a second however fast files go`() {
        var now = 0L
        val throttle = NotificationThrottle(clock = { now })
        var shown = 0
        // 1,250 small files in 2.5 seconds.
        repeat(1_250) {
            if (throttle.ready()) shown++
            now += 2
        }
        assertEquals(3, shown) // at 0, 1000 and 2000 ms
        assertTrue(throttle.ready().not())
        now += 1_000
        assertTrue(throttle.ready())
    }

    @Test
    fun `time left starts again after a pause, so the paused hour isn't counted`() {
        val eta = EtaTracker()
        val gb = 1_000_000_000L
        eta.onRunning("job")
        assertNull(eta.estimate("job", 0, 0, 10 * gb))
        // 1 GB in 20 s: 9 GB left = 180 s.
        assertEquals(180_000L, eta.estimate("job", 20_000, gb, 10 * gb))
        // Paused for an hour, then continued.
        eta.onRunning(null)
        eta.onRunning("job")
        assertNull(eta.estimate("job", 3_620_000, gb, 10 * gb))
        // Again 1 GB in 20 s: 8 GB left = 160 s (not the hour-long average).
        assertEquals(160_000L, eta.estimate("job", 3_640_000, 2 * gb, 10 * gb))
        assertFalse(eta.estimate("job", 3_640_000, 2 * gb, 10 * gb)!! > 200_000)
    }
}
