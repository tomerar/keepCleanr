// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The owner's design QA of 2026-11-02, items 1 (UI), 3, 4, 5 and 6. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class DesignQaTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now = System.currentTimeMillis()
    private val old = now - 400L * 24 * 3_600_000
    private val classifier = Classifier()

    private fun item(rel: String, size: Long = 1_000_000): ClassifiedItem {
        val path = "/storage/emulated/0/$rel"
        return classifier.classify(ScanItem(KPath.Local(path), path, size, old), now = now)
    }

    private val thumb = item("DCIM/.thumbnails/1.jpg", 4_000_000)
    private val bin = item("DCIM/Camera/.trashed-1700000000-IMG_1.jpg", 2_000_000)
    private val lost = item("LOST.DIR/x", 3_000_000)

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun size(bytes: Long) = Bidi.isolate(ByteSize.format(bytes, Locale.US))

    /** The text of [id] up to where its last argument goes (for sentences that end in a date). */
    private fun prefix(id: Int, vararg first: Any) = s(id, *first, "\u0000").substringBefore("\u0000")

    private fun confirm(items: List<ClassifiedItem>) = compose.setContent {
        KeepClearTheme(reducedMotion = true) { CleanConfirmDialog(items, onClean = { _, _ -> }, onDismiss = {}) }
    }

    // ------------------------------------------------------------------ item 1: what is deleted for good

    @Test
    fun `a mixed clean says which part is deleted for good and which goes to the trash, and why`() {
        listOf(thumb, bin, lost).forEach { assertEquals(Category.SAFE, it.category) }
        confirm(listOf(thumb, bin, lost))
        // Owner brief H2 item 5: "What happens" with each part's size; the bins part is the red line.
        compose.onNodeWithText(s(R.string.confirm_fact_what_happens)).assertExists()
        compose.onNodeWithText(s(R.string.clean_happens_rebuilds), substring = true).assertExists()
        compose.onNodeWithText(s(R.string.confirm_where_trash), substring = true).assertExists()
        compose.onNodeWithTag(io.github.tomerar.keepclear.core.designsystem.component.CONFIRM_SHEET_RISK_TAG)
            .assertExists()
        compose.onNodeWithText(s(R.string.clean_confirm_note_rest), substring = true).assertExists()
        // Not "Move to trash": part of it doesn't go there.
        compose.onNodeWithText(s(R.string.clean_confirm_yes_now)).assertExists()
        compose.onAllNodesWithText(s(R.string.clean_confirm_yes)).assertCountEquals(0)
        // The parts' sizes: 4 MB rebuild themselves, 2 MB from the bin, 3 MB to the trash.
        compose.onNodeWithText(size(4_000_000), substring = true).assertExists()
        compose.onNodeWithText(size(2_000_000), substring = true).assertExists()
        compose.onNodeWithText(size(3_000_000), substring = true).assertExists()
    }

    @Test
    fun `a clean of trash-only items reads as before`() {
        confirm(listOf(lost))
        compose.onNodeWithText(s(R.string.confirm_where_trash)).assertExists()
        compose.onNodeWithText(s(R.string.clean_confirm_note), substring = true).assertExists()
        compose.onNodeWithText(s(R.string.clean_confirm_yes)).assertExists()
        compose.onAllNodesWithText(s(R.string.clean_happens_rebuilds), substring = true).assertCountEquals(0)
    }

    @Test
    fun `a clean of permanent items only says deleted for good, now`() {
        confirm(listOf(thumb))
        compose.onNodeWithText(s(R.string.clean_happens_rebuilds)).assertExists()
        compose.onAllNodesWithText(s(R.string.confirm_where_trash), substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.clean_confirm_note_rest), substring = true).assertCountEquals(0)
    }

    private fun banner(r: CleanResult, onUndo: (CleanResult) -> Unit = {}) = compose.setContent {
        KeepClearTheme(reducedMotion = true) { CleanResultBanner(r, onUndo = { x, _ -> onUndo(x) }, onOpenTrash = {}) }
    }

    @Test
    fun `the result leads with the measured part, then the trash part with Undo and Empty now`() {
        val measured = Measured(4_100_000_000, 20_900_000_000, 16_800_000_000, Screen.Home)
        var undone: CleanResult? = null
        val r = CleanResult(
            done = Total(10, 7_900_000_000),
            skipped = 0,
            failed = 0,
            trashedPaths = listOf("/storage/emulated/0/LOST.DIR/x"),
            deletedForGood = Total(6, 4_100_000_000),
            measured = measured,
        )
        banner(r) { undone = it }
        compose.onNodeWithText(s(R.string.result_freed_now, size(4_100_000_000), size(20_900_000_000))).assertExists()
        compose.onNode(hasText(prefix(R.string.clean_in_trash, size(3_800_000_000)), substring = true)).assertExists()
        compose.onNodeWithTag("emptyNowLink").assertExists()
        compose.onNodeWithTag("undo").performClick()
        // Undo puts back only what went to the trash.
        assertEquals(listOf("/storage/emulated/0/LOST.DIR/x"), undone!!.trashedPaths)
    }

    @Test
    fun `when everything was deleted for good there is nothing to undo`() {
        val r = CleanResult(
            done = Total(6, 4_100_000_000),
            skipped = 0,
            failed = 0,
            trashedPaths = emptyList(),
            deletedForGood = Total(6, 4_100_000_000),
            measured = Measured(4_100_000_000, 20_900_000_000, 16_800_000_000, Screen.Home),
        )
        banner(r)
        compose.onNodeWithText(s(R.string.result_freed_now, size(4_100_000_000), size(20_900_000_000))).assertExists()
        compose.onNodeWithTag("undo").assertDoesNotExist()
        compose.onNodeWithTag("emptyNowLink").assertDoesNotExist()
    }

    @Test
    fun `in test mode the result says what would have been deleted for good, and nothing can be undone`() {
        val r = CleanResult(
            done = Total(6, 4_100_000_000),
            skipped = 0,
            failed = 0,
            trashedPaths = emptyList(),
            simulated = true,
            deletedForGood = Total(6, 4_100_000_000),
        )
        banner(r)
        compose.onNodeWithTag("simulatedResult").assertExists()
        compose.onNodeWithTag("undo").assertDoesNotExist()
        compose.onNode(hasText(prefix(R.string.test_mode_would_delete_for_good), substring = true)).assertExists()
    }

    // ------------------------------------------------------------------ item 5: ConfirmSheet

    @Test
    fun `the title is only the question, with the size`() {
        confirm(listOf(thumb, lost))
        compose.onNodeWithText(s(R.string.clean_confirm_title, size(7_000_000))).assertExists()
    }

    @Test
    fun `a tap while the confirm button fills does nothing, and it fills instead of looking disabled`() {
        var cleaned = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeepClearTheme(reducedMotion = false) {
                CleanConfirmDialog(listOf(lost), onClean = { _, _ -> cleaned++ }, onDismiss = {})
            }
        }
        compose.mainClock.advanceTimeBy(200)
        // Filling: the fill layer is there, and the button has no click action yet.
        compose.onNodeWithTag(CONFIRM_FILL_TAG, useUnmergedTree = true).assertExists()
        val node = compose.onNodeWithTag("confirmClean").fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsActions.OnClick))
        assertTrue(SemanticsProperties.Disabled in node.config)
        compose.onNodeWithTag("confirmClean").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, cleaned)
        compose.mainClock.advanceTimeBy(CONFIRM_READY_MILLIS)
        compose.onNodeWithTag(CONFIRM_FILL_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("confirmClean").performClick()
        assertEquals(1, cleaned)
    }

    @Test
    fun `under reduced motion the button is an unfilled track until it is ready`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                CleanConfirmDialog(listOf(lost), onClean = { _, _ -> }, onDismiss = {})
            }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag(CONFIRM_FILL_TAG, useUnmergedTree = true).assertExists()
        assertTrue(SemanticsProperties.Disabled in compose.onNodeWithTag("confirmClean").fetchSemanticsNode().config)
        compose.mainClock.advanceTimeBy(CONFIRM_READY_MILLIS)
        assertTrue(SemanticsProperties.Disabled !in compose.onNodeWithTag("confirmClean").fetchSemanticsNode().config)
    }

    // ------------------------------------------------------------------ item 6: review grid

    @Test
    fun `tiles have no preview glyph, and TalkBack can open the preview with a custom action`() {
        val shots = (0 until 3).map { item("Pictures/Screenshots/Screenshot_$it.png") }
        val summary = io.github.tomerar.keepclear.engine.classify.ClassificationSummary.of(shots)
        var opened: ClassifiedItem? = null
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                GroupScreen(summary.group("screenshots", Category.REVIEW)!!, onOpenItem = { opened = it }, onBack = {})
            }
        }
        compose.onAllNodesWithText("⤢").assertCountEquals(0)
        val tile = compose.onAllNodesWithTag("tile")[0].fetchSemanticsNode()
        val actions = tile.config[SemanticsActions.CustomActions]
        val preview = actions.single { it.label == s(R.string.review_preview) }
        compose.runOnIdle { preview.action() }
        assertTrue(opened != null)
    }

    // ------------------------------------------------------------------ item 3: Trash

    private fun entry(id: String, name: String, verified: String? = null) = TrashEntry(
        id = id,
        originalPath = "/storage/emulated/0/Pictures/$name",
        trashedPath = "/storage/emulated/0/.KeepClearTrash/20261031/$id/$name",
        volumeRoot = "/storage/emulated/0",
        size = 3_000_000,
        mtimeMillis = 1,
        ruleId = if (verified != null) "camera" else "other_app_bin",
        reason = if (verified != null) "MOVE_DELETE_ORIGINAL" else "TRASH",
        deletedAt = now - DAY,
        expiresAt = now + 29 * DAY,
        deletedAtElapsed = 0,
        bootId = "b",
        state = TrashState.ACTIVE,
        verifiedCopy = verified,
    )

    @Test
    @Config(qualifiers = "w360dp-h2400dp")
    fun `the Trash leads with the list, a one-line header, actions below and the gallery's names`() {
        val entries = listOf(
            entry("g", ".trashed-1700000000-VID_20240304.mp4"),
            entry("c", "IMG_2.jpg", verified = "USB:KeepClear/IMG_2.jpg"),
        )
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                TrashScreen(TrashUi(entries, releasable = setOf("c")), { _, _ ->
                }, { _, _ -> }, {}, now = now, initialView = TrashView.LIST)
            }
        }
        // The original name, with where it came from.
        compose.onNodeWithText("VID_20240304.mp4", substring = true).assertExists()
        compose.onNodeWithTag("fromGalleryBin_g").assertExists()
        compose.onAllNodesWithText(".trashed-", substring = true).assertCountEquals(0)
        // One line: size · count · when it goes.
        val header = s(R.string.trash_summary, "x").substringAfter("x")
        compose.onNodeWithText(header, substring = true).assertExists()
        assertEquals(-1, s(R.string.trash_summary, "x").indexOf('\n'))
        // The actions on the whole trash come after the groups (a tall screen: everything is composed).
        val empty = compose.onNodeWithTag("emptyNow").fetchSemanticsNode().positionInRoot.y
        val lastGroup = compose.onAllNodes(hasTestTag("trashGroup_MOVE")).fetchSemanticsNodes().single()
        assertTrue(empty > lastGroup.positionInRoot.y)
        compose.onNodeWithText(s(R.string.trash_release_all)).assertExists()
        compose.onNodeWithText(s(R.string.trash_empty_now)).assertExists()
    }

    @Test
    fun `hiding a group's files says hide the list`() {
        val entries = listOf(entry("g", "a.mp4"))
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                TrashScreen(TrashUi(entries), { _, _ -> }, { _, _ -> }, {}, now = now, initialView = TrashView.LIST)
            }
        }
        compose.onNodeWithText(s(R.string.trash_hide_files)).assertExists()
        assertEquals("Hide the list", s(R.string.trash_hide_files))
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
