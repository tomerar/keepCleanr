// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.move.FailReason
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.move.Receipt
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review PR C: the UX pass for non-technical users. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UxPassTest {
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
            listOf(i("DCIM/.thumbnails/1.jpg"), i("Download/a.tmp"), i("DCIM/Camera/IMG.jpg")),
        )
    }

    private fun home(state: HomeUiState, actions: HomeActions = HomeActions()) = compose.setContent {
        KeepClearTheme { HomeScreen(state = state, showDebugTools = false, actions = actions) }
    }

    private fun text(id: Int) = androidx.test.core.app.ApplicationProvider
        .getApplicationContext<android.content.Context>().getString(id)

    // 1. ONB-1 / cards before a scan
    @Test
    fun `before a scan without access the cards say needs access, never coming soon`() {
        home(HomeUiState(mediaAccess = MediaAccess.NONE))
        compose.onNodeWithTag("pending_SAFE").assertExists()
        assertEquals(3, compose.onAllNodesWithText(text(R.string.home_card_needs_access)).fetchSemanticsNodes().size)
    }

    @Test
    fun `while scanning the cards say counting`() {
        home(HomeUiState(mediaAccess = MediaAccess.FULL, scan = ScanState.Running(10, 1000)))
        assertEquals(3, compose.onAllNodesWithText(text(R.string.home_card_counting)).fetchSemanticsNodes().size)
    }

    // 2. One primary action
    @Test
    fun `the SAFE card has the one filled Clean button and see details opens Clean now`() {
        var opened: Category? = null
        home(
            HomeUiState(mediaAccess = MediaAccess.FULL, summary = summary(), scan = ScanState.Done(3, 3000, true)),
            HomeActions(onOpenCategory = { opened = it }),
        )
        compose.onNodeWithTag("seeDetails").performScrollTo().performClick()
        assertEquals(Category.SAFE, opened)
        compose.onNodeWithTag("scanLine").assertExists()
    }

    // F1b: the main button opens the usual confirmation directly; nothing happens until it is confirmed.
    @Test
    fun `the main button goes straight to the SAFE confirmation and cleans only after it`() {
        var opened: Category? = null
        var cleaned: List<String>? = null
        home(
            HomeUiState(
                totalBytes = 100_000_000_000,
                freeBytes = 10_000_000_000,
                mediaAccess = MediaAccess.FULL,
                summary = summary(),
                scan = ScanState.Done(3, 3000, true),
            ),
            HomeActions(onOpenCategory = { opened = it }, onClean = {
                items,
                _,
                ->
                cleaned = items.map { it.item.path }
            }),
        )
        compose.onNodeWithTag("open_SAFE").performScrollTo().performClick()
        assertEquals(null, opened)
        assertEquals(null, cleaned)
        // SEC-7: the confirm button is the delayed, tapjacking-safe one.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmClean").fetchSemanticsNodes()
                .any { SemanticsProperties.Disabled !in it.config }
        }
        compose.onNodeWithTag("confirmClean").performClick()
        val safe = summary().groups(Category.SAFE).flatMap { it.items }.map { it.item.path }
        assertEquals(safe.sorted(), cleaned?.sorted())
    }

    // 3. ONB-3: temporary files card always, even with no permission
    @Test
    fun `clear apps' temporary files is on Home even without any permission`() {
        var trimmed = 0
        home(HomeUiState(mediaAccess = MediaAccess.NONE), HomeActions(onTrim = { trimmed++ }))
        compose.onNodeWithTag("homeTrim").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmHomeTrim").fetchSemanticsNodes()
                .any { SemanticsProperties.Disabled !in it.config }
        }
        compose.onNodeWithTag("confirmHomeTrim").performClick()
        assertEquals(1, trimmed)
    }

    // 14. 0-byte trim explained
    @Test
    fun `a trim that freed nothing explains why`() {
        home(HomeUiState(mediaAccess = MediaAccess.NONE, trimResult = 0))
        compose.onNodeWithTag("homeTrimResult").performScrollTo()
        compose.onNodeWithText(text(R.string.trim_result_zero)).assertExists()
    }

    // 4. Measured result, tied to its screen
    @Test
    fun `a measured result shows on the screen it came from`() {
        home(HomeUiState(measured = Measured(7_900_000_000, 24_700_000_000, 16_800_000_000, Screen.Home)))
        compose.onNodeWithTag("measured").assertIsDisplayed()
    }

    @Test
    fun `a measured result from another screen is not shown on Home`() {
        home(HomeUiState(measured = Measured(7_900_000_000, 24_700_000_000, 16_800_000_000, Screen.Trash)))
        compose.onNodeWithTag("measured").assertDoesNotExist()
    }

    @Test
    fun `a clean result and its Undo never show on another screen`() {
        val r = CleanResult(Total(1, 1000), 0, 0, listOf("/a"), origin = Screen.Group("screenshots", Category.REVIEW))
        val state = HomeUiState(
            summary = summary(),
            cleanResult = r,
            screens = listOf(Screen.Home, Screen.CategoryList(Category.SAFE)),
        )
        compose.setContent { KeepClearTheme { AppScreens(state = state, onOpen = {}, onBack = {}, home = {}) } }
        compose.onNodeWithTag("cleanResult").assertDoesNotExist()
    }

    // Bug found in review PR C: AppScreens dropped onCheck, so "Check backup" did nothing in the real app.
    @Test
    fun `Check backup reaches the ViewModel through AppScreens`() {
        var checked: String? = null
        val job = MoveJob("j1", "t", "SanDisk", "KeepClear/P", 1, JobState.DONE)
        val receipt = Receipt("j1", "SanDisk", "KeepClear/P", 2, 2000, emptyMap(), 0, 0, 0)
        val state = HomeUiState(summary = summary(), screens = listOf(Screen.Home, Screen.CategoryList(Category.MOVE)))
        compose.setContent {
            KeepClearTheme {
                AppScreens(
                    state = state,
                    onOpen = {},
                    onBack = {},
                    home = {},
                    move = MoveUiState(jobs = listOf(JobView(job, receipt, canReadBack = true))),
                    moveActions = MoveActions(onCheck = { checked = it }),
                )
            }
        }
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("checkBackup"))
        compose.onNodeWithTag("checkBackup").performClick()
        assertEquals("j1", checked)
    }

    // 6. No jargon on the default path
    @Test
    fun `the receipt keeps developer checks under Details`() {
        val job = MoveJob("j1", "t", "SanDisk", "KeepClear/P", 1, JobState.DONE)
        val receipt = Receipt("j1", "SanDisk", "KeepClear/P", 1, 1000, mapOf(FailReason.SOURCE_MISSING to 2), 0, 0, 0)
        compose.setContent { KeepClearTheme { ReceiptCard(JobView(job, receipt, true), onFree = {}, onShare = {}) } }
        compose.onNodeWithTag("detailsCheckPc").assertDoesNotExist()
        compose.onNodeWithTag("receiptDetails").performClick()
        compose.onNodeWithTag("detailsCheckPc").assertExists()
    }

    // 9. MOVE: Stop needs a confirm; the guide checks a drive is attached; preflight is a checklist
    @Test
    fun `Stop asks first, and Pause vs Stop is explained`() {
        var stopped = 0
        val job = MoveJob("j1", "t", "SanDisk", "KeepClear/P", 1, JobState.RUNNING)
        val receipt = Receipt("j1", "SanDisk", "KeepClear/P", 1, 1000, emptyMap(), 0, 3, 0)
        compose.setContent {
            KeepClearTheme {
                MoveScreen(
                    summary = summary(),
                    state = MoveUiState(
                        jobs = listOf(
                            JobView(job, receipt, true, totalBytes = 4000, doneBytes = 1000, etaMillis = 600_000),
                        ),
                        running = "j1",
                    ),
                    now = now,
                    notificationsOff = false,
                    actions = MoveActions(onStop = { stopped++ }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("moveProgress").assertExists()
        // H1b item 4: ONE progress line with the time left.
        compose.onNodeWithTag("moveProgress").assertTextContains("left", substring = true)
        compose.onNodeWithTag("stop").performClick()
        assertEquals(0, stopped)
        compose.onNodeWithTag("keepGoing").assertExists()
        // PR #48 review: one Stop button in the sheet, under Keep going (not stacked on it), and it stops.
        val stopLabel = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.move_stop_confirm_yes)
        // The screen's own Stop (tag "stop") stays behind the sheet; the sheet itself has exactly one.
        compose.onAllNodes(androidx.compose.ui.test.hasText(stopLabel) and !hasTestTag("stop")).assertCountEquals(1)
        val keep = compose.onNodeWithTag("keepGoing").fetchSemanticsNode().boundsInRoot
        val stop = compose.onNodeWithTag("confirmStop").fetchSemanticsNode().boundsInRoot
        assertTrue("Stop sits under Keep going", stop.top >= keep.bottom)
        compose.onNodeWithTag("confirmStop").performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun `the folder picker guide stops when no drive is attached`() {
        var picked = false
        compose.setContent {
            KeepClearTheme {
                MoveScreen(
                    summary = summary(),
                    state = MoveUiState(),
                    now = now,
                    notificationsOff = false,
                    actions = MoveActions(onPickTarget = { picked = true }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("source_camera"))
        compose.onNodeWithTag("source_camera").performClick()
        compose.onNodeWithTag("age_ALL").performClick()
        compose.onNodeWithTag("pickTarget").performClick()
        // H1b item 3: with no drive the sheet says to connect one, and the picker isn't opened.
        compose.onNodeWithTag("noDrive").assertExists()
        compose.onNodeWithTag("guideContinue").assertIsNotEnabled()
        assertEquals(false, picked)
        // Advisory: another place (e.g. a cloud folder) can still be chosen.
        compose.onNodeWithTag("guideAnyway").performClick()
        assertEquals(true, picked)
    }

    @Test
    fun `preflight is a checklist that names blockers in words`() {
        val picked = PickedTarget(
            "SanDisk",
            io.github.tomerar.keepclear.backup.saf.TargetKind.REMOVABLE,
            true,
            emptyList(),
            listOf(MovePlanner.Issue.BatteryLow, MovePlanner.Issue.NamesChanged(2)),
        )
        compose.setContent {
            KeepClearTheme {
                MoveScreen(summary(), MoveUiState(picked = picked), now, false, MoveActions(), onBack = {})
            }
        }
        assertEquals(1, compose.onAllNodesWithTag("preflightBlock").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("preflightNote").fetchSemanticsNodes().size)
        compose.onNodeWithTag("preflightOk").assertDoesNotExist()
    }

    // 5. Trash grouped by action, release-all, restore-all confirm, empty split
    private fun entry(id: String, rule: String?, verified: String? = null, reason: String = "TRASH") = TrashEntry(
        id = id,
        originalPath = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
        trashedPath = "/storage/emulated/0/.KeepClearTrash/20261031/$id/IMG_$id.jpg",
        volumeRoot = "/storage/emulated/0",
        size = 1_000_000,
        mtimeMillis = 1,
        ruleId = rule,
        reason = reason,
        deletedAt = 1,
        expiresAt = 2,
        deletedAtElapsed = 0,
        bootId = "b",
        state = TrashState.ACTIVE,
        verifiedCopy = verified,
    )

    @Test
    fun `trash rows are grouped by action with group and release-all actions`() {
        var restored: List<TrashEntry>? = null
        var purged: List<TrashEntry>? = null
        val moved = entry("m", "camera", verified = "SanDisk", reason = "MOVE_DELETE_ORIGINAL")
        val trash = TrashUi(listOf(entry("a", "thumbnails"), entry("b", "screenshots"), moved), releasable = setOf("m"))
        compose.setContent {
            KeepClearTheme {
                TrashScreen(trash, onRestore = { e, _ -> restored = e }, onPurge = { e, _ -> purged = e }, onBack = {})
            }
        }
        compose.onNodeWithTag("trashGroup_CLEAN").assertExists()
        compose.onNodeWithTag("trash").performScrollToNode(hasTestTag("trashGroup_MOVE"))
        compose.onNodeWithTag("trash").performScrollToNode(hasTestTag("releaseAll"))
        compose.onNodeWithTag("releaseAll").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmReleaseAll").fetchSemanticsNodes()
                .any { SemanticsProperties.Disabled !in it.config }
        }
        compose.onNodeWithTag("confirmReleaseAll").performClick()
        assertEquals(listOf(moved), purged)
        compose.onNodeWithTag("restoreAll").performClick()
        assertEquals(null, restored)
        compose.onNodeWithTag("confirmRestoreAll").performClick()
        assertEquals(3, restored!!.size)
        compose.onNodeWithTag("emptyNow").performClick()
        compose.onNodeWithTag("emptyBody").assertExists()
    }

    @Test
    fun `opening a large trash group composes only the rows on screen`() {
        val many = (0 until 500).map { entry("e$it", "thumbnails") }
        compose.setContent {
            KeepClearTheme { TrashScreen(TrashUi(many), onRestore = { _, _ -> }, onPurge = { _, _ -> }, onBack = {}) }
        }
        compose.onNodeWithTag("trash").performScrollToNode(hasTestTag("toggleGroup_CLEAN"))
        compose.onNodeWithTag("toggleGroup_CLEAN").performClick()
        compose.onNodeWithTag("trash").performScrollToNode(hasTestTag("entry_e0"))
        val composed = (0 until 500).count {
            compose.onAllNodesWithTag("entry_e$it").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("composed $composed of 500", composed in 1..50)
    }

    // 12. Settings
    @Test
    fun `settings has language, how to get files back, about, privacy and replay intro`() {
        var lang: String? = "x"
        var replay = 0
        var privacy = 0
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    testModeOn = true,
                    onTestModeOn = {},
                    onTestModeOff = {},
                    onBack = {},
                    actions = SettingsActions(
                        onLanguage = { lang = it },
                        onReplayIntro = { replay++ },
                        onOpenPrivacy = { privacy++ },
                        version = "0.1.0",
                    ),
                )
            }
        }
        compose.onNodeWithTag("lang_iw").performClick()
        assertEquals("iw", lang)
        compose.onNodeWithTag("getBack").assertExists()
        compose.onNodeWithTag("replayIntro").performScrollTo().performClick()
        compose.onNodeWithTag("privacy").performScrollTo().performClick()
        assertEquals(1, replay)
        assertEquals(1, privacy)
    }

    // 1. ONB-1 intro
    @Test
    fun `the intro has three screens and then starts the app`() {
        var done = 0
        compose.setContent { KeepClearTheme { IntroScreen(onDone = { done++ }) } }
        compose.onNodeWithTag("introNext").performClick()
        compose.onNodeWithTag("introNext").performClick()
        assertEquals(0, done)
        compose.onNodeWithTag("introSkip").assertDoesNotExist()
        compose.onNodeWithTag("introNext").performClick()
        assertEquals(1, done)
    }

    @Test
    fun `SAFE rows toggle from the text too (one touch target)`() {
        compose.setContent {
            KeepClearTheme {
                SafeScreen(
                    summary = summary(),
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
        val tag = compose.onAllNodesWithTag("check_thumbnails").fetchSemanticsNodes().single()
        assertTrue(tag.config.contains(SemanticsProperties.ToggleableState))
    }
}
