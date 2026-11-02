// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** E13: the Nextcloud sign-in page opens by itself once; recreating the screen doesn't open the browser again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NextcloudLoginOnceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the browser opens once, not again after rotation, and again only on request`() {
        // Stands in for NextcloudViewModel, which outlives the activity.
        var state by mutableStateOf(
            NextcloudSetupState(NextcloudStep.Browser("https://cloud.example.com/login/v2/flow/x")),
        )
        val opened = mutableListOf<String>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            KeepClearTheme {
                NextcloudScreen(
                    NextcloudUi(
                        state = state,
                        onOpenLogin = { opened += it },
                        onLoginOpened = {
                            val step = state.step as NextcloudStep.Browser
                            state = state.copy(step = step.copy(opened = true))
                        },
                        testAccountNotice = false,
                    ),
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
        assertEquals(1, opened.size)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(1, opened.size)
        compose.onNodeWithTag("nextcloudOpenAgain").performClick()
        assertEquals(2, opened.size)
    }
}
