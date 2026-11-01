// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.media.MediaConsentHandler
import io.github.tomerar.keepclear.engine.classify.Total
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M12 (PR #20 review): only the items of dialogs the user approved count as moved to Android's trash. */
class MediaConsentTest {
    private fun op(id: Long) =
        PlannedOp(KPath.Media("external_primary", id), "/storage/emulated/0/DCIM/$id.jpg", OpKind.TRASH, false, 10, 1)

    private val ops = (1L..2_500L).map { op(it) }

    private fun start() = MediaConsent(MediaConsentHandler.batches(ops), Screen.Home)

    @Test
    fun `approve the first dialog, decline the second - only the first batch is counted`() {
        val c = start().answer(approved = true).answer(approved = false)
        assertTrue(c.requests.isEmpty())
        val r = c.result()
        assertEquals(Total(2_000, 20_000), r.done)
        assertEquals(500, r.skipped)
        assertTrue(r.androidTrash)
        assertFalse(r.declined)
    }

    @Test
    fun `declining every dialog changes nothing`() {
        val r = start().answer(false).answer(false).result()
        assertEquals(Total(), r.done)
        assertTrue(r.declined)
        assertFalse(r.androidTrash)
    }

    @Test
    fun `a dialog Android could not show is reported as failed, not declined`() {
        val r = start().answer(true).answer(false, couldNotShow = true).result()
        assertEquals(2_000, r.done.files)
        assertEquals(500, r.failed)
        assertFalse(r.declined)
    }
}
