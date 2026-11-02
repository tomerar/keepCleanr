// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M5: read-only browsing Home → category → group → item (REV-1/2, SCAN-2, SAFETY-10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReviewScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val root = "/storage/emulated/0"

    private fun summary(screenshots: Int = 3): ClassificationSummary {
        val c = Classifier()

        fun item(rel: String, size: Long, mtime: Long) =
            c.classify(ScanItem(KPath.Local("$root/$rel"), "$root/$rel", size, mtime), now = now)
        val items =
            (0 until screenshots).map {
                item(
                    "Pictures/Screenshots/Screenshot_$it.png",
                    1_000_000L + it,
                    1_678_000_000_000 + it * 3_600_000L,
                )
            } +
                listOf(
                    item("DCIM/Camera/IMG_1.jpg", 4_000_000, 1_600_000_000_000),
                    item("Documents/תמונה של אמא 🎂.jpg", 2_000_000, 1_600_000_000_000),
                    item(
                        "Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14",
                        450_000_000,
                        1_600_000_000_000,
                    ),
                )
        return ClassificationSummary.of(items)
    }

    private fun showApp(summary: ClassificationSummary) {
        compose.setContent {
            var state by remember {
                mutableStateOf(
                    HomeUiState(mediaAccess = MediaAccess.FULL, scan = ScanState.Done(10, 10, true), summary = summary),
                )
            }
            KeepClearTheme {
                AppScreens(
                    state = state,
                    onOpen = { state = state.copy(screens = state.screens + it) },
                    onBack = { state = state.copy(screens = state.screens.dropLast(1)) },
                    // E4: selections live in the (here: test) ViewModel state, as in the app.
                    selectionActions = SelectionActions(
                        onSelect = { key, paths -> state = state.copy(selections = state.selections + (key to paths)) },
                        onSwipe = { key, d ->
                            state =
                                state.copy(swipes = if (d == null) state.swipes - key else state.swipes + (key to d))
                        },
                    ),
                    home = {
                        HomeScreen(
                            state = state,
                            showDebugTools = false,
                            actions = HomeActions(onOpenCategory = {
                                state =
                                    state.copy(screens = state.screens + Screen.CategoryList(it))
                            }),
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `home shows the three totals and never counts protected items (SCAN-2)`() {
        showApp(summary())
        compose.onNodeWithTag("total_REVIEW").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("total_MOVE").assertExists()
        // F1b: SAFE is the main button in the free-space card (here without a measured phone size).
        compose.onNodeWithTag("safeCard").assertExists()
        // 450 MB of chat databases may appear in "where your space is", never in an action total.
        for (tag in listOf("safeCard", "total_REVIEW", "total_MOVE")) {
            compose.onAllNodes(hasText("450", substring = true) and hasAnyAncestor(hasTestTag(tag)))
                .assertCountEquals(0)
        }
    }

    @Test
    fun `navigate home to category to group to item and back`() {
        showApp(summary())
        compose.onNodeWithTag("open_REVIEW").performScrollTo().performClick()
        compose.onNodeWithTag("group_screenshots").performClick()
        compose.onNodeWithTag("grid").assertIsDisplayed()
        compose.onNodeWithTag("deleteSelected").assertDoesNotExist()
        compose.onAllNodesWithTag("tile")[0].assertExists()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("preview_Screenshot_0.png"))
        compose.onNodeWithTag("preview_Screenshot_0.png").performTouchInput { longClick() }
        compose.onNodeWithTag("preview").assertIsDisplayed()
        compose.onNodeWithTag("preview").performScrollToIndex(3)
        compose.onNodeWithText("Why is this here?").assertExists()
        compose.onNodeWithTag("preview").performScrollToIndex(0)
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("grid").assertIsDisplayed()
        // E4: the grid came back where it was (scrolled to the picture); F1b: the back arrow is in the pinned top
        // app bar, so it is there without scrolling.
        compose.onNodeWithTag("preview_Screenshot_0.png").assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("categoryList").assertIsDisplayed()
    }

    @Test
    fun `month headers group the grid (REV-2)`() {
        showApp(summary())
        compose.onNodeWithTag("open_REVIEW").performScrollTo().performClick()
        compose.onNodeWithTag("group_screenshots").performClick()
        compose.onNodeWithTag("bucket_2023-03").assertIsDisplayed()
    }

    @Test
    fun `nothing is pre-selected (SAFETY-10)`() {
        showApp(summary())
        compose.onNodeWithTag("open_REVIEW").performScrollTo().performClick()
        compose.onNodeWithTag("group_screenshots").performClick()
        compose.onAllNodesWithTag("tileSelected").assertCountEquals(0)
        compose.onNodeWithTag("deleteSelected").assertDoesNotExist()
    }

    @Test
    fun `a 2000-item group renders and scrolls to the end`() {
        showApp(summary(screenshots = 2_000))
        compose.onNodeWithTag("open_REVIEW").performScrollTo().performClick()
        compose.onNodeWithTag("group_screenshots").performClick()
        // E14: the last picture is really there at the end, and the first one is no longer composed.
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("preview_Screenshot_1999.png"))
        compose.onNodeWithTag("preview_Screenshot_1999.png").assertIsDisplayed()
        compose.onNodeWithTag("preview_Screenshot_0.png").assertDoesNotExist()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("bucket_2023-03"))
        compose.onNodeWithTag("bucket_2023-03").assertIsDisplayed()
    }

    @Test
    fun `10,000 pictures - scroll, select all, preview and back keeps the selection and the place (E4)`() {
        showApp(summary(screenshots = 10_000))
        compose.onNodeWithTag("open_REVIEW").performScrollTo().performClick()
        compose.onNodeWithTag("group_screenshots").performClick()
        compose.onNodeWithTag("selectAll").performClick()
        compose.onNodeWithTag("deleteSelected").assertExists()
        val selectedText = compose.onNodeWithTag("deleteSelected").fetchSemanticsNode()
            .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("preview_Screenshot_5000.png"))
        compose.onNodeWithTag("preview_Screenshot_5000.png").performTouchInput { longClick() }
        compose.onNodeWithTag("preview").assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        // Still all 10,000 selected, and still at the same place in the grid.
        compose.onNodeWithTag("grid").assertIsDisplayed()
        compose.onNodeWithTag("preview_Screenshot_5000.png").assertExists()
        compose.onNodeWithTag("deleteSelected").assertExists()
        assertEquals(
            selectedText,
            compose.onNodeWithTag("deleteSelected").fetchSemanticsNode()
                .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString(),
        )
        assertTrue(selectedText.orEmpty().contains("10,000"))
    }

    @Test
    fun `every rule id has typed explanation strings`() {
        assertEquals(Classifier.explainedRuleIds.toSet(), RuleText.byRule.keys)
    }

    @Test
    fun `protected category is not reachable from Home`() {
        showApp(summary())
        compose.onNodeWithTag("open_${Category.PROTECTED}").assertDoesNotExist()
    }
}
