// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

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
    fun `decisions, undo, and only Delete choices are returned`() {
        val items = group().items
        var d = SwipeSession.decide(emptyList(), SwipeChoice.DELETE, 3)
        d = SwipeSession.decide(d, SwipeChoice.KEEP, 3)
        d = SwipeSession.undo(d)
        d = SwipeSession.decide(d, SwipeChoice.SKIP, 3)
        d = SwipeSession.decide(d, SwipeChoice.DELETE, 3)
        d = SwipeSession.decide(d, SwipeChoice.DELETE, 3) // past the end: ignored
        assertEquals(3, d.size)
        assertEquals(listOf(items[0], items[2]), SwipeSession.toDelete(items, d))
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
        compose.onNodeWithTag("swipeReview").performClick()
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
        compose.onNodeWithTag("swipeReview").assertIsNotEnabled()
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
        val firstPicture = compose.onAllNodesWithTag("tileName", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(0, firstPicture.size) // the first item shows its picture, not its name
        compose.onNodeWithTag("swipeKeep").performClick()
        compose.waitForIdle()
        // The second item is still loading: its name shows, never the first item's picture.
        assertEquals(1, compose.onAllNodesWithTag("tileName", useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
