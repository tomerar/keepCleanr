// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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

    /** F2 (C76): the computer at home is set up in Settings › Advanced, not in the backup picker. */
    private fun openForm(onNetwork: (SmbLocation) -> Unit) {
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    testModeOn = true,
                    onTestModeOn = {},
                    onTestModeOff = {},
                    onBack = {},
                    actions = SettingsActions(onSetUpNetworkDrive = onNetwork),
                )
            }
        }
        compose.onNodeWithTag("setUpNetwork").performScrollTo().performClick()
        compose.onNodeWithTag("networkDrive").assertExists()
    }

    private fun openPicker(actions: MoveActions) {
        compose.setContent {
            KeepClearTheme {
                MoveScreen(
                    summary = summary(),
                    state = MoveUiState(),
                    now = now,
                    notificationsOff = false,
                    actions = actions,
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("source_camera"))
        compose.onNodeWithTag("source_camera").performClick()
        compose.onNodeWithTag("age_ALL").performClick()
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("pickTarget"))
        compose.onNodeWithTag("pickTarget").performClick()
        compose.onNodeWithTag("pickGuide").assertExists()
    }

    @Test
    fun `the backup picker has no address form, only the USB drive or memory card when nothing is set up (C76)`() {
        openPicker(MoveActions())
        compose.onNodeWithTag("guideContinue").assertExists()
        compose.onNodeWithTag("useNetwork").assertDoesNotExist()
        compose.onNodeWithTag("networkDrive").assertDoesNotExist()
    }

    @Test
    fun `the computer set up under Advanced is offered in the picker and used as saved (C76)`() {
        val saved = SmbLocation("192.168.1.20", "photos", user = "me", password = "s3cret")
        var used: Pair<Int, SmbLocation>? = null
        openPicker(
            MoveActions(
                networkHost = "192.168.1.20",
                savedNetworkDrive = { saved },
                onNetworkDrive = { items, location -> used = items.size to location },
            ),
        )
        compose.onNodeWithTag("useNetwork").assertTextContains("192.168.1.20", substring = true).performClick()
        assertEquals(1 to saved, used)
        compose.onNodeWithTag("networkDrive").assertDoesNotExist()
    }

    @Test
    fun `restore offers the computer at home only once it is set up, and uses the saved place (C76)`() {
        val saved = SmbLocation("192.168.1.20", "photos", user = "me", password = "s3cret")
        var used: SmbLocation? = null
        var host by androidx.compose.runtime.mutableStateOf<String?>(null)
        compose.setContent {
            KeepClearTheme {
                RestoreDialogs(
                    RestoreStep.Pick,
                    RestoreActions(networkHost = host, savedNetworkDrive = { saved }, onNetworkDrive = { used = it }),
                )
            }
        }
        compose.onNodeWithTag("restoreNetwork").assertDoesNotExist()
        host = "192.168.1.20"
        compose.onNodeWithTag("restoreNetwork").performClick()
        assertEquals(saved, used)
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
