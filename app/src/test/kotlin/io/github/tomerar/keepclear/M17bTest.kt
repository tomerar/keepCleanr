// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.Receipt
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M17b: Nextcloud only under Settings › Advanced, its set-up form, and the plain-language receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class M17bTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val old = now - 400L * 24 * 3_600_000

    private fun summary(): ClassificationSummary {
        val c = Classifier()
        val path = "/storage/emulated/0/DCIM/Camera/IMG.jpg"
        return ClassificationSummary.of(listOf(c.classify(ScanItem(KPath.Local(path), path, 1000, old), now = now)))
    }

    private fun openGuide(actions: MoveActions) {
        compose.setContent {
            KeepClearTheme {
                MoveScreen(summary(), MoveUiState(), now, notificationsOff = false, actions = actions, onBack = {})
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
    fun `the default path never offers Nextcloud`() {
        openGuide(MoveActions())
        compose.onNodeWithTag("useNetwork").assertExists()
        compose.onNodeWithTag("useNextcloud").assertDoesNotExist()
    }

    @Test
    fun `once set up under Advanced, Choose where offers it with the selection`() {
        var chosen: List<ClassifiedItem>? = null
        openGuide(MoveActions(nextcloudHost = "cloud.example.com", onNextcloud = { chosen = it }))
        compose.onNodeWithTag("useNextcloud").assertTextContains("cloud.example.com", substring = true).performClick()
        assertEquals(1, chosen!!.size)
    }

    @Test
    fun `restore offers Nextcloud only when it is set up`() {
        compose.setContent { KeepClearTheme { RestoreDialogs(RestoreStep.Pick, RestoreActions()) } }
        compose.onNodeWithTag("restorePick").assertExists()
        compose.onNodeWithTag("restoreNextcloud").assertDoesNotExist()
    }

    @Test
    fun `restore from the Nextcloud when it is set up`() {
        var asked = 0
        compose.setContent {
            KeepClearTheme {
                RestoreDialogs(
                    RestoreStep.Pick,
                    RestoreActions(nextcloudHost = "cloud.example.com", onNextcloud = {
                        asked++
                    }),
                )
            }
        }
        compose.onNodeWithTag("restoreNextcloud").performClick()
        assertEquals(1, asked)
    }

    private fun settings(actions: SettingsActions) = compose.setContent {
        KeepClearTheme { SettingsScreen(true, {}, {}, {}, actions) }
    }

    @Test
    fun `Settings › Advanced opens the set-up, and names nothing while none is set up`() {
        var opened = 0
        settings(SettingsActions(onOpenNextcloud = { opened++ }))
        compose.onNodeWithTag("nextcloudOnly").assertDoesNotExist()
        compose.onNodeWithTag("forgetNextcloud").assertDoesNotExist()
        compose.onNodeWithTag("openNextcloud").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `Settings names the Nextcloud as the only other place KeepClear connects to, and can forget it`() {
        var forgot = 0
        settings(SettingsActions(nextcloudHost = "cloud.example.com", onForgetNextcloud = { forgot++ }))
        compose.onNodeWithTag(
            "nextcloudOnly",
        ).performScrollTo().assertTextContains("cloud.example.com", substring = true)
        compose.onNodeWithTag("forgetNextcloud").performScrollTo().performClick()
        assertEquals(1, forgot)
    }

    private var signedIn: String? = null
    private var manual: Triple<String, String, String>? = null
    private val opened = mutableListOf<String>()

    private fun setUp(state: NextcloudSetupState = NextcloudSetupState()) = compose.setContent {
        KeepClearTheme {
            NextcloudScreen(
                NextcloudUi(
                    state = state,
                    onSignIn = { signedIn = it },
                    onManual = { a, u, p -> manual = Triple(a, u, p) },
                    onOpenLogin = { opened += it },
                ),
                onBack = {},
            )
        }
    }

    @Test
    fun `plain http to another device, credentials or a query in the address are refused before anything is sent`() {
        listOf(
            "http://cloud.example.com",
            "http://192.168.1.20",
            "https://dana:pw@cloud.example.com",
            "https://cloud.example.com/?x=1",
            "https://cloud.example.com/a/../b",
        ).also { setUp() }.forEach { address ->
            compose.onNodeWithTag("nextcloudAddress").performTextClearance()
            compose.onNodeWithTag("nextcloudAddress").performTextInput(address)
            compose.onNodeWithTag("nextcloudSignIn").assertIsNotEnabled()
        }
        assertNull(signedIn)
    }

    @Test
    fun `a plain address signs in through the browser`() {
        setUp()
        compose.onNodeWithTag("nextcloudAddress").performTextInput("cloud.example.com")
        compose.onNodeWithTag("nextcloudSignIn").assertIsEnabled().performClick()
        assertEquals("cloud.example.com", signedIn)
    }

    @Test
    fun `manual entry needs a user name without a colon and an app password`() {
        setUp()
        compose.onNodeWithTag("nextcloudAddress").performTextInput("https://cloud.example.com")
        compose.onNodeWithTag("nextcloudManualToggle").performClick()
        compose.onNodeWithTag("nextcloudUser").performTextInput("dana:x")
        compose.onNodeWithTag("nextcloudAppPassword").performTextInput("Abc123-app-password")
        compose.onNodeWithTag("nextcloudManualConnect").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("nextcloudUser").performTextClearance()
        compose.onNodeWithTag("nextcloudAppPassword").performTextClearance()
        compose.onNodeWithTag("nextcloudUser").performTextInput("dana")
        compose.onNodeWithTag("nextcloudManualConnect").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("nextcloudAppPassword").performTextInput("Abc123-app-password")
        compose.onNodeWithTag("nextcloudManualConnect").performScrollTo().assertIsEnabled().performClick()
        assertEquals(Triple("https://cloud.example.com", "dana", "Abc123-app-password"), manual)
    }

    @Test
    fun `waiting for the browser opens the sign-in page once, and again on request`() {
        setUp(NextcloudSetupState(NextcloudStep.Browser("https://cloud.example.com/login/v2/flow/abc")))
        compose.waitForIdle()
        assertEquals(listOf("https://cloud.example.com/login/v2/flow/abc"), opened)
        compose.onNodeWithTag("nextcloudOpenAgain").performClick()
        assertEquals(2, opened.size)
    }

    @Test
    fun `a certificate problem is said in plain words, with no way to trust it anyway`() {
        setUp(NextcloudSetupState(NextcloudStep.Form, NextcloudIssue.CERTIFICATE))
        compose.onNodeWithTag("nextcloudProblem").assertTextContains("certificate isn't trusted", substring = true)
    }

    private fun receipt(verified: Int, byServer: Int) = Receipt(
        "j1",
        "cloud.example.com",
        "KeepClear/Phone",
        verified,
        verified * 1_000L,
        emptyMap(),
        0,
        0,
        0,
        confirmed = verified,
        confirmedBytes = verified * 1_000L,
        checkedByServer = byServer,
    )

    private fun showReceipt(ref: String, r: Receipt) = compose.setContent {
        KeepClearTheme {
            ReceiptCard(
                JobView(
                    MoveJob("j1", "t", "cloud.example.com", "KeepClear/Phone", 1, JobState.DONE, targetRef = ref),
                    r,
                    true,
                ),
                {
                },
                {},
            )
        }
    }

    @Test
    fun `the receipt says the Nextcloud checked the copies, and how only under Details`() {
        showReceipt("davs://cloud.example.com/remote.php/dav/files/dana", receipt(3, 3))
        compose.onNodeWithTag(
            "receiptVerified",
        ).assertTextContains("Your Nextcloud checked each file", substring = true)
        compose.onNodeWithTag("detailsNextcloud").assertDoesNotExist()
        compose.onNodeWithTag("receiptDetails").performClick()
        compose.onNodeWithTag("detailsNextcloud").assertTextContains("SHA-256", substring = true)
    }

    @Test
    fun `some copies read back - both sentences`() {
        showReceipt("davs://cloud.example.com/remote.php/dav/files/dana", receipt(3, 2))
        compose.onNodeWithTag("receiptVerified").assertTextContains("read again", substring = true)
        compose.onNodeWithTag(
            "receiptNextcloud",
        ).assertTextContains("of them were checked by your Nextcloud", substring = true)
    }

    @Test
    fun `a drive's receipt never mentions Nextcloud`() {
        showReceipt("content://x/tree/y", receipt(3, 0))
        compose.onNodeWithTag("receiptNextcloud").assertDoesNotExist()
        compose.onNodeWithTag("receiptDetails").performClick()
        compose.onNodeWithTag("detailsNextcloud").assertDoesNotExist()
    }
}
