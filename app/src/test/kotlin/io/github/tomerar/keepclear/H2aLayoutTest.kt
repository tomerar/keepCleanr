// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.Receipt
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Owner brief H2 item 12: layout bugs at 200 % font and on tablets and foldables. Real text measuring (NATIVE
 * graphics): the default Robolectric graphics put any text on one line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class H2aLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        KeepClearTheme(reducedMotion = true) {
            androidx.compose.runtime.CompositionLocalProvider(LocalClock provides { OwnerPhone.NOW }) { content() }
        }
    }

    private fun node(tag: String, unmerged: Boolean = false): SemanticsNodeInteraction =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged)

    private fun layoutOf(interaction: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        interaction.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun isBreak(c: Char) = c.isWhitespace() || c == '⁨' || c == '⁩' || c == '‏'

    /** Every line but the last ends between two words. */
    private fun assertNoMidWordBreak(layout: TextLayoutResult, what: String) {
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue("$what: \"$text\" breaks inside a word at $end", isBreak(text[end - 1]) || isBreak(text[end]))
        }
    }

    private fun width(r: DpRect): Dp = r.right - r.left

    private fun fontScale(scale: Float) = RuntimeEnvironment.setFontScale(scale)

    // ------------------------------------------------------------------------------------------- month header

    @Test
    @Config(sdk = [36], qualifiers = "iw-w360dp-h800dp")
    fun `at 200 percent a review month header wraps only between words`() {
        fontScale(2f)
        val group = OwnerPhone.summary.group("screenshots", Category.REVIEW)!!
        show { GroupScreen(group, {}, {}) }
        val bucket = group.buckets.first().first!!
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("bucket_$bucket"))
        val header = node("bucket_$bucket")
        assertNoMidWordBreak(layoutOf(header), "month header")
        // A month's name is short: it stays on one line (the button wraps below it instead).
        assertEquals(1, layoutOf(header).lineCount)
    }

    // ------------------------------------------------------------------------------------------- tabs and rail

    private fun showTabs() {
        val state = OwnerPhone.home.copy(trash = OwnerPhone.trash)
        show { AppScreens(state = state, onOpen = {}, onBack = {}, home = { HomeScreen(state, false) }) }
    }

    private fun tabLabels(): List<SemanticsNodeInteraction> = Tab.entries.map { node("tabLabel_${it.name}", true) }

    @Test
    @Config(sdk = [36], qualifiers = "iw-w360dp-h800dp")
    fun `at 200 percent the bottom tab labels keep a margin from their tab's edges`() {
        fontScale(2f)
        showTabs()
        Tab.entries.forEach { t ->
            val item = node("tab_${t.name}").getBoundsInRoot()
            val label = node("tabLabel_${t.name}", true).getBoundsInRoot()
            val start = label.left - item.left
            val end = item.right - label.right
            assertTrue("${t.name} label touches its tab's edge ($start, $end)", start >= 4.dp && end >= 4.dp)
            assertFalse(layoutOf(node("tabLabel_${t.name}", true)).hasVisualOverflow)
        }
    }

    private fun assertRailLabelsFit() {
        val rail = node(NAV_RAIL_TAG).getBoundsInRoot()
        tabLabels().forEachIndexed { i, labelNode ->
            val label = labelNode.getBoundsInRoot()
            val layout = layoutOf(labelNode)
            assertFalse("rail label $i overflows", layout.hasVisualOverflow)
            assertTrue(
                "rail label $i is outside the rail",
                label.left >= rail.left - 0.5.dp && label.right <= rail.right + 0.5.dp,
            )
            assertNoMidWordBreak(layout, "rail label $i")
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "iw-w673dp-h841dp")
    fun `rail labels fit the rail on a foldable`() {
        fontScale(1f)
        showTabs()
        assertRailLabelsFit()
    }

    @Test
    @Config(sdk = [36], qualifiers = "iw-w673dp-h841dp")
    fun `rail labels fit the rail on a foldable at 200 percent`() {
        fontScale(2f)
        showTabs()
        assertRailLabelsFit()
    }

    @Test
    @Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
    fun `rail labels fit the rail on a tablet`() {
        fontScale(1f)
        showTabs()
        assertRailLabelsFit()
    }

    // ------------------------------------------------------------------------------------------- top bar

    @Test
    @Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
    fun `on a tablet the top bar lines up with the centred content`() {
        fontScale(1f)
        showTabs()
        val content = node("homeScreen").getBoundsInRoot()
        val wordmark = node("wordmark").getBoundsInRoot()
        val gear = node("openSettings").getBoundsInRoot()
        // The wordmark starts where the content's column starts, and the gear ends where it ends (± padding).
        assertTrue("wordmark at ${wordmark.left}, content at ${content.left}", wordmark.left >= content.left - 1.dp)
        assertTrue(wordmark.left - content.left <= 32.dp)
        assertTrue("gear at ${gear.right}, content to ${content.right}", gear.right <= content.right + 1.dp)
        assertTrue(content.right - gear.right <= 32.dp)
    }

    // ------------------------------------------------------------------------------------------- age chips

    private fun assertAllChipNotAlone() {
        show {
            MoveScreen(OwnerPhone.summary, MoveUiState(), OwnerPhone.NOW, false, MoveActions(), onBack = null)
        }
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("age_ALL"))
        val tops = MoveAge.entries.associateWith { node("age_${it.name}").getBoundsInRoot().top }
        val rows = tops.values.groupingBy { it }.eachCount()
        val allAlone = rows.getValue(tops.getValue(MoveAge.ALL)) == 1
        // Either "all" shares a row, or no chip shares one (a column of choices).
        assertTrue("rows: $tops", !allAlone || rows.values.all { it == 1 })
    }

    @Test
    @Config(sdk = [36], qualifiers = "iw-w360dp-h800dp")
    fun `the age chip All never sits alone on its row, Hebrew`() {
        fontScale(1f)
        assertAllChipNotAlone()
    }

    @Test
    @Config(sdk = [36], qualifiers = "iw-w360dp-h800dp")
    fun `the age chip All never sits alone on its row, Hebrew at 200 percent`() {
        fontScale(2f)
        assertAllChipNotAlone()
    }

    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h800dp")
    fun `the age chip All never sits alone on its row, English`() {
        fontScale(1f)
        assertAllChipNotAlone()
    }

    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h800dp")
    fun `the age chip All never sits alone on its row, English at 200 percent`() {
        fontScale(2f)
        assertAllChipNotAlone()
    }

    // ------------------------------------------------------------------------------------------- backup summary

    @Test
    @Config(sdk = [36], qualifiers = "iw-w360dp-h800dp")
    fun `at 200 percent the backup summary keeps a drive's name on one line`() {
        fontScale(2f)
        val receipt = Receipt(
            "j1",
            "SanDisk USB",
            "KeepClear/Phone",
            verified = 6_830,
            verifiedBytes = 31_000_000_000,
            failed = emptyMap(),
            changed = 0,
            pending = 0,
            namesChanged = 0,
            confirmed = 6_830,
            confirmedBytes = 31_000_000_000,
        )
        val job = MoveJob("j1", "t1", "SanDisk USB", "KeepClear/Phone", OwnerPhone.NOW, state = JobState.DONE)
        val state = OwnerPhone.home.copy(tab = Tab.BACKUP, screens = listOf(Screen.Backup), trash = OwnerPhone.trash)
        val move = MoveUiState(jobs = listOf(JobView(job, receipt, canReadBack = true)))
        show { AppScreens(state = state, onOpen = {}, onBack = {}, home = {}, move = move) }
        val title = node("receiptTitle", true)
        val layout = layoutOf(title)
        val text = layout.layoutInput.text.text
        val start = text.indexOf("SanDisk")
        val end = text.indexOf("USB")
        assertTrue(start >= 0 && end > start)
        assertEquals("\"$text\" splits the drive's name", layout.getLineForOffset(start), layout.getLineForOffset(end))
    }

    // ------------------------------------------------------------------------------------------- grid columns

    /** Tiles on the first row of the first month. */
    private fun tilesPerRow(): Int {
        val tiles = compose.onAllNodes(
            SemanticsMatcher("a tile") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("preview_") == true
            },
        ).fetchSemanticsNodes()
        val firstTop = tiles.minOf { it.boundsInRoot.top }
        return tiles.count { it.boundsInRoot.top == firstTop }
    }

    private val manyScreenshots: ClassificationSummary by lazy {
        val c = Classifier()
        ClassificationSummary.of(
            (1..40).map { i ->
                val p = "/storage/emulated/0/Pictures/Screenshots/Screenshot_202610${"%02d".format(i % 28 + 1)}_$i.png"
                c.classify(
                    ScanItem(
                        io.github.tomerar.keepclear.core.storage.KPath.Local(p),
                        p,
                        1_000L,
                        OwnerPhone.NOW - i * 3_600_000L,
                    ),
                    now = OwnerPhone.NOW,
                )
            },
        )
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h800dp")
    fun `a phone shows four columns of pictures`() {
        fontScale(1f)
        show { GroupScreen(manyScreenshots.groups(Category.REVIEW).first(), {}, {}) }
        assertEquals(4, tilesPerRow())
    }

    @Test
    @Config(sdk = [36], qualifiers = "w1280dp-h800dp-mdpi")
    fun `a tablet shows more columns of pictures`() {
        fontScale(1f)
        show { ReadableWidth { GroupScreen(manyScreenshots.groups(Category.REVIEW).first(), {}, {}) } }
        assertTrue("${tilesPerRow()} columns", tilesPerRow() >= 8)
    }

    @Suppress("unused")
    private fun all(tag: String) = compose.onAllNodesWithTag(tag)
}
