// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M12b, ONB-6 (play): WhatsApp's folder is explained first, picked in Android's picker, wrong folder → retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhatsAppFolderTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HomeUiState, onPick: () -> Unit = {}, onDismiss: () -> Unit = {}) = compose.setContent {
        KeepClearTheme {
            HomeScreen(
                state,
                showDebugTools = false,
                actions = HomeActions(onPickWhatsApp = onPick, onDismissWrongFolder = onDismiss),
            )
        }
    }

    private val play = HomeUiState(mediaAccess = MediaAccess.FULL, allFiles = null)

    @Test
    fun `the picker opens only after the explainer`() {
        var picked = 0
        show(play, onPick = { picked++ })
        compose.onNodeWithTag("pickWhatsApp").performScrollTo().performClick()
        assertEquals(0, picked)
        compose.onNodeWithTag("explainerContinue").performScrollTo().performClick()
        assertEquals(1, picked)
    }

    @Test
    fun `no card once the folder is granted`() {
        show(play.copy(grantedFolders = listOf("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp")))
        compose.onNodeWithTag("pickWhatsApp").assertDoesNotExist()
    }

    @Test
    fun `no card in the foss build, where All files access covers it`() {
        show(play.copy(allFiles = true))
        compose.onNodeWithTag("pickWhatsApp").assertDoesNotExist()
    }

    @Test
    fun `a wrong folder is said plainly and offers to try again`() {
        var picked = 0
        var dismissed = 0
        show(play.copy(wrongFolder = true), onPick = { picked++ }, onDismiss = { dismissed++ })
        compose.onNodeWithTag("bannerAction").performScrollTo().performClick()
        assertEquals(1, picked)
        assertEquals(1, dismissed)
    }
}
