// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.engine.restore.RestoreReport
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MOVE-17: choosing what to restore (nothing pre-selected) and an honest result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RestoreUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val catalog = RestoreStep.Catalog(
        "KeepClear/OnePlus 10T",
        listOf(RestoreGroup("Camera", "2021-01", 3, 30_000), RestoreGroup("WhatsApp Images", "2021-02", 2, 5_000)),
    )

    @Test
    fun `nothing is pre-selected, and only the chosen months are restored`() {
        var chosen: Set<String>? = null
        compose.setContent { KeepClearTheme { RestoreDialogs(catalog, RestoreActions(onRestore = { chosen = it })) } }
        compose.onNodeWithTag("restoreRun").assertIsNotEnabled()
        compose.onNodeWithTag("restore_Camera|2021-01").performClick()
        compose.onNodeWithTag("restoreTotal").assertExists()
        compose.onNodeWithTag("restoreRun").assertIsEnabled().performClick()
        assertEquals(setOf("Camera|2021-01"), chosen)
    }

    @Test
    fun `the result says what was restored, redirected, already there and damaged`() {
        val report = RestoreReport(
            restored = listOf(
                "/storage/emulated/0/DCIM/Camera/a.jpg",
                "/storage/emulated/0/Pictures/KeepClear Restored/Camera/b.jpg",
            ),
            alreadyThere = 1,
            damaged = 1,
            redirected = 1,
        )
        compose.setContent { KeepClearTheme { RestoreDialogs(RestoreStep.Done(report), RestoreActions()) } }
        val text = compose.onNodeWithTag("restoreDone").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        listOf(
            "files restored and checked",
            "Pictures › KeepClear Restored",
            "already on the phone",
            "doesn't match",
        ).forEach {
            assert(it in text) { "missing \"$it\" in: $text" }
        }
    }

    @Test
    fun `the play build explains instead of failing`() {
        var closed = 0
        compose.setContent {
            KeepClearTheme { RestoreDialogs(RestoreStep.NeedsAllFiles, RestoreActions(onClose = { closed++ })) }
        }
        compose.onNodeWithTag("restoreInfo").assertExists()
        compose.onNodeWithTag("restoreClose").performClick()
        assertEquals(1, closed)
    }
}
