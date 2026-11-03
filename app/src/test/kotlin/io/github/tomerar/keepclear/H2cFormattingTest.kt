// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Owner brief H2 item 10 (PR H2c): one order for a count with its size, „2,324 קבצים · 2.9GB”, everywhere; an empty
 * selection never shows "0B"; the duplicated About text is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H2cFormattingTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    /** The text without bidi marks and isolates, as a reader sees the characters in order. */
    private fun plain(s: String) = s.filterNot { it in "‎‏⁦⁧⁨⁩‪‫‬" }

    private fun text(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text }

    private fun total(t: Total) = compose.setContent {
        KeepClearTheme { Text(totalText(t), Modifier.testTag("t")) }
    }

    @Test
    fun `a count and its size read count first in English`() {
        total(Total(2_324, 2_900_000_000))
        assertEquals("2,324 files · 2.9 GB", plain(text("t")))
    }

    @Test
    @Config(qualifiers = "iw")
    fun `a count and its size read count first in Hebrew`() {
        total(Total(2_324, 2_900_000_000))
        assertEquals("2,324 קבצים · 2.9GB", plain(text("t")))
    }

    @Test
    @Config(qualifiers = "iw")
    fun `apps and the clean sheet's parts use the same order`() {
        compose.setContent {
            KeepClearTheme { Text(countAndSize("3 אפליקציות", "1.2GB"), Modifier.testTag("t")) }
        }
        assertEquals("3 אפליקציות · 1.2GB", plain(text("t")))
    }

    @Test
    fun `every string that pairs a count with a size puts the count first, never the size in parentheses`() {
        for (dir in listOf("values", "values-iw")) {
            val xml = File(root, "core/i18n/src/main/res/$dir/strings.xml").readText()
            // "%1$s files (%2$s)", „קובץ אחד (%2$s)”, "%3$d (%4$s)": the old size-after-count forms.
            val bad = Regex("(files?|קבצים|קובץ אחד|שני קבצים|%\\d\\\$[sd]) \\(%\\d\\\$s\\)").findAll(xml).toList()
            assertEquals(dir, emptyList<String>(), bad.map { it.value })
            assertFalse(dir, "confirm_part_value" in xml)
        }
        val en = context.resources
        assertEquals("%1\$s · %2\$s", en.getString(R.string.total_value, "%1\$s", "%2\$s"))
        assertTrue(en.getString(R.string.move_receipt_text, "d", "f", 7, "1 GB", 0).contains("checked: 7 · 1 GB"))
        assertEquals(
            "Copied 2 files · 1 GB. We checked each file: it was read again from the drive and matches the original.",
            en.getQuantityString(R.plurals.move_receipt_verified, 2, "2", "1 GB"),
        )
    }

    @Test
    fun `the one count-and-size resource is used only through countAndSize`() {
        val src = File(root, "app/src/main/kotlin")
        val users = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { "R.string.total_value" in it.readText() }
            .map { it.name }.toList()
        assertEquals(listOf("ReviewScreens.kt"), users)
        val review = File(src, "io/github/tomerar/keepclear/ReviewScreens.kt").readText()
        assertTrue(review.contains("stringResource(R.string.total_value, Bidi.isolate(count), Bidi.isolate(size))"))
    }

    private fun safeSummary(): ClassificationSummary {
        val c = Classifier()
        val now = 1_800_000_000_000L

        fun i(rel: String) = c.classify(
            ScanItem(KPath.Local("/storage/emulated/0/$rel"), "/storage/emulated/0/$rel", 1000, now - 1_000_000),
            now = now,
        )
        return ClassificationSummary.of(listOf(i("DCIM/.thumbnails/1.jpg"), i("Download/a.tmp")))
    }

    @Test
    fun `Clean now with nothing selected says so and never shows 0B`() {
        compose.setContent {
            KeepClearTheme {
                SafeScreen(
                    summary = safeSummary(),
                    result = null,
                    trimResult = null,
                    showExternalCacheClear = false,
                    onClean = { _, _ -> },
                    onUndo = { _, _ -> },
                    onTrim = {},
                    onClearExternalCaches = {},
                    onOpenGroup = {},
                    onOpenTrash = {},
                    onBack = {},
                )
            }
        }
        assertEquals("Selected: 2 files · 2.0 KB", plain(text("selectedTotal")))
        compose.onNodeWithTag("check_thumbnails").performClick()
        compose.onNodeWithTag("check_temp_files").performClick()
        assertEquals("Nothing selected", text("selectedTotal"))
        assertEquals("Clean", text("cleanButton"))
        compose.onNodeWithTag("cleanButton").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "iw")
    fun `the backup bar with nothing selected says so and never shows 0B`() {
        compose.setContent { KeepClearTheme { BackupBar(Total(), enabled = false, preparing = false, onClick = {}) } }
        assertEquals("לא נבחר כלום", text("moveSelected"))
        assertFalse(text("pickTarget").contains("0"))
    }

    @Test
    @Config(qualifiers = "iw")
    fun `the backup bar with a selection is count first`() {
        compose.setContent {
            KeepClearTheme { BackupBar(Total(2_324, 2_900_000_000), enabled = true, preparing = false, onClick = {}) }
        }
        assertEquals("נבחרו: 2,324 קבצים · 2.9GB", plain(text("moveSelected")))
    }

    @Test
    fun `the About card says no ads, no tracking only once`() {
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    testModeOn = true,
                    onTestModeOn = {},
                    onTestModeOff = {},
                    onBack = {},
                    actions = SettingsActions(version = "1.0"),
                )
            }
        }
        compose.onNodeWithTag("about").performScrollTo()
        val nodes = compose.onAllNodes(hasText("No ads", substring = true, ignoreCase = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, nodes.size)
    }

    private fun receipt(keepInTrash: Boolean) {
        val job = io.github.tomerar.keepclear.engine.move.MoveJob(
            id = "j",
            targetId = "t",
            targetLabel = "SanDisk",
            rootRel = "KeepClear/Pixel 8",
            createdAt = 1,
            state = io.github.tomerar.keepclear.engine.move.JobState.DONE,
        )
        val receipt = io.github.tomerar.keepclear.engine.move.Receipt(
            jobId = "j",
            targetLabel = "SanDisk",
            rootRel = "KeepClear/Pixel 8",
            verified = 2,
            verifiedBytes = 2_000,
            failed = emptyMap(),
            changed = 0,
            pending = 0,
            namesChanged = 0,
            confirmed = 2,
            confirmedBytes = 2_000,
        )
        compose.setContent {
            KeepClearTheme {
                ReceiptCard(
                    JobView(job, receipt, canReadBack = true),
                    onFree = {},
                    onShare = {},
                    keepInTrash = keepInTrash,
                )
            }
        }
    }

    @Test
    fun `the receipt names the backup folder in plain words, never a raw path`() {
        receipt(keepInTrash = false)
        val howBack = plain(text("howBack"))
        assertTrue(howBack, howBack.contains("in the folder KeepClear › Pixel 8."))
        assertFalse(howBack, "KeepClear/" in howBack)
    }

    @Test
    fun `Free up is said only when the bytes come back - with keep-in-trash the button says trash`() {
        receipt(keepInTrash = false)
        assertEquals("Free up 2.0 KB from the phone", plain(text("freeOriginals")))
    }

    @Test
    fun `with the keep-in-trash setting the free button says it moves to the trash`() {
        receipt(keepInTrash = true)
        assertEquals("Move 2.0 KB to the trash", plain(text("freeOriginals")))
    }

    private fun allTexts(): List<String> =
        compose.onAllNodes(androidx.compose.ui.test.isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { plain(it.text) } }

    @Test
    @Config(qualifiers = "w360dp-h2400dp")
    fun `the Trash names the drive of a checked copy, never the raw path, and counts first`() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        val entry = io.github.tomerar.keepclear.engine.trash.TrashEntry(
            id = "c",
            originalPath = "/storage/emulated/0/DCIM/Camera/IMG_2.jpg",
            trashedPath = "/storage/emulated/0/.KeepClearTrash/20261031/c/IMG_2.jpg",
            volumeRoot = "/storage/emulated/0",
            size = 3_000_000,
            mtimeMillis = 1,
            ruleId = "camera",
            reason = "MOVE_DELETE_ORIGINAL",
            deletedAt = now - day,
            expiresAt = now + 29 * day,
            deletedAtElapsed = 0,
            bootId = "b",
            state = io.github.tomerar.keepclear.engine.trash.TrashState.ACTIVE,
            verifiedCopy = "USB:KeepClear/Phone/IMG",
        )
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                TrashScreen(
                    TrashUi(listOf(entry), releasable = setOf("c")),
                    { _, _ -> },
                    { _, _ -> },
                    {},
                    now = now,
                    initialView = TrashView.LIST,
                )
            }
        }
        if (compose.onAllNodes(hasTestTag("verifiedCopy_c")).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("toggleGroup_MOVE").performScrollTo().performClick()
        }
        assertEquals("Checked copy on USB", plain(text("verifiedCopy_c")))
        val texts = allTexts()
        assertEquals(emptyList<String>(), texts.filter { "KeepClear/" in it || "USB:" in it })
        assertTrue(texts.toString(), texts.any { it.startsWith("1 file · 3.0 MB") || it.startsWith("1 file · 3 MB") })
    }
}
