// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.os.Environment
import io.github.tomerar.keepclear.engine.move.AttachGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D4: only Android's own unmount → mount events of the same volume count as "the drive was re-attached". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VolumeEventsTest {
    @Test
    fun `an unplug and plug-in of the same volume counts once, anything else not at all`() {
        val events = VolumeEvents(epoch = "p")
        assertNull(events.generation(null))
        assertNull(events.generation(""))
        events.onState("1234-ABCD", Environment.MEDIA_MOUNTED) // already there: nothing proven
        assertEquals(AttachGeneration("p", 0), events.generation("1234-ABCD"))
        events.onState("1234-ABCD", Environment.MEDIA_EJECTING)
        events.onState("1234-ABCD", Environment.MEDIA_UNMOUNTED)
        events.onState("1234-ABCD", Environment.MEDIA_CHECKING)
        assertEquals(0L, events.generation("1234-ABCD")!!.count)
        events.onState("1234-ABCD", Environment.MEDIA_MOUNTED)
        assertEquals(1L, events.generation("1234-abcd")!!.count)
        // A read-only remount counts as back too; another volume's events never count for this one.
        events.onState("5678-EF01", Environment.MEDIA_REMOVED)
        events.onState("5678-EF01", Environment.MEDIA_MOUNTED_READ_ONLY)
        assertEquals(1L, events.generation("5678-EF01")!!.count)
        assertEquals(1L, events.generation("1234-ABCD")!!.count)
    }
}
