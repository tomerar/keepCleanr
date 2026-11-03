// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** Owner brief H1 item 9 (PR H1c): the Trash as a grid by month, search, single-item restore, TalkBack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H1cTrashGridTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("Asia/Jerusalem")
    private val now = 1_800_000_000_000L

    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun entry(id: String, name: String, mtime: Long, verified: String? = null) = TrashEntry(
        id = id,
        originalPath = "/storage/emulated/0/DCIM/Camera/$name",
        trashedPath = "/storage/emulated/0/.KeepClearTrash/20261031/$id/$name",
        volumeRoot = "/storage/emulated/0",
        size = 3_000_000,
        mtimeMillis = mtime,
        reason = "TRASH",
        deletedAt = now - 86_400_000,
        expiresAt = now + 20 * 86_400_000L,
        deletedAtElapsed = 0,
        bootId = "b",
        state = TrashState.ACTIVE,
        verifiedCopy = verified,
    )

    private val beach = entry("a", "IMG_beach.jpg", at(2021, 7, 12))
    private val party = entry("b", "VID_party.mp4", at(2025, 12, 31), verified = "SanDisk")
    private val doc = entry("c", "letter.pdf", at(2026, 1, 1))

    private var restored: List<TrashEntry>? = null
    private var released: List<TrashEntry>? = null

    private fun show(trash: TrashUi = TrashUi(listOf(beach, party, doc), releasable = setOf("b"))) =
        compose.setContent {
            KeepClearTheme {
                TrashScreen(
                    trash,
                    onRestore = { e, _ -> restored = e },
                    onPurge = { _, _ -> },
                    onBack = {},
                    onRelease = { e, _ -> released = e },
                    now = now,
                    zone = zone,
                )
            }
        }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("trash").performScrollToNode(hasTestTag(tag))

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1600dp")
    fun `the trash opens as a grid in month sections, newest first, across the year boundary`() {
        show()
        val months = listOf("2026-01", "2025-12", "2021-07")
        val headers = compose.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("a month header") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("monthHeader_") == true
            },
        ).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
        assertEquals(months.map { "monthHeader_$it" }, headers.map { it.config[SemanticsProperties.TestTag] })
        scrollTo("tile_a")
        compose.onNodeWithTag("tile_a").assertIsDisplayed()
        compose.onNodeWithTag("entry_a").assertDoesNotExist()
        // The grouped list is one tap away.
        compose.onNodeWithTag("trashViewToggle").performClick()
        compose.onNodeWithTag("trash").performScrollToNode(hasTestTag("entry_a"))
        compose.onNodeWithTag("tile_a").assertDoesNotExist()
    }

    @Test
    fun `search filters by name and by a typed date`() {
        show()
        compose.onNodeWithTag("trashSearch").performTextInput("beach")
        scrollTo("tile_a")
        assertTrue(compose.onAllNodesWithTag("tile_b").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("tile_c").fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "iw")
    fun `search by a Hebrew month name finds that month, and no match says so`() {
        show()
        compose.onNodeWithTag("trashSearch").performTextInput("דצמבר 2025")
        scrollTo("tile_b")
        assertTrue(compose.onAllNodesWithTag("tile_a").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("trashSearch").performTextInput(" xyz")
        compose.onNodeWithTag("trashNoMatch").assertExists()
    }

    @Test
    fun `a tile opens its details, and Restore there puts back that one file only`() {
        show()
        scrollTo("tile_a")
        compose.onNodeWithTag("tile_a").performClick()
        compose.onNodeWithTag("trashDetails").assertExists()
        compose.onNodeWithTag("daysLeft_a").assertExists()
        compose.onNodeWithTag("release_a").assertDoesNotExist()
        compose.onNodeWithTag("restore_a").performClick()
        assertEquals(listOf(beach), restored)
        compose.onNodeWithTag("trashDetails").assertDoesNotExist()
    }

    @Test
    fun `a changed file is never restored from its tile without the separate-folder confirm (owner 1-2)`() {
        show(TrashUi(listOf(beach, party), changed = setOf("a")))
        scrollTo("tile_a")
        restoreAction("tile_a").invoke()
        assertEquals(null, restored)
        compose.onNodeWithTag("confirmRestoreChanged").assertExists()
    }

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun actions(tag: String) =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun restoreAction(tag: String): () -> Unit = {
        actions(tag).first().action()
        compose.waitForIdle()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1600dp")
    fun `TalkBack reads name, date and size and offers Restore, Preview and only there Free now`() {
        show()
        scrollTo("tile_b")
        val config = node("tile_b").fetchSemanticsNode().config
        val description = config[SemanticsProperties.ContentDescription].single()
        listOf("VID_party.mp4", "2025", "3.0 MB").forEach { assertTrue("$it in $description", it in description) }
        assertEquals(listOf("Restore", "Preview", "Free now"), actions("tile_b").map { it.label })
        scrollTo("tile_a")
        // No checked backup copy: no permanent action on this tile, as in the list.
        assertEquals(listOf("Restore", "Preview"), actions("tile_a").map { it.label })

        actions("tile_a").single { it.label == "Restore" }.action()
        assertEquals(listOf(beach), restored)
        actions("tile_b").single { it.label == "Free now" }.action()
        compose.waitForIdle()
        // Permanent: it opens the red second confirmation, nothing is deleted yet.
        compose.onNodeWithTag("confirmPurge").assertExists()
        assertEquals(null, released)
        actions("tile_a").single { it.label == "Preview" }.action()
    }

    @Test
    fun `an empty trash says files stay 30 days and offers one next step`() {
        var restoreFromBackup = 0
        compose.setContent {
            KeepClearTheme {
                TrashScreen(TrashUi(), { _, _ -> }, { _, _ -> }, {}, onRestoreFromBackup = { restoreFromBackup++ })
            }
        }
        val text = node("trashEmpty").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        assertTrue(text, "30 days" in text)
        node("trashEmptyRestore").performClick()
        assertEquals(1, restoreFromBackup)
    }

    @Test
    @Config(sdk = [36], qualifiers = "iw", fontScale = 2.0f)
    // Real text measuring (the default Robolectric graphics put any text on one line).
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `at 200 percent a month header wraps only between words, never inside one`() {
        show(TrashUi((1..12).map { m -> entry("m$m", "IMG_$m.jpg", at(2026, m, 3)) }))
        (1..12).forEach { m ->
            val tag = "monthHeader_2026-%02d".format(m)
            scrollTo(tag)
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            // The header spans the whole row (never squeezed beside anything, the old FlowRow bug).
            val row = node("trash").getBoundsInRoot()
            val header = node(tag).getBoundsInRoot()
            assertTrue(
                "$tag is ${header.right - header.left} wide",
                header.right - header.left >= row.right - row.left - 40.dp,
            )
            val text = layout.layoutInput.text.text
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                assertTrue("\"$text\" breaks inside a word at $end", text[end - 1] == ' ' || text[end] == ' ')
            }
        }
    }

    /** The widest row of tiles on screen. */
    private fun tilesPerRow(): Int {
        scrollTo("tile_m12")
        val tops = (1..12).mapNotNull { m ->
            compose.onAllNodesWithTag("tile_m$m").fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.top
        }
        return tops.groupingBy { it }.eachCount().values.max()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1600dp")
    fun `a phone shows three columns`() {
        show(TrashUi((1..12).map { entry("m$it", "IMG_$it.jpg", at(2026, 3, it)) }))
        assertEquals(3, tilesPerRow())
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h1600dp")
    fun `a tablet shows more columns`() {
        show(TrashUi((1..12).map { entry("m$it", "IMG_$it.jpg", at(2026, 3, it)) }))
        assertTrue(tilesPerRow() >= 6)
    }
}
