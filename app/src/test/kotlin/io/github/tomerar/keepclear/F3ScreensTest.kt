// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.THUMB_STRIP_TAG
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
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

/** PR F3: the F2 leftovers (pictures on Home's lane cards, progress on the Trash screen). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class F3ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val loaded = mutableListOf<String>()
    private val loader = object : ThumbnailLoader(null) {
        override suspend fun load(item: ScanItem, sizePx: Int): android.graphics.Bitmap {
            loaded += item.path
            return android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        }
    }

    private fun strip(lane: Category) =
        compose.onNode(
            hasTestTag(THUMB_STRIP_TAG) and hasAnyAncestor(hasTestTag("total_$lane")),
            useUnmergedTree = true,
        )

    @Test
    fun `Home's REVIEW and MOVE cards show pictures from their lane`() {
        compose.setContent {
            KeepClearTheme {
                CompositionLocalProvider(LocalThumbnailLoader provides loader) {
                    HomeScreen(OwnerPhone.home, showDebugTools = false)
                }
            }
        }
        compose.waitForIdle()
        strip(Category.REVIEW).assertExists()
        compose.onNodeWithTag("homeScreen").performScrollToNode(hasTestTag("total_MOVE"))
        strip(Category.MOVE).assertExists()
        // Only a few pictures per lane are read, never the whole lane (no extra scan).
        assertTrue("loaded ${loaded.size}", loaded.size <= 16)
    }

    @Test
    fun `a lane's pictures come from its largest groups in turn, on the phone only, and only a few`() {
        val c = Classifier()
        val root = "/storage/emulated/0"

        fun i(path: String, size: Long, t: Long) = c.classify(ScanItem(KPath.Local(path), path, size, t), now = 2_000L)
        val items = (1..50).map { i("$root/Movies/v$it.mp4", 200_000_000, it.toLong()) } +
            (1..500).map { i("$root/Pictures/Screenshots/s$it.png", 1_000_000, it.toLong()) } +
            (1..5).map { i("/storage/1234-5678/Pictures/Screenshots/sd$it.png", 1_000, 900L + it) }
        val picked = laneThumbItems(ClassificationSummary.of(items), Category.REVIEW)
        assertEquals(8, picked.size)
        assertTrue(picked.none { it.item.path.startsWith("/storage/1234-5678") })
        // Largest group first (big videos), then screenshots, alternating; newest first in each.
        assertEquals(listOf("big_video", "screenshots"), picked.take(2).map { it.ruleId })
        assertEquals("$root/Movies/v50.mp4", picked[0].item.path)
        assertEquals("$root/Pictures/Screenshots/s500.png", picked[1].item.path)
    }

    @Test
    fun `a backup source's pictures are of the chosen age, like its count`() {
        // Owner decision P2: the first backup defaults to everything; after one, to older than a year (tested here).
        val job = io.github.tomerar.keepclear.engine.move.MoveJob(
            "j0",
            "t",
            "SanDisk",
            "KeepClear/Phone",
            1,
            io.github.tomerar.keepclear.engine.move.JobState.DONE,
        )
        val earlierBackup = MoveUiState(
            jobs = listOf(
                JobView(job, io.github.tomerar.keepclear.engine.move.Receipt.of(job, emptyList()), canReadBack = true),
            ),
        )
        compose.setContent {
            KeepClearTheme {
                CompositionLocalProvider(LocalThumbnailLoader provides loader) {
                    MoveScreen(OwnerPhone.summary, earlierBackup, OwnerPhone.NOW, false, MoveActions(), onBack = null)
                }
            }
        }
        compose.waitForIdle()
        val cutoff = OwnerPhone.NOW - 12 * 30L * OwnerPhone.DAY
        val byPath = OwnerPhone.items.associateBy { it.item.path }
        val camera = loaded.mapNotNull { byPath[it] }.filter { it.ruleId == "camera" }
        assertTrue("no camera pictures loaded", camera.isNotEmpty())
        // Older than a year is chosen: the newest camera photos (this month's) must not stand for the group.
        assertTrue(camera.joinToString { it.item.path }, camera.all { it.item.mtimeMillis < cutoff })
    }

    // PR H6 (C197): the screenshots showed „Trash · 25…” next to the wordmark, the "?" and the gear.
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `at 100 percent font on a 360dp phone the Trash chip's words are never cut in English`(): Unit =
        chipFitsAt100("en")

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `at 100 percent font on a 360dp phone the Trash chip's words are never cut in Hebrew`(): Unit =
        chipFitsAt100("iw")

    private fun chipFitsAt100(locale: String) {
        org.robolectric.RuntimeEnvironment.setQualifiers("+$locale")
        compose.setContent {
            KeepClearTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(360.dp)) {
                    MainTopBar(OwnerPhone.trash, TabsUi())
                }
            }
        }
        val chip = compose.onNodeWithTag("trashChip").fetchSemanticsNode()
        assertEquals("the chip's words were cut", true, chip.config[TrashChipFits])
    }

    @Test
    // Real text measurement (the default Robolectric graphics measure every character as 1 px).
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `at 200 percent font the Trash chip shows its whole size next to the wordmark`() {
        org.robolectric.RuntimeEnvironment.setQualifiers("+iw")
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        compose.setContent {
            KeepClearTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(360.dp)) {
                        MainTopBar(OwnerPhone.trash, TabsUi())
                    }
                    // The same bar with room to spare: the chip at its natural width.
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredWidth(1_200.dp)) {
                        MainTopBar(OwnerPhone.trash, TabsUi())
                    }
                }
            }
        }
        // Owner brief H2 item 3: the chip now also has the word „סל”; on the phone it may get a little smaller (never
        // below its size at 100% font), but its words, and so the whole size, are never cut.
        val (phone, wide) = compose.onAllNodesWithTag("trashChip").fetchSemanticsNodes()
        listOf(phone, wide).forEach { chip ->
            assertEquals("the chip's words were cut", true, chip.config[TrashChipFits])
            val size = io.github.tomerar.keepclear.core.common.Bidi.strip(
                io.github.tomerar.keepclear.core.common.ByteSize.format(
                    trashChipBytes(OwnerPhone.trash)!!,
                    java.util.Locale.forLanguageTag("iw"),
                ),
            )
            assertTrue(io.github.tomerar.keepclear.core.common.Bidi.strip(chip.config[TrashChipLabel]).contains(size))
        }
    }

    @Test
    // Real text measurement (the default Robolectric graphics measure every character as 1 px).
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `at 200 percent font every tab's name fits its tab`() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        compose.setContent {
            KeepClearTheme { MainTabs(Tab.FREE, OwnerPhone.trash, TabsUi()) {} }
        }
        val texts = compose.onAllNodes(
            hasAnyAncestor(hasTestTag("tab_FREE")) or hasAnyAncestor(hasTestTag("tab_BACKUP")) or
                hasAnyAncestor(hasTestTag("tab_APPS")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().filter { androidx.compose.ui.semantics.SemanticsProperties.Text in it.config }
        assertEquals(3, texts.size)
        texts.forEach { node ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val r = results.single()
            val text = r.layoutInput.text
            assertTrue("\"$text\" is cut off", r.multiParagraph.intrinsics.maxIntrinsicWidth <= r.size.width + 1f)
        }
    }

    @Test
    fun `a busy trash shows the progress line, like Home and Backup`() {
        compose.setContent {
            KeepClearTheme { TrashScreen(OwnerPhone.trash, { _, _ -> }, { _, _ -> }, {}, busy = true) }
        }
        compose.onNodeWithTag("busy").assertExists()
    }

    @Test
    fun `an idle trash shows no progress line`() {
        compose.setContent {
            KeepClearTheme { TrashScreen(OwnerPhone.trash, { _, _ -> }, { _, _ -> }, {}, busy = false) }
        }
        compose.onNodeWithTag("busy").assertDoesNotExist()
    }
}
