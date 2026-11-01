// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CLS-7 / SET-2: keeping and "always junk", their limits, and the choke point. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserChoicesTest {
    @get:Rule
    val compose = createComposeRule()

    private val root = "/storage/emulated/0"
    private val store = UserChoices(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("choices-test", Context.MODE_PRIVATE),
    )

    @Test
    fun `always-junk is only for folders two levels deep, outside memories and app data (C12)`() {
        listOf("$root/Download/Memes", "$root/Telegram/Telegram Images", "/storage/1234-ABCD/Old/Junk")
            .forEach { assertTrue(it, UserChoices.canBeJunk(it)) }
        listOf(
            "$root/Download",
            "$root/DCIM/Camera",
            "$root/Pictures/Screenshots",
            "$root/Android/media/com.whatsapp",
            "$root/Documents/Work",
            "$root/.KeepClearTrash/x",
            "/data/data/x/y",
            "relative/path",
        ).forEach { assertFalse(it, UserChoices.canBeJunk(it)) }
    }

    @Test
    fun `keeping a folder takes it out of always-junk`() {
        assertTrue(store.alwaysJunk("$root/Download/Memes"))
        assertTrue(store.keep("$root/Download/Memes"))
        assertEquals(setOf("$root/Download/Memes"), store.state.value.kept)
        assertTrue(store.state.value.junk.isEmpty())
    }

    @Test
    fun `a kept file is refused at the choke point, not only hidden from the lists (SAFETY-3)`() {
        val path = "$root/Download/Tax/2023.zip"
        val protected = BackupRoots.protectedPaths(emptyList(), kept = listOf("$root/Download/Tax"))
        val op = PlannedOp(KPath.Local(path), path, OpKind.TRASH, false, 1, 1)
        val verdict = SafetyGuard(protected).check(ActionPlan("p", listOf(op), "t").confirm(1, "s"), "s")
        assertTrue(verdict is SafetyGuard.Verdict.Rejected)
    }

    @Test
    fun `the item screen keeps a file, and asks before treating a folder as junk`() {
        val now = 1_800_000_000_000L
        val path = "$root/Download/Memes/a.zip"
        val item = Classifier().classify(ScanItem(KPath.Local(path), path, 1000, now - 400L * 86_400_000), now = now)
        var kept: String? = null
        var junk: String? = null
        compose.setContent {
            KeepClearTheme {
                PreviewScreen(
                    item,
                    onBack = {},
                    actions = PreviewActions(onKeep = { kept = it }, onAlwaysJunk = {
                        junk =
                            it
                    }),
                )
            }
        }
        compose.onNodeWithTag("preview").performScrollToNode(hasTestTag("alwaysJunk"))
        compose.onNodeWithTag("alwaysJunk").performClick()
        assertEquals(null, junk)
        compose.onNodeWithTag("confirmAlwaysJunk").performClick()
        assertEquals("$root/Download/Memes", junk)
        compose.onNodeWithTag("preview").performScrollToNode(hasTestTag("keepItem"))
        compose.onNodeWithTag("keepItem").performClick()
        assertEquals(path, kept)
    }

    @Test
    fun `no always-junk for a possible memory`() {
        val now = 1_800_000_000_000L
        val path = "$root/DCIM/Camera/IMG_1.jpg"
        val item = Classifier().classify(ScanItem(KPath.Local(path), path, 1000, now - 400L * 86_400_000), now = now)
        compose.setContent { KeepClearTheme { PreviewScreen(item, onBack = {}) } }
        compose.onNodeWithTag("preview").performScrollToNode(hasTestTag("keepItem"))
        compose.onNodeWithTag("keepItem").assertExists()
        compose.onNodeWithTag("alwaysJunk").assertDoesNotExist()
    }
}
