// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
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

    @Test
    fun `shows tagline and no debug tools in release mode`() {
        compose.setContent { KeepClearTheme { HomeScreen(showDebugTools = false) } }
        compose.onNodeWithTag("tagline").assertIsDisplayed()
        compose.onNodeWithText("Flip layout direction (debug)").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "iw")
    fun `renders Hebrew`() {
        compose.setContent { KeepClearTheme { HomeScreen(showDebugTools = false) } }
        compose.onNodeWithText("שומרים את מה שחשוב. מפנים את השאר.").assertIsDisplayed()
    }
}
