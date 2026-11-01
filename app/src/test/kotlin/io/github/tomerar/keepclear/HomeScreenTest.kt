// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.scan.SpaceMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HomeUiState, actions: HomeActions = HomeActions()) =
        compose.setContent { KeepClearTheme { HomeScreen(state = state, showDebugTools = false, actions = actions) } }

    @Test
    fun `shows tagline and no debug tools in release mode`() {
        show(HomeUiState())
        compose.onNodeWithTag("tagline").assertIsDisplayed()
        compose.onNodeWithText("Flip layout direction (debug)").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "iw")
    fun `renders Hebrew`() {
        show(HomeUiState())
        compose.onNodeWithText("שומרים את מה שחשוב. מפנים את השאר.").assertIsDisplayed()
    }

    @Test
    fun `permission is requested only after the explainer, never on its own (ONB-2)`() {
        var requests = 0
        show(HomeUiState(), HomeActions(onRequestMedia = { requests++ }))
        compose.waitForIdle()
        assertEquals(0, requests)
        compose.onNodeWithTag("allowAccess").performClick()
        compose.onNodeWithTag("explainer").assertIsDisplayed()
        assertEquals(0, requests)
        compose.onNodeWithTag("explainerContinue").performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `partial media access is labelled (ONB-4)`() {
        show(HomeUiState(mediaAccess = MediaAccess.PARTIAL, scan = ScanState.Done(40, 1_000_000, true)))
        compose.onNodeWithText(
            "KeepClear sees only the photos you picked. Allow all to get real numbers.",
        ).assertIsDisplayed()
    }

    @Test
    fun `incomplete scan says so (E-PERM-1)`() {
        show(HomeUiState(mediaAccess = MediaAccess.FULL, scan = ScanState.Done(10, 10, complete = false)))
        compose.onNodeWithText(
            "Scan stopped because access was removed. These numbers are incomplete.",
        ).assertIsDisplayed()
    }

    @Test
    fun `all-files card only where the flavor allows it`() {
        show(HomeUiState(mediaAccess = MediaAccess.FULL, allFiles = false, scan = ScanState.Done(1, 1, true)))
        compose.onNodeWithTag("allowAllFiles").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `play flavor never offers all-files`() {
        show(HomeUiState(mediaAccess = MediaAccess.FULL, allFiles = null, scan = ScanState.Done(1, 1, true)))
        compose.onNodeWithTag("allowAllFiles").assertDoesNotExist()
    }

    @Test
    fun `minimal space banner (LOW-2)`() {
        show(HomeUiState(spaceMode = SpaceMode.MINIMAL))
        compose.onNodeWithText(
            "Less than 100 MB left. To avoid filling the phone, KeepClear isn't saving any data until there's room. " +
                "Start by clearing apps' temporary files.",
        ).assertIsDisplayed()
    }
}
