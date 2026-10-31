// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
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

/** M8: REVIEW selection, select-by-month, editable confirmation (REV-2/3, SAFETY-10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReviewActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val march = 1_678_000_000_000L // 2023-03
    private val november = 1_700_000_000_000L // 2023-11

    private fun summary(): ClassificationSummary {
        val c = Classifier()

        fun i(name: String, t: Long) =
            c.classify(
                ScanItem(
                    KPath.Local("/storage/emulated/0/Pictures/Screenshots/$name"),
                    "/storage/emulated/0/Pictures/Screenshots/$name",
                    1000,
                    t,
                ),
                now = now,
            )
        return ClassificationSummary.of(listOf(i("a.png", march), i("b.png", march + 1000), i("c.png", november)))
    }

    private var deleted: List<ClassifiedItem>? = null

    private fun show(category: Category = Category.REVIEW) = compose.setContent {
        val s = summary()
        val group = s.group("screenshots", Category.REVIEW)!!.let {
            if (category ==
                Category.REVIEW
            ) {
                it
            } else {
                it.copy(category = category)
            }
        }
        KeepClearTheme { GroupScreen(group, onOpenItem = {}, onBack = {}, onDelete = { deleted = it }) }
    }

    private fun awaitEnabled(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { SemanticsProperties.Disabled !in it.config }
    }

    @Test
    fun `select a month, remove one in the confirmation, delete the rest`() {
        show()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("selectMonth_2023-03"))
        compose.onNodeWithTag("selectMonth_2023-03").performClick()
        compose.onAllNodesWithTag("tileSelected").assertCountEquals(2)
        compose.onNodeWithTag("deleteSelected").assertIsEnabled().performClick()
        compose.onNodeWithTag("remove_a.png").performClick()
        awaitEnabled("confirmDelete")
        compose.onNodeWithTag("confirmDelete").performClick()
        assertEquals(listOf("b.png"), deleted!!.map { it.item.path.substringAfterLast('/') })
    }

    @Test
    fun `tapping a tile toggles it`() {
        show()
        compose.onAllNodesWithTag("tile")[0].performClick()
        compose.onAllNodesWithTag("tileSelected").assertCountEquals(1)
        compose.onAllNodesWithTag("tileSelected")[0].performClick()
        compose.onAllNodesWithTag("tileSelected").assertCountEquals(0)
    }

    @Test
    fun `MOVE groups are not deletable here`() {
        show(Category.MOVE)
        compose.onNodeWithTag("deleteDisabled").assertIsNotEnabled()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("bucket_2023-03"))
        compose.onNodeWithTag("selectMonth_2023-03").assertDoesNotExist()
    }
}
