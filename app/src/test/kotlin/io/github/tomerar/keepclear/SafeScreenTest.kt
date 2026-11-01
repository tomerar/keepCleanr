// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SAFE-1/5/6, SAFETY-7, SAFETY-10 on the Clean now screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val old = now - 400L * 24 * 3_600_000

    private fun summary(): ClassificationSummary {
        val c = Classifier()

        fun i(rel: String) = c.classify(
            ScanItem(KPath.Local("/storage/emulated/0/$rel"), "/storage/emulated/0/$rel", 1000, old),
            now = now,
        )
        return ClassificationSummary.of(
            listOf(
                i("DCIM/.thumbnails/1.jpg"),
                i("DCIM/.thumbnails/2.jpg"),
                i("Download/a.tmp"),
                i("LOST.DIR/x"),
                i("DCIM/Camera/IMG.jpg"),
            ),
        )
    }

    private var cleaned: List<ClassifiedItem>? = null
    private var trimmed = 0
    private var undone: CleanResult? = null

    private var confirmedPaths: Set<String>? = null

    private fun show(result: CleanResult? = null, external: Boolean = false) = compose.setContent {
        KeepClearTheme {
            SafeScreen(
                summary = summary(),
                result = result,
                trimResult = null,
                showExternalCacheClear = external,
                onClean = { items, confirmation ->
                    cleaned = items
                    confirmedPaths = confirmation.paths
                },
                onUndo = { r, _ -> undone = r },
                onTrim = { trimmed++ },
                onClearExternalCaches = {},
                onOpenGroup = {},
                onOpenTrash = {},
                onBack = {},
            )
        }
    }

    @Test
    fun `SAFE groups are pre-selected and only SAFE items are cleaned`() {
        show()
        compose.onNodeWithTag("check_thumbnails").assertIsOn()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("cleanButton").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("confirmClean").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("confirmClean").assertIsEnabled().performClick()
        assertEquals(4, cleaned!!.size)
        // Review PR B: the tap names exactly what the dialog showed.
        assertEquals(cleaned!!.map { it.item.path }.toSet(), confirmedPaths)
        assertTrue(cleaned!!.none { it.item.path.contains("/DCIM/Camera/") })
    }

    @Test
    fun `unticked groups are not cleaned`() {
        show()
        compose.onNodeWithTag("check_thumbnails").performClick()
        compose.onNodeWithTag("cleanButton").performClick()
        awaitEnabled("confirmClean")
        compose.onNodeWithTag("confirmClean").performClick()
        assertEquals(setOf("temp_files", "lost_dir"), cleaned!!.map { it.ruleId }.toSet())
    }

    @Test
    fun `cache trim is labelled as not restorable before it runs (SAFETY-7)`() {
        show()
        compose.onNodeWithTag("safeScreen").performScrollToNode(hasTestTag("trim"))
        compose.onNodeWithTag("trim").performClick()
        compose.onNodeWithTag("trimLabel").assertIsDisplayed()
        assertEquals(0, trimmed)
        awaitEnabled("confirmTrim")
        compose.onNodeWithTag("confirmTrim").performClick()
        assertEquals(1, trimmed)
    }

    private fun awaitEnabled(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { SemanticsProperties.Disabled !in it.config }
    }

    @Test
    fun `result shows Undo (E-DEL-15)`() {
        val r = CleanResult(Total(4, 4000), skipped = 1, failed = 0, trashedPaths = listOf("/a"))
        show(result = r)
        compose.onNodeWithTag("undo").performClick()
        assertEquals(r, undone)
    }

    @Test
    fun `external cache clear only with All files access (SAFE-6)`() {
        show(external = false)
        compose.onNodeWithTag("safeScreen").performScrollToNode(hasTestTag("trim"))
        compose.onNodeWithTag("trimExternal").assertDoesNotExist()
    }
}
