// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.tomerar.keepclear.backup.saf.TargetKind
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.move.FailReason
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.move.Receipt
import io.github.tomerar.keepclear.engine.scan.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M10: MOVE selection (MOVE-1, SAFETY-10), receipt (MOVE-9/14/18, Q12), confirm before freeing (MOVE-10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MoveScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun summary(): ClassificationSummary {
        val c = Classifier()

        fun cam(name: String, t: Long, favourite: Boolean = false) = c.classify(
            ScanItem(
                KPath.Local("/storage/emulated/0/DCIM/Camera/$name"),
                "/storage/emulated/0/DCIM/Camera/$name",
                1_000_000,
                t,
                isFavorite = favourite,
            ),
            now = now,
        )
        return ClassificationSummary.of(
            listOf(
                cam("old.jpg", now - 400 * day),
                cam("older.jpg", now - 800 * day),
                cam("recent.jpg", now - 100 * day),
                cam("fav.jpg", now - 900 * day, favourite = true),
            ),
        )
    }

    private val job = MoveJob("j1", "t", "SanDisk", "KeepClear/OnePlus 10T", 1, JobState.DONE)

    private fun receipt(
        verified: Int = 3,
        pending: Int = 0,
        failed: Map<FailReason, Int> = emptyMap(),
        confirmed: Int = verified,
    ) = Receipt(
        "j1",
        "SanDisk",
        "KeepClear/OnePlus 10T",
        verified,
        verified * 1_000_000L,
        failed,
        0,
        pending,
        0,
        confirmed = confirmed,
        confirmedBytes = confirmed * 1_000_000L,
    )

    private var picked: List<ClassifiedItem>? = null
    private var freed: String? = null

    private fun show(state: MoveUiState = MoveUiState(), notificationsOff: Boolean = false) = compose.setContent {
        KeepClearTheme {
            MoveScreen(
                summary = summary(),
                state = state,
                now = now,
                notificationsOff = notificationsOff,
                actions = MoveActions(onPickTarget = { picked = it }, onFree = { freed = it }),
                onBack = {},
            )
        }
    }

    @Test
    fun `nothing is pre-selected and the default age skips the last 12 months and favourites`() {
        show()
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("pickTarget"))
        compose.onNodeWithTag("pickTarget").assertIsNotEnabled()
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("source_camera"))
        compose.onNodeWithTag("source_camera").performClick()
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("pickTarget"))
        compose.onNodeWithTag("pickTarget").assertIsEnabled().performClick()
        assertEquals(setOf("old.jpg", "older.jpg"), picked!!.map { it.item.path.substringAfterLast('/') }.toSet())
    }

    @Test
    fun `everything includes recent files but never favourites`() {
        val items = moveSelection(summary(), setOf("camera"), MoveAge.ALL, now)
        assertEquals(
            setOf("old.jpg", "older.jpg", "recent.jpg"),
            items.map {
                it.item.path.substringAfterLast('/')
            }.toSet(),
        )
        assertEquals(2, moveSelection(summary(), setOf("camera"), MoveAge.OLDER_6, now).size)
    }

    @Test
    fun `receipt shows how to get files back, warns it is unencrypted, and frees only after confirming`() {
        show(MoveUiState(jobs = listOf(JobView(job, receipt(), canReadBack = true))))
        compose.onNodeWithTag("receiptVerified").assertExists()
        compose.onNodeWithTag("howBack").assertExists()
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("unencrypted"))
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("freeOriginals"))
        compose.onNodeWithTag("freeOriginals").performClick()
        assertNull(freed)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmFree").fetchSemanticsNodes().any {
                SemanticsProperties.Disabled !in
                    it.config
            }
        }
        compose.onNodeWithTag("confirmFree").performClick()
        assertEquals("j1", freed)
    }

    @Test
    fun `no free button when the copy can't be read back (MOVE-14) or files are still pending (MOVE-9)`() {
        show(MoveUiState(jobs = listOf(JobView(job, receipt(), canReadBack = false))))
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("freeUnavailable"))
        compose.onAllNodesWithTag("freeOriginals").fetchSemanticsNodes().size.let { assertEquals(0, it) }
    }

    @Test
    fun `a copy checked only once offers Check backup, never Free (H1)`() {
        var checked: String? = null
        val view = JobView(job, receipt(confirmed = 0), canReadBack = true)
        assertEquals(false, view.canFree)
        assertEquals(true, view.needsCheck)
        compose.setContent {
            KeepClearTheme {
                MoveScreen(
                    summary = summary(),
                    state = MoveUiState(jobs = listOf(view)),
                    now = now,
                    notificationsOff = false,
                    actions = MoveActions(onFree = { freed = it }, onCheck = { checked = it }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("checkBackup"))
        compose.onAllNodesWithTag("freeOriginals").fetchSemanticsNodes().size.let { assertEquals(0, it) }
        compose.onNodeWithTag("checkBackup").performClick()
        assertEquals("j1", checked)
    }

    @Test
    fun `pending files hide the free button`() {
        val view = JobView(job, receipt(pending = 2), canReadBack = true)
        assertEquals(false, view.canFree)
        assertEquals(true, JobView(job, receipt(), canReadBack = true).canFree)
        assertEquals(
            false,
            JobView(job, receipt(verified = 0, failed = mapOf(FailReason.HASH_MISMATCH to 1)), true).canFree,
        )
    }

    @Test
    fun `failed files are listed with their reason`() {
        show(
            MoveUiState(
                jobs = listOf(JobView(job, receipt(failed = mapOf(FailReason.TOO_LARGE_FOR_TARGET to 2)), true)),
            ),
        )
        compose.onNodeWithTag("failed_TOO_LARGE_FOR_TARGET").assertExists()
    }

    @Test
    fun `a paused job offers Continue`() {
        val paused = job.copy(state = JobState.PAUSED, pauseReason = PauseReason.TARGET_DISCONNECTED)
        show(MoveUiState(jobs = listOf(JobView(paused, receipt(pending = 3), true))))
        compose.onNodeWithTag("resume").assertIsEnabled()
    }

    @Test
    fun `notifications-off warning appears while a job runs`() {
        val running = job.copy(state = JobState.RUNNING)
        show(
            MoveUiState(jobs = listOf(JobView(running, receipt(pending = 3), true)), running = "j1"),
            notificationsOff = true,
        )
        compose.onNodeWithTag("notifWarning").assertExists()
        compose.onNodeWithTag("jobRunning").assertExists()
    }

    @Test
    fun `a blocking pre-flight issue disables Start`() {
        val picked = PickedTarget(
            "SanDisk",
            TargetKind.REMOVABLE,
            true,
            emptyList(),
            listOf(MovePlanner.Issue.NotEnoughSpace(10_000, 5_000)),
        )
        show(MoveUiState(picked = picked))
        compose.onNodeWithTag("startCopy").assertIsNotEnabled()
    }

    @Test
    fun `a cloud target says originals stay`() {
        val picked =
            PickedTarget(
                "Drive",
                TargetKind.CLOUD_COPY_ONLY,
                false,
                emptyList(),
                listOf(MovePlanner.Issue.CannotVerify),
            )
        show(MoveUiState(picked = picked))
        compose.onNodeWithTag("startCopy").assertIsEnabled()
        compose.onNodeWithTag("preflight").assertExists()
    }

    @Test
    fun `an unfinished older job in history keeps its Continue button`() {
        val done = JobView(job.copy(id = "j2", createdAt = 2), receipt(), true)
        val paused = JobView(
            job.copy(state = JobState.PAUSED, pauseReason = PauseReason.PAUSED_BY_USER),
            receipt(pending = 2),
            true,
        )
        show(MoveUiState(jobs = listOf(done, paused)))
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("resume"))
        compose.onNodeWithTag("resume").assertIsEnabled()
    }

    @Test
    fun `a lost selection after the picker says so`() {
        show(MoveUiState(problem = TargetProblem.SELECTION_LOST))
        compose.onNodeWithTag("targetProblem").assertExists()
    }
}
