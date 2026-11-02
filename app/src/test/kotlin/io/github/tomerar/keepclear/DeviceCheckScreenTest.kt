// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.devcheck.DeviceCheckGate
import io.github.tomerar.keepclear.devcheck.DeviceCheckScreen
import io.github.tomerar.keepclear.devcheck.DeviceCheckState
import io.github.tomerar.keepclear.devcheck.DeviceCheckUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Advanced › Device check: test builds only, read-only report with Copy and Share. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceCheckScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val report =
        "KeepClear device check\n== Scan (D3-1) ==\n[PASS] Scan time: 21.0 s\n\nNo file paths are included.\n"

    @Test
    fun `only test builds offer the device check`() {
        assertTrue("unit tests run the debug variant", BuildConfig.DEBUG)
        assertEquals(BuildConfig.DEBUG, DeviceCheckGate.visible())
        assertFalse("a release build never shows it", DeviceCheckGate.visible(debug = false))
    }

    @Test
    fun `settings advanced shows the button in test builds and opens the screen`() {
        var opened = 0
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    testModeOn = true,
                    onTestModeOn = {},
                    onTestModeOff = {},
                    onBack = {},
                    actions = SettingsActions(showDeviceCheck = DeviceCheckGate.visible(), onOpenDeviceCheck = {
                        opened++
                    }),
                )
            }
        }
        compose.onNodeWithTag("openDeviceCheck").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `release settings have no device check button`() {
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    testModeOn = false,
                    onTestModeOn = {},
                    onTestModeOff = {},
                    onBack = {},
                    actions = SettingsActions(showDeviceCheck = DeviceCheckGate.visible(debug = false)),
                )
            }
        }
        compose.onNodeWithTag("settings").assertExists()
        compose.onNodeWithTag("openDeviceCheck").assertDoesNotExist()
    }

    @Test
    fun `a release build never shows the screen, even when asked to open it`() {
        compose.setContent {
            KeepClearTheme {
                AppScreens(
                    state = HomeUiState(screens = listOf(Screen.Home, Screen.Settings, Screen.DeviceCheck)),
                    onOpen = {},
                    onBack = {},
                    home = { androidx.compose.material3.Text("home") },
                    deviceCheck = DeviceCheckUi(enabled = false, state = DeviceCheckState(report = report)),
                )
            }
        }
        compose.onNodeWithTag("deviceCheck").assertDoesNotExist()
        compose.onNodeWithText("home").assertExists()
    }

    @Test
    fun `the screen shows the report and offers run again, copy and share`() {
        var runs = 0
        val copied = mutableListOf<String>()
        val shared = mutableListOf<String>()
        compose.setContent {
            KeepClearTheme {
                AppScreens(
                    state = HomeUiState(screens = listOf(Screen.Home, Screen.Settings, Screen.DeviceCheck)),
                    onOpen = {},
                    onBack = {},
                    home = {},
                    deviceCheck = DeviceCheckUi(
                        enabled = true,
                        state = DeviceCheckState(report = report),
                        onRun = { runs++ },
                        onCopy = { copied += it },
                        onShare = { shared += it },
                    ),
                )
            }
        }
        compose.onNodeWithTag("deviceCheckReport").assertExists()
        compose.onNodeWithText("[PASS] Scan time: 21.0 s", substring = true).assertExists()
        compose.onNodeWithText("The report is in English", substring = true).assertExists()
        compose.onNodeWithTag("deviceCheckCopy").performClick()
        compose.onNodeWithTag("deviceCheckShare").performClick()
        compose.onNodeWithTag("deviceCheckRun").performClick()
        assertEquals(listOf(report), copied)
        assertEquals(listOf(report), shared)
        assertEquals(1, runs)
    }

    @Test
    fun `while running, copy and share wait for the report`() {
        compose.setContent {
            KeepClearTheme {
                DeviceCheckScreen(DeviceCheckUi(enabled = true, state = DeviceCheckState(running = true)), onBack = {})
            }
        }
        compose.onNodeWithTag("deviceCheckProgress").assertExists()
        compose.onNodeWithTag("deviceCheckRun").assertIsNotEnabled()
        compose.onNodeWithTag("deviceCheckCopy").assertIsNotEnabled()
        compose.onNodeWithTag("deviceCheckShare").assertIsNotEnabled()
    }

    @Test
    fun `run again is available once a report is there`() {
        compose.setContent {
            KeepClearTheme {
                DeviceCheckScreen(DeviceCheckUi(enabled = true, state = DeviceCheckState(report = report)), onBack = {})
            }
        }
        compose.onNodeWithTag("deviceCheckRun").assertIsEnabled()
        compose.onNodeWithTag("deviceCheckShare").assertIsEnabled()
    }
}
