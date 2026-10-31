// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
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
        val items = (0 until screenshots).map { item("Pictures/Screenshots/Screenshot_$it.png", 1_000_000L + it, 1_678_000_000_000 + it * 86_400_000L) } +
            listOf(
                item("DCIM/Camera/IMG_1.jpg", 4_000_000, 1_600_000_000_000),
                item("Documents/תמונה של אמא 🎂.jpg", 2_000_000, 1_600_000_000_000),
                item("Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14", 450_000_000, 1_600_000_000_000),
            )
        return ClassificationSummary.of(items)
    }

    private fun showApp(summary: ClassificationSummary) {
        compose.setContent {
            var state by mutableStateOf(
                HomeUiState(mediaAccess = MediaAccess.FULL, scan = ScanState.Done(10, 10, true), summary = summary),
            )
            KeepClearTheme {
                AppScreens(
                    state = state,
                    onOpen = { state = state.copy(screens = state.screens + it) },
                    onBack = { state = state.copy(screens = state.screens.dropLast(1)) },
                    home = {
                        HomeScreen(
                            state = state,
                            showDebugTools = false,
                            actions = HomeActions(onOpenCategory = { state = state.copy(screens = state.screens + Screen.CategoryList(it)) }),
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
        compose.onNodeWithTag("total_SAFE").assertExists()
        // 450 MB of chat databases may appear in "where your space is", never in an action total.
        for (category in listOf("SAFE", "REVIEW", "MOVE")) {
            compose.onAllNodes(hasText("450", substring = true) and hasAnyAncestor(hasTestTag("total_$category")))
                .assertCountEquals(0)
        }
    }

    @Test
    fun `navigate home to category to group to item and back`() {
        showApp(summary())
        compose.onNodeWithTag("open_REVIEW").performScrollTo().performClick()
        compose.onNodeWithTag("group_screenshots").performClick()
        compose.onNodeWithTag("grid").assertIsDisplayed()
        compose.onNodeWithTag("deleteDisabled").assertIsNotEnabled()
        compose.onAllNodesWithTag("tile")[0].performClick()
        compose.onNodeWithTag("preview").assertIsDisplayed()
        compose.onNodeWithTag("preview").performScrollToIndex(3)
        compose.onNodeWithText("Why is this here?").assertExists()
        compose.onNodeWithTag("preview").performScrollToIndex(0)
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("grid").assertIsDisplayed()
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
    fun `nothing is selected or selectable in read-only browsing (SAFETY-10)`() {
        showApp(summary())
        compose.onNodeWithTag("open_REVIEW").performScrollTo().performClick()
        compose.onNodeWithTag("group_screenshots").performClick()
        compose.onNodeWithText("Select", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a 2000-item group renders and scrolls to the end`() {
        showApp(summary(screenshots = 2_000))
        compose.onNodeWithTag("open_REVIEW").performScrollTo().performClick()
        compose.onNodeWithTag("group_screenshots").performClick()
        compose.onNodeWithTag("grid").performScrollToIndex(2_000)
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("bucket_2023-03"))
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
