// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.engine.trash.ClockMark
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M2, E-DEL-10: uptime adds up across reboots and is stored; the last purge observation survives restarts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SystemTrashClockTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private var elapsed = 0L
    private var boot = "1"

    private fun clock() = SystemTrashClock(context.contentResolver, context, { elapsed }, { boot })

    @Before
    fun clear() {
        context.getSharedPreferences(SystemTrashClock.PREFS, 0).edit().clear().commit()
    }

    @Test
    fun `uptime keeps counting across reboots and process restarts`() {
        elapsed = 5_000
        assertEquals(5_000, clock().uptimeMillis())
        elapsed = 9_000
        assertEquals(9_000, clock().uptimeMillis())
        boot = "2" // reboot: elapsed starts again near zero
        elapsed = 1_000
        assertEquals(10_000, clock().uptimeMillis())
        elapsed = 4_000
        assertEquals(13_000, clock().uptimeMillis())
    }

    @Test
    fun `the clock mark survives a new instance`() {
        clock().saveMark(ClockMark(10, 20, "1"))
        assertEquals(ClockMark(10, 20, "1"), clock().lastMark())
    }
}
