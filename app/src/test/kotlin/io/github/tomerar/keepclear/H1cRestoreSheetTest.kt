// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.INFO_SHEET_BODY_TAG
import io.github.tomerar.keepclear.core.designsystem.component.PANEL_SHEET_BODY_TAG
import io.github.tomerar.keepclear.engine.restore.RestoreReport
import io.github.tomerar.keepclear.engine.restore.RoomCheck
import io.github.tomerar.keepclear.engine.restore.Space
import io.github.tomerar.keepclear.engine.restore.Where
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Owner brief H1 item 9 (PR H1c): "Restore from a backup" opens as a sheet (never a plain dialog), shows the files
 * by month with search, restores one file alone, says where files go, offers what fits when the phone is full, and
 * the "needs All files access" case has a button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1600dp")
class H1cRestoreSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val files = listOf(
        RestoreFile("Camera/IMG_beach.jpg", "Camera", "2021-07", at(2021, 7, 12), 4_000_000, Where.ORIGINAL),
        RestoreFile("Camera/IMG_cake.jpg", "Camera", "2021-07", at(2021, 7, 20), 3_000_000, Where.ORIGINAL),
        RestoreFile("Camera/VID_snow.mp4", "Camera", "2022-12", at(2022, 12, 1), 900_000_000, Where.RESTORE_FOLDER),
    )
    private val groups = listOf(
        RestoreGroup("Camera", "2021-07", 7_000_000, toOriginal = 2, toRestoreFolder = 0),
        RestoreGroup("Camera", "2022-12", 900_000_000, toOriginal = 0, toRestoreFolder = 1),
    )
    private val plenty = Space(allocatable = 50_000_000_000, total = 128_000_000_000)
    private val catalog = RestoreStep.Catalog("KeepClear/Phone", groups, plenty, files = files)

    private var restored: Set<String>? = null
    private val thumbnailCalls = mutableListOf<String>()

    private val loader = object : ThumbnailLoader(null) {
        override suspend fun loadDocument(uri: android.net.Uri, sizePx: Int): Bitmap? = null
    }

    private fun actions(
        onAllFiles: (() -> Unit)? = null,
        onStart: () -> Unit = {},
        onChooseAgain: () -> Unit = {},
    ) = RestoreActions(
        onStart = onStart,
        onRestore = { restored = it },
        onAllFilesAccess = onAllFiles,
        onChooseAgain = onChooseAgain,
        thumbnail = { _, rel, px ->
            thumbnailCalls += rel
            Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        },
    )

    private fun show(step: RestoreStep, actions: RestoreActions = actions()) = compose.setContent {
        KeepClearTheme {
            CompositionLocalProvider(LocalThumbnailLoader provides loader) { RestoreDialogs(step, actions) }
        }
    }

    private fun text(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString { it.text }

    /** A sheet of the design system: its body tag, and the title as the pane TalkBack announces. */
    private fun assertSheet(bodyTag: String, title: String) {
        compose.onNodeWithTag(bodyTag).assertExists()
        val panes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.PaneTitle] }
        assertTrue("pane titles $panes", title in panes)
    }

    @Test
    fun `every step of the restore is a sheet with its title as the pane, not a plain dialog`() {
        val steps = listOf(
            RestoreStep.NeedsAllFiles to (INFO_SHEET_BODY_TAG to "Restore from a backup"),
            RestoreStep.NoBackup to (INFO_SHEET_BODY_TAG to "Restore from a backup"),
            RestoreStep.Backups(listOf("KeepClear/A", "KeepClear/B")) to
                (INFO_SHEET_BODY_TAG to "Which phone's backup?"),
            RestoreStep.Running(1, 4) to (INFO_SHEET_BODY_TAG to "Restoring…"),
            RestoreStep.Done(
                RestoreReport(restored = listOf("/a")),
            ) to (INFO_SHEET_BODY_TAG to "Restore from a backup"),
            catalog to (PANEL_SHEET_BODY_TAG to "What to restore"),
        )
        val state = androidx.compose.runtime.mutableStateOf<RestoreStep>(steps.first().first)
        compose.setContent { KeepClearTheme { RestoreDialogs(state.value, RestoreActions()) } }
        for ((step, expected) in steps) {
            state.value = step
            compose.waitForIdle()
            assertSheet(expected.first, expected.second)
        }
    }

    @Test
    fun `the catalog opens as a grid by month, nothing is chosen, a tap chooses one file`() {
        show(catalog)
        compose.onNodeWithTag("restoreRun").assertIsNotEnabled()
        compose.onNodeWithTag("monthHeader_2022-12").assertExists()
        compose.onNodeWithTag("monthHeader_2021-07").assertExists()
        compose.onNodeWithTag("file_Camera/IMG_cake.jpg").performClick()
        assertEquals(
            true,
            compose.onNodeWithTag("file_Camera/IMG_cake.jpg").fetchSemanticsNode().config[SemanticsProperties.Selected],
        )
        compose.onNodeWithTag("restoreRun").assertIsEnabled().performClick()
        assertEquals(setOf(RestoreFile.key("Camera/IMG_cake.jpg")), restored)
    }

    @Test
    fun `where the files go is said in plain words, with the restore folder's real name`() {
        show(catalog)
        assertTrue("restore folder, Pictures › KeepClear Restored" in text("restoreDestination"))
    }

    @Test
    @Config(qualifiers = "iw-w360dp-h1600dp")
    fun `in Hebrew the files go to the restore folder by its name`() {
        show(catalog)
        val where = text("restoreDestination")
        assertTrue(where, "לתיקיית השחזורים" in where && "KeepClear Restored" in where)
    }

    @Test
    fun `a file's TalkBack actions restore it alone or preview it, and the preview restores one`() {
        show(catalog)
        val tile = compose.onNodeWithTag("file_Camera/IMG_beach.jpg").fetchSemanticsNode().config
        val description = tile[SemanticsProperties.ContentDescription].single()
        listOf("IMG_beach.jpg", "2021", "4.0 MB").forEach { assertTrue("$it in $description", it in description) }
        val custom = tile[SemanticsActions.CustomActions]
        assertEquals(listOf("Restore this file", "Preview"), custom.map { it.label })
        custom.first().action()
        assertEquals(setOf(RestoreFile.key("Camera/IMG_beach.jpg")), restored)

        restored = null
        custom.last().action()
        compose.waitForIdle()
        assertEquals("Back to where they were", text("restoreFileWhere"))
        compose.onNodeWithTag("restoreOne").performClick()
        assertEquals(setOf(RestoreFile.key("Camera/IMG_beach.jpg")), restored)
    }

    @Test
    fun `a long press previews a file`() {
        show(catalog)
        compose.onNodeWithTag("file_Camera/VID_snow.mp4").performTouchInput { longClick() }
        compose.onNodeWithTag("restoreFileSheet").assertExists()
    }

    @Test
    fun `search keeps the files whose name or date matches`() {
        show(catalog)
        compose.onNodeWithTag("restoreSearch").performTextInput("july 2021")
        assertTrue(compose.onAllNodesWithTag("file_Camera/VID_snow.mp4").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("file_Camera/IMG_cake.jpg").assertExists()
        compose.onNodeWithTag("restoreSearch").performTextInput("zzz")
        compose.onNodeWithTag("restoreNoMatch").assertExists()
    }

    @Test
    fun `the months are still there, one tap away`() {
        show(catalog)
        compose.onNodeWithTag("restoreViewToggle").performClick()
        compose.onNodeWithTag("restore_Camera|2021-07").performClick()
        compose.onNodeWithTag("restoreRun").performClick()
        assertEquals(setOf("Camera|2021-07"), restored)
    }

    @Test
    fun `when the phone is short of room, the newest files that fit can be restored now (D1, C136)`() {
        // Room for 10 MB above the 1 GB reserve: the two July photos fit, the December video doesn't.
        val tight = catalog.copy(space = Space(allocatable = 1_010_000_000, total = 16_000_000_000))
        show(tight)
        compose.onNodeWithTag("restoreViewToggle").performClick()
        groups.forEach { compose.onNodeWithTag("restore_${it.key}").performClick() }
        compose.onNodeWithTag("restoreRun").assertIsNotEnabled()
        compose.onNodeWithTag("restoreFit").performScrollTo()
        assertEquals(
            "There is room for 2 of 3 files.",
            text("restorePartial").filter {
                it.code > 0x2000 ||
                    it.isLetterOrDigit() ||
                    it in " ."
            }.replace(Regex("[⁦-⁩]"), ""),
        )
        compose.onNodeWithTag("restoreFit").performClick()
        assertEquals(setOf(RestoreFile.key("Camera/IMG_beach.jpg"), RestoreFile.key("Camera/IMG_cake.jpg")), restored)
    }

    @Test
    fun `when nothing fits there is no partial offer and nothing can start`() {
        val full = catalog.copy(space = Space(allocatable = 1_000_000_000, total = 16_000_000_000))
        show(full)
        compose.onNodeWithTag("file_Camera/IMG_cake.jpg").performClick()
        compose.onNodeWithTag("restoreRun").assertIsNotEnabled()
        compose.onNodeWithTag("restoreTooBig").performScrollTo()
        compose.onNodeWithTag("restoreFit").assertDoesNotExist()
    }

    @Test
    fun `a full phone after starting is not a dead end, it offers to choose again`() {
        var again = 0
        show(
            RestoreStep.Done(RestoreReport(notEnoughRoom = RoomCheck(5_000_000_000, plenty))),
            actions(onChooseAgain = {
                again++
            }),
        )
        compose.onNodeWithTag("restoreChooseAgain").performClick()
        assertEquals(1, again)
    }

    @Test
    fun `needs All files access offers the settings screen, where the build can have it`() {
        var opened = 0
        show(RestoreStep.NeedsAllFiles, actions(onAllFiles = { opened++ }))
        compose.onNodeWithTag("restoreAllFilesSettings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the play build has no settings button for All files access`() {
        show(RestoreStep.NeedsAllFiles, actions(onAllFiles = null))
        compose.onNodeWithTag("restoreAllFilesSettings").assertDoesNotExist()
        compose.onNodeWithTag("restoreClose").assertExists()
    }

    @Test
    fun `no backup found offers one next step, another place`() {
        var started = 0
        show(RestoreStep.NoBackup, actions(onStart = { started++ }))
        compose.onNodeWithTag("restorePickAgain").performClick()
        assertEquals(1, started)
    }

    @Test
    fun `pictures come only from a drive on the phone and elsewhere nothing is read for them`() {
        val step = androidx.compose.runtime.mutableStateOf(catalog.copy(thumbnails = false))
        compose.setContent {
            KeepClearTheme {
                CompositionLocalProvider(LocalThumbnailLoader provides loader) { RestoreDialogs(step.value, actions()) }
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), thumbnailCalls)

        step.value = catalog.copy(thumbnails = true)
        compose.waitForIdle()
        // Photos and videos only, by their path in the backup.
        assertEquals(
            setOf(
                "KeepClear/Phone/Camera/IMG_beach.jpg",
                "KeepClear/Phone/Camera/IMG_cake.jpg",
                "KeepClear/Phone/Camera/VID_snow.mp4",
            ),
            thumbnailCalls.toSet(),
        )
    }

    @Test
    fun `a drive's document is named without listing folders, only for Android's own file provider`() {
        val resolver = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>().contentResolver
        val caps = io.github.tomerar.keepclear.backup.TargetCaps(canReadBack = true)

        fun target(tree: String) =
            io.github.tomerar.keepclear.backup.saf.SafBackupTarget(
                resolver,
                android.net.Uri.parse(tree),
                "t",
                "USB",
                caps,
            )

        fun id(uri: android.net.Uri?) = uri?.let { android.provider.DocumentsContract.getDocumentId(it) }

        val drive = target("content://com.android.externalstorage.documents/tree/1234-ABCD%3A")
        assertEquals(
            "1234-ABCD:KeepClear/Phone/Camera/a.jpg",
            id(drive.localDocumentUri("KeepClear/Phone/Camera/a.jpg")),
        )
        val folder = target("content://com.android.externalstorage.documents/tree/1234-ABCD%3ABackups")
        assertEquals("1234-ABCD:Backups/KeepClear/P/a.jpg", id(folder.localDocumentUri("KeepClear/P/a.jpg")))
        // SEC-3: a name from the (untrusted) manifest never climbs out of the tree.
        assertEquals(null, drive.localDocumentUri("KeepClear/../../secret.jpg"))
        assertEquals(null, drive.localDocumentUri(""))
        assertEquals(null, target("content://com.example.cloud/tree/root").localDocumentUri("KeepClear/P/a.jpg"))
    }
}
