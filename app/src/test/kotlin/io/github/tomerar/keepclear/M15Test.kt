// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import io.github.tomerar.keepclear.backup.smb.SmbLocation
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M15: setting up a network drive on the phone (address only, never a URL), and Settings naming the address. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class M15Test {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val old = now - 400L * 24 * 3_600_000

    private fun summary(): ClassificationSummary {
        val c = Classifier()
        val path = "/storage/emulated/0/DCIM/Camera/IMG.jpg"
        return ClassificationSummary.of(listOf(c.classify(ScanItem(KPath.Local(path), path, 1000, old), now = now)))
    }

    private fun openForm(onNetwork: (SmbLocation) -> Unit) {
        compose.setContent {
            KeepClearTheme {
                MoveScreen(
                    summary = summary(),
                    state = MoveUiState(),
                    now = now,
                    notificationsOff = false,
                    actions = MoveActions(onNetworkDrive = { _, location -> onNetwork(location) }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("source_camera"))
        compose.onNodeWithTag("source_camera").performClick()
        compose.onNodeWithTag("age_ALL").performClick()
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("pickTarget"))
        compose.onNodeWithTag("pickTarget").performClick()
        compose.onNodeWithTag("useNetwork").performClick()
        compose.onNodeWithTag("networkDrive").assertExists()
    }

    @Test
    fun `a URL or path in the address is refused, a plain address connects`() {
        var connected: SmbLocation? = null
        openForm { connected = it }
        compose.onNodeWithTag("networkHost").performTextInput("smb://nas/photos")
        compose.onNodeWithTag("networkShare").performTextInput("photos")
        compose.onNodeWithTag("networkConnect").assertIsNotEnabled()
        assertNull(connected)
    }

    @Test
    fun `the typed address, share, user and password are what is used`() {
        var connected: SmbLocation? = null
        openForm { connected = it }
        compose.onNodeWithTag("networkHost").performTextInput("192.168.1.20")
        compose.onNodeWithTag("networkShare").performTextInput("photos")
        compose.onNodeWithTag("networkUser").performTextInput("me")
        compose.onNodeWithTag("networkPassword").performTextInput("s3cret")
        compose.onNodeWithTag("networkConnect").assertIsEnabled().performClick()
        assertEquals(SmbLocation("192.168.1.20", "photos", user = "me", password = "s3cret"), connected)
    }

    @Test
    fun `Settings names the only address KeepClear connects to, and can forget it`() {
        var forgot = 0
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    testModeOn = true,
                    onTestModeOn = {},
                    onTestModeOff = {},
                    onBack = {},
                    actions = SettingsActions(networkHost = "192.168.1.20", onForgetNetworkDrive = { forgot++ }),
                )
            }
        }
        compose.onNodeWithTag("networkOnly").assertExists()
        compose.onNodeWithTag("forgetNetwork").performScrollTo().performClick()
        assertEquals(1, forgot)
    }
}
