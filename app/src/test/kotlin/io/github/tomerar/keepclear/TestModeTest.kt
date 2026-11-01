// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SAFETY-9: test builds start in test mode; turning it off takes Settings › Advanced and two confirmations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TestModeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `a fresh debug install starts in test mode`() {
        assertTrue("unit tests run the fossDebug variant", BuildConfig.DEBUG)
        context.getSharedPreferences(TestMode.PREFS, 0).edit().clear().commit()
        assertTrue(TestMode(context).isOn)
    }

    @Test
    fun `release builds start with test mode off, and the choice is remembered`() {
        context.getSharedPreferences(TestMode.PREFS, 0).edit().clear().commit()
        assertFalse(TestMode(context, defaultOn = false).isOn)
        TestMode(context).turnOff()
        assertFalse(TestMode(context).isOn)
        TestMode(context).turnOn()
        assertTrue(TestMode(context).isOn)
    }

    @Test
    fun `turning test mode off needs two confirmations`() {
        var off = 0
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(testModeOn = true, onTestModeOn = {}, onTestModeOff = { off++ }, onBack = {})
            }
        }
        compose.onNodeWithTag("testModeSwitch").performClick()
        assertEquals(0, off)
        compose.onNodeWithTag("testModeOff1").performClick()
        assertEquals(0, off)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("testModeOff2").fetchSemanticsNodes()
                .any { SemanticsProperties.Disabled !in it.config }
        }
        compose.onNodeWithTag("testModeOff2").performClick()
        assertEquals(1, off)
    }

    @Test
    fun `the banner is on every screen while test mode is on`() {
        compose.setContent {
            KeepClearTheme {
                AppScreens(
                    state = HomeUiState(screens = listOf(Screen.Home, Screen.Settings)),
                    onOpen = {},
                    onBack = {},
                    home = {},
                    testMode = TestModeUi(on = true),
                )
            }
        }
        compose.onNodeWithTag("testModeBanner").assertExists()
        compose.onNodeWithTag("settings").assertExists()
    }

    @Test
    fun `no banner when test mode is off`() {
        compose.setContent {
            KeepClearTheme {
                AppScreens(state = HomeUiState(), onOpen = {
                }, onBack = {}, home = {}, testMode = TestModeUi(on = false))
            }
        }
        compose.onNodeWithTag("testModeBanner").assertDoesNotExist()
    }

    @Test
    fun `a simulated clean says what would have been freed and offers no Undo`() {
        compose.setContent {
            KeepClearTheme {
                SafeScreen(
                    summary = io.github.tomerar.keepclear.engine.classify.ClassificationSummary.of(emptyList()),
                    result = CleanResult(
                        io.github.tomerar.keepclear.engine.classify.Total(3, 3_000_000),
                        0,
                        0,
                        emptyList(),
                        simulated = true,
                    ),
                    trimResult = TestMode.TRIM_SKIPPED,
                    showExternalCacheClear = false,
                    onClean = {},
                    onUndo = {},
                    onTrim = {},
                    onClearExternalCaches = {},
                    onOpenGroup = {},
                    onOpenTrash = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("simulatedResult").assertExists()
        compose.onNodeWithTag("undo").assertDoesNotExist()
    }
}
