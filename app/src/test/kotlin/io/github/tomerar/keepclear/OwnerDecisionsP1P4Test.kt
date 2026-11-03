// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.backup.saf.TargetKind
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.safety.Confirmation
import io.github.tomerar.keepclear.engine.move.FailReason
import io.github.tomerar.keepclear.engine.move.FreeCandidate
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.move.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner decisions P1–P4 (2026-11-03) on the current backup screen: the honest free confirmation with the drive's
 * name, the two pre-ticked "keep on the phone" options, the WhatsApp sentence, "Deleted 400 of 6,830", the result's
 * "Restore from a backup", the keep-in-trash setting and the battery warning and pause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OwnerDecisionsP1P4Test {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val cam = "/storage/emulated/0/DCIM/Camera"
    private val wa = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"
    private val job = MoveJob("j1", "t", "SanDisk", "KeepClear/OnePlus 10T", 1, JobState.DONE)

    private fun receipt(n: Int) = Receipt(
        "j1",
        "SanDisk",
        "KeepClear/OnePlus 10T",
        n,
        n * 1_000_000L,
        emptyMap<FailReason, Int>(),
        0,
        0,
        0,
        confirmed = n,
        confirmedBytes = n * 1_000_000L,
    )

    private val drive = io.github.tomerar.keepclear.core.common.Bidi.isolate("SanDisk")

    private val candidates = listOf(
        FreeCandidate("$cam/old.jpg", 1_000_000, now - 400 * day, favourite = false),
        FreeCandidate("$cam/fav.jpg", 1_000_000, now - 400 * day, favourite = true),
        FreeCandidate("$cam/recent.jpg", 1_000_000, now - 3 * day, favourite = false),
    )

    private var confirmed: Confirmation? = null
    private var restoreOpened = 0

    private fun show(
        list: List<FreeCandidate> = candidates,
        keepInTrash: Boolean = false,
        state: MoveUiState? = null,
    ) = compose.setContent {
        KeepClearTheme {
            MoveScreen(
                summary = null,
                state = state ?: MoveUiState(
                    jobs = listOf(JobView(job, receipt(list.size), canReadBack = true, freeCandidates = list)),
                    keepInTrash = keepInTrash,
                ),
                now = now,
                notificationsOff = false,
                actions = MoveActions(
                    onFree = { _, c -> confirmed = c },
                    restore = RestoreActions(onStart = { restoreOpened++ }),
                ),
                onBack = {},
            )
        }
    }

    private fun openFreeStep() {
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("freeOriginals"))
        compose.onNodeWithTag("freeOriginals").performClick()
        compose.onNodeWithTag("freeConfirm").assertExists()
    }

    private fun confirm() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmFree").fetchSemanticsNodes().any {
                SemanticsProperties.Disabled !in it.config
            }
        }
        compose.onNodeWithTag("confirmFree").performClick()
    }

    @Test
    fun `P1 the confirmation says honestly what happens, with the drive's name`() {
        show()
        openFreeStep()
        val sentence = context.getString(R.string.move_free_confirm_for_good, drive)
        compose.onNodeWithText(sentence, substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.free_where_for_good, drive), useUnmergedTree = true)
            .assertExists()
        // Not the trash wording.
        compose.onAllNodesWithTag("freeConfirm").fetchSemanticsNodes().single()
        assertEquals(
            0,
            compose.onAllNodes(
                androidx.compose.ui.test.hasText(context.getString(R.string.confirm_where_trash)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
    }

    @Test
    @Config(qualifiers = "iw")
    fun `P1 the Hebrew confirmation is the owner's sentence`() {
        val he = context.getString(R.string.move_free_confirm_for_good, "SanDisk")
        assertEquals(
            "המקור יימחק מהטלפון. העותק נשאר ב-SanDisk, נבדק קובץ-קובץ, ואפשר להחזיר ממנו בכל זמן.",
            he,
        )
        assertEquals("להשאיר בטלפון מועדפים", context.getString(R.string.free_keep_favourites))
        assertEquals("להשאיר בטלפון את 30 הימים האחרונים", context.getString(R.string.free_keep_recent))
        assertEquals("בצ'אט יופיע שהקובץ לא זמין; העותק שמור בגיבוי", context.getString(R.string.free_whatsapp))
        assertEquals("נמחקו 400 מתוך 6,830", context.getString(R.string.free_progress_deleted, "400", "6,830"))
        assertEquals(
            "לשמור את המקור גם בסל 30 יום (לא מפנה מקום)",
            context.getString(R.string.settings_keep_originals),
        )
        assertEquals(
            "כדאי לטעון קודם; הגיבוי ייעצר לבד ב-10% וימשיך אחר כך",
            context.getString(R.string.move_issue_battery_warning),
        )
    }

    @Test
    fun `P2 both keep options are ticked and the tap names only what they leave in`() {
        show()
        openFreeStep()
        compose.onNodeWithTag("keepFavourites").assertIsOn()
        compose.onNodeWithTag("keepRecent").assertIsOn()
        confirm()
        assertEquals(setOf("$cam/old.jpg"), confirmed!!.paths)
    }

    @Test
    fun `P2 unticking an option frees those files too`() {
        show()
        openFreeStep()
        compose.onNodeWithTag("keepFavourites")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("keepFavourites").assertIsOff()
        confirm()
        assertEquals(setOf("$cam/old.jpg", "$cam/fav.jpg"), confirmed!!.paths)
    }

    @Test
    fun `P2 a tap on the keep row unticks it`() {
        show()
        openFreeStep()
        // Robolectric's default window is small: the sheet scrolls, so the row is scrolled into view first.
        compose.onNodeWithTag("keepRecent").performScrollTo().performClick()
        compose.onNodeWithTag("keepRecent").assertIsOff()
    }

    @Test
    fun `P2 when the options keep everything, nothing is confirmed`() {
        show(list = candidates.drop(1))
        openFreeStep()
        compose.onNodeWithTag("freeNothing").assertExists()
        confirm()
        assertNull(confirmed)
    }

    @Test
    fun `P4 WhatsApp media in the plan adds the chat sentence, and only then`() {
        show(list = candidates + FreeCandidate("$wa/IMG-1.jpg", 1_000, now - 400 * day, favourite = false))
        openFreeStep()
        compose.onNodeWithTag("freeWhatsApp").assertExists()
        compose.onNodeWithText(context.getString(R.string.free_whatsapp), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `P4 no WhatsApp sentence without WhatsApp media, or when the options keep it`() {
        show(list = candidates + FreeCandidate("$wa/IMG-1.jpg", 1_000, now - 3 * day, favourite = false))
        openFreeStep()
        assertEquals(0, compose.onAllNodesWithTag("freeWhatsApp").fetchSemanticsNodes().size)
    }

    @Test
    fun `P1 with the keep-in-trash setting the sheet says trash, as before`() {
        show(keepInTrash = true)
        openFreeStep()
        compose.onNodeWithText(context.getString(R.string.confirm_where_trash), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.move_free_confirm_yes), useUnmergedTree = true)
            .assertExists()
        assertEquals(
            0,
            compose.onAllNodes(
                androidx.compose.ui.test.hasText(
                    context.getString(R.string.move_free_confirm_for_good, drive),
                    substring = true,
                ),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `P1 progress while freeing says how many were deleted`() {
        val task = TaskProgress(TaskKind.FREE, done = 420, total = 6_830, freeMode = FreeMode.FOR_GOOD, freed = 400)
        compose.setContent { KeepClearTheme { TaskProgressText(task) } }
        compose.onNodeWithTag("taskProgress").assertExists()
        val text = compose.onNodeWithTag("taskProgress").fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString { it.text }
        assertTrue(text, text.startsWith("Deleted ") && "400" in text && "6,830" in text)
    }

    @Test
    fun `P1 test mode progress says nothing is deleted`() {
        val task = TaskProgress(TaskKind.FREE, done = 3, total = 10, freeMode = FreeMode.TEST)
        compose.setContent { KeepClearTheme { TaskProgressText(task) } }
        val text = compose.onNodeWithTag("taskProgress").fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString { it.text }
        assertTrue(text, text.startsWith("Checked ") && "nothing is deleted" in text)
    }

    @Test
    fun `P1 the result after deleting for good offers Restore from a backup`() {
        val r = FreeResult("j1", 3, 3_000_000, 1, 0, forGood = true, targetLabel = "SanDisk")
        show(state = MoveUiState(freeResult = r))
        compose.onNodeWithTag("freeRestore").assertExists().performClick()
        assertEquals(1, restoreOpened)
        assertEquals(0, compose.onAllNodesWithTag("freeEmptyNow").fetchSemanticsNodes().size)
    }

    @Test
    fun `P1 in test mode the result says what would have been freed and offers nothing to undo`() {
        val r = FreeResult("j1", 3, 3_000_000, 0, 0, simulated = true, forGood = true, targetLabel = "SanDisk")
        show(state = MoveUiState(freeResult = r))
        compose.onNodeWithTag("simulatedResult").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("freeRestore").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("freeEmptyNow").fetchSemanticsNodes().size)
    }

    @Test
    fun `P1 the keep-in-trash switch is off by default and reports a change`() {
        var set: Boolean? = null
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(false, {}, {}, {}, SettingsActions(onKeepOriginalsInTrash = { set = it }))
            }
        }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("keepOriginalsSwitch"))
        compose.onNodeWithTag("keepOriginalsSwitch").assertIsOff().performClick()
        assertEquals(true, set)
    }

    @Test
    fun `P3 the battery warning is a note, not a block`() {
        val picked = PickedTarget(
            "SanDisk",
            TargetKind.REMOVABLE,
            true,
            emptyList(),
            listOf(MovePlanner.Issue.BatteryWarning),
        )
        assertEquals(false, picked.blocked)
        show(state = MoveUiState(picked = picked))
        compose.onNodeWithTag("preflightNote").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("preflightBlock").fetchSemanticsNodes().size)
        compose.onNodeWithText(context.getString(R.string.move_issue_battery_warning), substring = true)
            .assertExists()
    }

    @Test
    fun `P3 a job waiting for the battery says it goes on by itself`() {
        val paused = job.copy(state = JobState.PAUSED, pauseReason = PauseReason.BATTERY_LOW)
        compose.setContent {
            KeepClearTheme {
                MoveScreen(
                    summary = null,
                    state = MoveUiState(jobs = listOf(JobView(paused, receipt(0), canReadBack = true)), running = "j1"),
                    now = now,
                    notificationsOff = false,
                    actions = MoveActions(),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("moveBatteryWait").assertExists()
        assertNotNull(context.getString(R.string.move_paused_battery))
    }
}
