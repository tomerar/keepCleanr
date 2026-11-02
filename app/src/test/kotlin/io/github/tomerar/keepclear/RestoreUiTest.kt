// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.engine.restore.RestorePreview
import io.github.tomerar.keepclear.engine.restore.RestoreReport
import io.github.tomerar.keepclear.engine.restore.RoomCheck
import io.github.tomerar.keepclear.engine.restore.Space
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

    private val groups = listOf(
        RestoreGroup("Camera", "2021-01", 30_000, toOriginal = 3, toRestoreFolder = 0),
        RestoreGroup("WhatsApp Images", "2021-02", 5_000, toOriginal = 0, toRestoreFolder = 2, skipped = 1),
        RestoreGroup("WhatsApp Images", "2021-03", 7_000, toOriginal = 1, toRestoreFolder = 1),
    )
    private val plenty = Space(allocatable = 50_000_000_000, total = 128_000_000_000)
    private val catalog = RestoreStep.Catalog("KeepClear/OnePlus 10T", groups, plenty)

    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }

    private var shown: androidx.compose.runtime.MutableState<RestoreReport>? = null

    /** Shows [report] as the result (content is set once per test) and returns its text. */
    private fun done(report: RestoreReport): String {
        val state = shown
        if (state == null) {
            val s = androidx.compose.runtime.mutableStateOf(report).also { shown = it }
            compose.setContent { KeepClearTheme { RestoreDialogs(RestoreStep.Done(s.value), RestoreActions()) } }
        } else {
            state.value = report
            compose.waitForIdle()
        }
        return text("restoreDone")
    }

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
        val text = done(report)
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
    fun `the catalog says where each month goes, and free space against what is chosen (D1, D2)`() {
        compose.setContent { KeepClearTheme { RestoreDialogs(catalog, RestoreActions()) } }
        assertEquals("Back to where they were", text("restoreWhere_Camera|2021-01"))
        assert("restore folder" in text("restoreWhere_WhatsApp Images|2021-02"))
        assert("back to where they were, " in text("restoreWhere_WhatsApp Images|2021-03"))
        compose.onNodeWithTag("restore_Camera|2021-01").performClick()
        val space = text("restoreSpace")
        assert("Free on the phone" in space && "Chosen" in space) { space }
        compose.onNodeWithTag("restoreTooBig").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "iw")
    fun `in Hebrew the catalog says original place or restore folder (D2)`() {
        compose.setContent { KeepClearTheme { RestoreDialogs(catalog, RestoreActions()) } }
        assertEquals("למקום המקורי", text("restoreWhere_Camera|2021-01"))
        assert(text("restoreWhere_WhatsApp Images|2021-02").startsWith("לתיקיית שחזור"))
    }

    @Test
    fun `months that don't fit can't be restored, and choosing only what fits trims them (D1)`() {
        // Room for 20,000 bytes above the 1 GB reserve.
        val tight = catalog.copy(space = Space(allocatable = 1_000_020_000, total = 16_000_000_000))
        var chosen: Set<String>? = null
        compose.setContent { KeepClearTheme { RestoreDialogs(tight, RestoreActions(onRestore = { chosen = it })) } }
        compose.onNodeWithTag("restore_Camera|2021-01").performClick()
        compose.onNodeWithTag("restore_WhatsApp Images|2021-02").performClick()
        compose.onNodeWithTag("restoreRun").assertIsNotEnabled()
        assert("1.0 GB" in text("restoreTooBig")) { text("restoreTooBig") }
        compose.onNodeWithTag("restoreFit").performScrollTo().performClick()
        compose.onNodeWithTag("restoreRun").assertIsEnabled().performClick()
        assertEquals(setOf("WhatsApp Images|2021-02"), chosen)
    }

    @Test
    fun `nothing can be restored when free space can't be measured (D1)`() {
        compose.setContent { KeepClearTheme { RestoreDialogs(catalog.copy(space = null), RestoreActions()) } }
        compose.onNodeWithTag("restore_Camera|2021-01").performClick()
        compose.onNodeWithTag("restoreRun").assertIsNotEnabled()
        assert("couldn't check" in text("restoreSpace"))
    }

    @Test
    fun `fitting by month keeps chosen months in order while they fit (D1)`() {
        val all = groups.map { it.key }.toSet()
        assertEquals(all, fitByMonth(groups, all, 42_000))
        assertEquals(setOf("WhatsApp Images|2021-02", "WhatsApp Images|2021-03"), fitByMonth(groups, all, 29_999))
        assertEquals(emptySet<String>(), fitByMonth(groups, all, 4_999))
        assertEquals(setOf("WhatsApp Images|2021-03"), fitByMonth(groups, setOf("WhatsApp Images|2021-03"), 50_000))
    }

    @Test
    fun `trial mode says what would be restored and that nothing was written (D1)`() {
        val text = done(RestoreReport(simulated = RestorePreview(3, 2, 1, 2_000_000_000)))
        listOf("Would have restored", "trial mode", "Nothing was written", "not restored").forEach {
            assert(it in text) { "missing \"$it\" in: $text" }
        }
        assert("files restored and checked" !in text)
    }

    @Test
    fun `the result says when the phone was too full (D1)`() {
        val refused = done(RestoreReport(notEnoughRoom = RoomCheck(5_000_000_000, plenty)))
        assert("Nothing was restored" in refused) { refused }
        val stopped = done(RestoreReport(restored = listOf("/storage/emulated/0/a.jpg"), stoppedLowSpace = true))
        assert("almost full" in stopped) { stopped }
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
