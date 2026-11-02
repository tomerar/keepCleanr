// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.media.MediaConsentHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E12: Android's trash dialog is launched once per request, never again after rotation, theme or language changes;
 * batches over 2,000 items are several requests, each with its own id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConsentOnceTest {
    @get:Rule
    val compose = createComposeRule()

    private fun op(id: Long) =
        PlannedOp(KPath.Media("external_primary", id), "/storage/emulated/0/DCIM/$id.jpg", OpKind.TRASH, false, 10, 1)

    private val ops = (1L..4_500L).map { op(it) }

    @Test
    fun `each dialog is launched once, also when the screen is recreated`() {
        // Stands in for the ViewModel: it outlives the activity.
        var consent: MediaConsent? by mutableStateOf(MediaConsent(MediaConsentHandler.batches(ops), Screen.Home))
        val launched = mutableListOf<Int>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MediaConsentEffect(
                consent = consent,
                build = { consent?.headId },
                onLaunched = { id -> consent = consent?.takeIf { it.headId == id }?.copy(launchedId = id) },
                launch = { request -> launched += request as Int },
            )
        }
        compose.waitForIdle()
        assertEquals(listOf(0), launched)
        // Rotation / dark theme / language: the activity is recreated while Android's dialog is open.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(listOf(0), launched)
        // The user answers; the next batch gets its own dialog, once.
        consent = consent!!.answer(approved = true)
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(listOf(0, 1), launched)
        consent = consent!!.answer(approved = true)
        compose.waitForIdle()
        assertEquals(listOf(0, 1, 2), launched)
    }

    @Test
    fun `batches over 2,000 are tracked by id`() {
        val c = MediaConsent(MediaConsentHandler.batches(ops), Screen.Home)
        assertEquals(3, c.requests.size)
        assertTrue(c.needsLaunch)
        val shown = c.copy(launchedId = c.headId)
        assertFalse(shown.needsLaunch)
        val next = shown.answer(approved = true)
        assertEquals(1, next.headId)
        assertTrue(next.needsLaunch)
        assertEquals(2_000, next.approved.size)
    }
}
