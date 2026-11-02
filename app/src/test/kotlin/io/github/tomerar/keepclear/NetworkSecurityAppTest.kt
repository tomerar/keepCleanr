// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ThisDeviceInTests::class)

package io.github.tomerar.keepclear

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.backup.net.HostRule
import io.github.tomerar.keepclear.backup.net.TestsOnThisDevice
import io.github.tomerar.keepclear.backup.net.ThisDeviceInTests
import io.github.tomerar.keepclear.backup.smb.SmbLocation
import io.github.tomerar.keepclear.backup.webdav.NextcloudAccount
import io.github.tomerar.keepclear.backup.webdav.NextcloudProblem
import io.github.tomerar.keepclear.backup.webdav.NextcloudRevoke
import io.github.tomerar.keepclear.backup.webdav.NextcloudServer
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner review 2026-11-02, D3/D6/L6 in the app: the app's own parsers and stores never accept this phone as a
 * network place (only tests opt in), a guest drive is announced as copy-only, forgetting the Nextcloud says what
 * happened on the server, and test builds with the public key say "use a test account".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkSecurityAppTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun text(id: Int) = context.getString(id)

    @Test
    fun `the app-facing parsers and stores refuse this phone, only the test rule reaches it (D3)`() {
        // The app's rule is the strict one, unless a test changes it.
        assertEquals(HostRule.NetworkOnly, KeepClearApplication().nextcloudHosts)
        listOf("http://127.0.0.1:8089", "https://localhost", "https://[::1]", "127.0.0.1").forEach {
            assertEquals(it, NextcloudProblem.THIS_PHONE, NextcloudServer.problem(it))
        }
        listOf("127.0.0.1", "localhost", "0.0.0.0").forEach { host ->
            assertTrue(runCatching { SmbLocation(host, "photos") }.isFailure)
        }
        // A loopback place saved by a test-rule store never loads in the app's store.
        val prefs = context.getSharedPreferences("nextcloud-d3", Context.MODE_PRIVATE)
        val testStore = NextcloudStore(prefs, NextcloudStoreTest.FakeBox(), TestsOnThisDevice)
        testStore.save(
            NextcloudAccount(
                NextcloudServer.parse("http://127.0.0.1:8089", TestsOnThisDevice),
                "dana",
                "dana",
                "A1b2".repeat(18),
            ),
        )
        assertNotNull(testStore.load())
        assertNull(NextcloudStore(prefs, NextcloudStoreTest.FakeBox()).load())
        val drives = NetworkDriveStore(
            context.getSharedPreferences("network-d3", Context.MODE_PRIVATE),
            NextcloudStoreTest.FakeBox(),
        )
        drives.save(SmbLocation("127.0.0.1", "photos", user = "me", password = "pw", hosts = TestsOnThisDevice))
        assertNull(drives.load())
    }

    private fun networkForm(notice: Boolean) = compose.setContent {
        KeepClearTheme { NetworkDriveDialog(saved = null, onConnect = {}, onDismiss = {}, testAccountNotice = notice) }
    }

    @Test
    fun `the network form refuses this phone's address and says guest places are copy-only (D3)`() {
        networkForm(notice = false)
        compose.onNodeWithTag("networkGuestNote").assertExists()
        compose.onNodeWithTag("networkHost").performTextInput("127.0.0.1")
        compose.onNodeWithTag("networkShare").performTextInput("photos")
        compose.onNodeWithTag("networkConnect").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.move_network_this_phone)).assertExists()
        compose.onNodeWithTag("networkUser").performTextInput("me")
        compose.onNodeWithTag("networkGuestNote").assertDoesNotExist()
    }

    @Test
    fun `test builds with the public key say to use a test account, private-key builds don't (L6)`() {
        networkForm(notice = true)
        compose.onNodeWithTag("testAccountNotice").assertExists()
        compose.onNodeWithText(text(R.string.test_build_use_test_account)).assertExists()
    }

    @Test
    fun `no test-account notice once the private key signs (L6)`() {
        networkForm(notice = false)
        compose.onNodeWithTag("testAccountNotice").assertDoesNotExist()
    }

    @Test
    fun `the Nextcloud screen shows the notice only with the public test key (L6)`() {
        var notice by mutableStateOf(true)
        compose.setContent { KeepClearTheme { NextcloudScreen(NextcloudUi(testAccountNotice = notice), onBack = {}) } }
        compose.onNodeWithTag("testAccountNotice").assertExists()
        notice = false
        compose.onNodeWithTag("testAccountNotice").assertDoesNotExist()
    }

    @Test
    fun `the Nextcloud form says plainly when the address is this phone (D3)`() {
        compose.setContent { KeepClearTheme { NextcloudScreen(NextcloudUi(testAccountNotice = false), onBack = {}) } }
        compose.onNodeWithTag("nextcloudAddress").performTextInput("https://127.0.0.1")
        compose.onNodeWithTag("nextcloudSignIn").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.nextcloud_this_phone)).assertExists()
    }

    @Test
    fun `after forgetting, Settings says honestly whether the server signed the phone out (D6)`() {
        var result by mutableStateOf<NextcloudRevoke?>(NextcloudRevoke.NOT_REVOKED)
        var busy by mutableStateOf(true)
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    testModeOn = true,
                    onTestModeOn = {},
                    onTestModeOff = {},
                    onBack = {},
                    actions = SettingsActions(
                        nextcloudHost = "cloud.example.com",
                        forgettingNextcloud = busy,
                        nextcloudForgot = result,
                    ),
                )
            }
        }
        compose.onNodeWithTag("forgetNextcloud").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.settings_nextcloud_forgot_not_revoked)).assertExists()
        busy = false
        result = NextcloudRevoke.REVOKED
        compose.onNodeWithTag("forgetNextcloud").assertIsEnabled()
        compose.onNodeWithText(text(R.string.settings_nextcloud_forgot_revoked)).assertExists()
        result = NextcloudRevoke.ALREADY_INVALID
        compose.onNodeWithText(text(R.string.settings_nextcloud_forgot_revoked)).assertExists()
    }
}
