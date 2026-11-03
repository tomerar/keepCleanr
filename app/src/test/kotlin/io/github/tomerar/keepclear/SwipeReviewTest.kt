// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** REV-5: swipe mode decides one by one, can undo, and deletes only through the usual confirmation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SwipeReviewTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L

    private fun group() = Classifier().let { c ->
        val items = (1..3).map { i ->
            val p = "/storage/emulated/0/Pictures/Screenshots/s$i.png"
            c.classify(ScanItem(KPath.Local(p), p, 1000L * i, 1_678_000_000_000L + i), now = now)
        }
        ClassificationSummary.of(items).group("screenshots", Category.REVIEW)!!
    }

    @Test
    fun `the counter shows exact numbers with thousands separators`() {
        // Owner scale: 2,324 screenshots. The counter said "1 of 2324".
        val c = Classifier()
        val items = (1..2_324).map { i ->
            val p = "/storage/emulated/0/Pictures/Screenshots/s$i.png"
            c.classify(ScanItem(KPath.Local(p), p, 1000L, 1_678_000_000_000L + i), now = now)
        }
        compose.setContent { KeepClearTheme { SwipeReview(items, onDone = {}, onClose = {}) } }
        val text = compose.onNodeWithTag("swipeProgress").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")
        // Bidi isolates around each number; the digits are grouped.
        assertEquals("1 of 2,324", text.filter { it.code !in 0x2066..0x2069 })
    }

    @Test
    fun `decisions, undo, and only Delete choices are returned`() {
        val items = group().items

        fun next(d: List<SwipeDecision>) = SwipeSession.next(items, d)
        var d = SwipeSession.decide(emptyList(), next(emptyList()), SwipeChoice.DELETE)
        d = SwipeSession.decide(d, next(d), SwipeChoice.KEEP)
        d = SwipeSession.undo(d)
        d = SwipeSession.decide(d, next(d), SwipeChoice.SKIP)
        d = SwipeSession.decide(d, next(d), SwipeChoice.DELETE)
        d = SwipeSession.decide(d, next(d), SwipeChoice.DELETE) // past the end: ignored
        assertEquals(3, d.size)
        assertEquals(listOf(items[0], items[2]), SwipeSession.toDelete(items, d))
    }

    @Test
    fun `a Delete decision stays with its file when the list changes (E11, safety)`() {
        val items = group().items
        // "Delete" for the first file, "Keep" for the second.
        var d = SwipeSession.decide(emptyList(), items[0], SwipeChoice.DELETE)
        d = SwipeSession.decide(d, items[1], SwipeChoice.KEEP)
        // A rescan finished meanwhile: the first file is gone, so every other file moved up one place.
        val after = items.drop(1)
        // Nothing the user kept, and nothing they never saw, is offered for deletion.
        assertEquals(emptyList<ClassifiedItem>(), SwipeSession.toDelete(after, d))
        assertEquals(items[2], SwipeSession.next(after, d))
        // The same list in another order (sorted differently): decisions still follow the files.
        val reordered = items.reversed()
        assertEquals(listOf(items[0]), SwipeSession.toDelete(reordered, d))
    }

    @Test
    fun `the shown file changes while deciding - the list moving never changes what Delete applies to`() {
        val items = group().items
        var shown: List<ClassifiedItem> by androidx.compose.runtime.mutableStateOf(items)
        var decisions: List<SwipeDecision> by androidx.compose.runtime.mutableStateOf(emptyList())
        compose.setContent {
            KeepClearTheme {
                SwipeReview(shown, onDone = {}, onClose = {}, decisions = decisions, onDecisions = { decisions = it })
            }
        }
        compose.onNodeWithTag("swipeKeep").performClick() // keep s1
        // The list changes: s1 is gone, s2 is first now.
        shown = items.drop(1)
        compose.waitForIdle()
        compose.onNodeWithTag("swipeDelete").performClick() // the file on screen: s2
        assertEquals(
            listOf(
                SwipeDecision.of(items[0], SwipeChoice.KEEP),
                SwipeDecision.of(items[1], SwipeChoice.DELETE),
            ),
            decisions,
        )
    }

    @Test
    fun `swiping ends in the editable confirmation with exactly the chosen items`() {
        var deleted: List<ClassifiedItem>? = null
        compose.setContent {
            KeepClearTheme {
                GroupScreen(group(), onOpenItem = {}, onBack = {}, onDelete = { items, _ -> deleted = items })
            }
        }
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("startSwipe"))
        compose.onNodeWithTag("startSwipe").performClick()
        compose.onNodeWithTag("swipeDelete").performClick()
        compose.onNodeWithTag("swipeKeep").performClick()
        compose.onNodeWithTag("swipeUndo").performClick()
        compose.onNodeWithTag("swipeSkip").performClick()
        compose.onNodeWithTag("swipeKeep").performClick()
        compose.onNodeWithTag("swipeSummary").assertExists()
        // Nothing was deleted while swiping.
        assertEquals(null, deleted)
        compose.onNodeWithTag("swipeFinish").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmDelete").fetchSemanticsNodes()
                .any { SemanticsProperties.Disabled !in it.config }
        }
        compose.onNodeWithTag("confirmDelete").performClick()
        // Only the first item shown was swiped toward Delete.
        assertEquals(listOf(group().items.first().item.path), deleted!!.map { it.item.path })
    }

    @Test
    fun `keeping everything offers nothing to delete`() {
        compose.setContent { KeepClearTheme { SwipeReview(group().items, onDone = {}, onClose = {}) } }
        repeat(3) { compose.onNodeWithTag("swipeKeep").performClick() }
        compose.onNodeWithTag("swipeFinish").assertIsNotEnabled()
    }

    @Test
    fun `advancing never shows the previous item's picture while the next one loads`() {
        val g = group()
        val first = g.items[0].item.path
        val loader = object : ThumbnailLoader(null) {
            override suspend fun load(item: ScanItem, sizePx: Int): android.graphics.Bitmap? =
                if (item.path == first) {
                    android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
                } else {
                    kotlinx.coroutines.awaitCancellation()
                }
        }
        compose.setContent {
            KeepClearTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalThumbnailLoader provides loader) {
                    SwipeReview(g.items, onDone = {}, onClose = {})
                }
            }
        }
        compose.waitForIdle()

        fun pictures() = compose.onAllNodesWithTag("thumbPicture", useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals(1, pictures()) // the first item shows its picture
        compose.onNodeWithTag("swipeKeep").performClick()
        compose.waitForIdle()
        // The second item is still loading: no picture at all, never the first item's.
        assertEquals(0, pictures())
    }
}
