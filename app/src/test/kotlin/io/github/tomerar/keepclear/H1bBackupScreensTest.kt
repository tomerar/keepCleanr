// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.move.CheckResult
import io.github.tomerar.keepclear.engine.move.FailReason
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.move.Receipt
import io.github.tomerar.keepclear.engine.scan.ScanItem
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PR H1b: the backup screens (owner brief H1 items 1-8, 10, 11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H1bBackupScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun summary(): ClassificationSummary {
        val c = Classifier()
        return ClassificationSummary.of(
            (1..30).map { i ->
                val path = "/storage/emulated/0/DCIM/Camera/IMG_$i.jpg"
                c.classify(ScanItem(KPath.Local(path), path, 1_000_000_000, now - (400 + i) * day), now = now)
            },
        )
    }

    private fun job(id: String = "j1", state: JobState = JobState.DONE, reason: PauseReason? = null) =
        MoveJob(id, "t", "SanDisk", "KeepClear/OnePlus 10T", 1, state, reason)

    private fun receipt(
        verified: Int = 3,
        confirmed: Int = verified,
        failed: Map<FailReason, Int> = emptyMap(),
        pending: Int = 0,
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

    private var checked: String? = null
    private var resumed: String? = null

    private fun show(state: MoveUiState, onBack: (() -> Unit)? = null) = compose.setContent {
        KeepClearTheme {
            MoveScreen(
                summary = summary(),
                state = state,
                now = now,
                notificationsOff = false,
                actions = MoveActions(onCheck = { checked = it }, onResume = { resumed = it }),
                onBack = onBack,
            )
        }
    }

    // ---------------------------------------------------------------- items 1, 2

    @Test
    fun `item 1 the tab is called Backup and says that it only copies`() {
        show(MoveUiState())
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("onlyCopies"))
        compose.onNodeWithTag("onlyCopies")
            .assertTextEquals("At this step KeepClear only copies. No photo is deleted.")
        assertEquals("Backup", ctx().getString(io.github.tomerar.keepclear.core.i18n.R.string.move_title))
    }

    @Test
    @Config(qualifiers = "iw")
    fun `item 1 Hebrew heading and line are the owner's words`() {
        val c = ctx()
        assertEquals("גיבוי", c.getString(io.github.tomerar.keepclear.core.i18n.R.string.move_title))
        assertEquals(
            "בשלב הזה רק מעתיקים. שום תמונה לא נמחקת.",
            c.getString(io.github.tomerar.keepclear.core.i18n.R.string.backup_only_copies),
        )
    }

    @Test
    @Config(sdk = [36], fontScale = 2.0f)
    fun `item 2 the primary button is on screen at 200 percent with a long list, with its size`() {
        val history = (1..8).map { JobView(job("h$it"), receipt(), canReadBack = true) }
        show(MoveUiState(jobs = history))
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("source_camera"))
        compose.onNodeWithTag("source_camera").performClick()
        // Never below the fold: displayed without scrolling to it, inside the window.
        compose.onNodeWithTag("pickTarget").assertIsDisplayed().assertIsEnabled()
        val button = compose.onNodeWithTag("pickTarget").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("moveScreen").fetchSemanticsNode().boundsInRoot
        assertTrue("button under the list, not inside it", button.top >= root.bottom - 1)
        val label = compose.onNodeWithTag("pickTarget").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { Bidi.strip(it.text) }
        assertTrue(label, label.contains("30.0 GB"))
    }

    @Test
    fun `item 2 while a job runs the selection and the button are hidden`() {
        val running = job(state = JobState.RUNNING)
        show(
            MoveUiState(
                jobs = listOf(JobView(running, receipt(pending = 2), true, totalBytes = 10, doneBytes = 5)),
                running = "j1",
            ),
        )
        compose.onNodeWithTag("pickTarget").assertDoesNotExist()
        compose.onNodeWithTag("source_camera").assertDoesNotExist()
        compose.onNodeWithTag("jobRunning").assertExists()
    }

    @Test
    fun `item 2 while a check or free runs the selection is hidden too`() {
        show(MoveUiState(task = TaskProgress(TaskKind.CHECK)))
        compose.onNodeWithTag("pickTarget").assertDoesNotExist()
        compose.onNodeWithTag("source_camera").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- item 3

    @Test
    fun `item 3 the guide says to connect the drive, and shows Found the moment it is plugged in`() {
        val drive = mutableStateOf<RemovableDrive?>(null)
        var picked = 0
        compose.setContent {
            KeepClearTheme {
                MoveScreen(
                    summary = summary(),
                    state = MoveUiState(drive = drive.value),
                    now = now,
                    notificationsOff = false,
                    actions = MoveActions(onPickTarget = { picked++ }),
                    onBack = null,
                )
            }
        }
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("source_camera"))
        compose.onNodeWithTag("source_camera").performClick()
        compose.onNodeWithTag("pickTarget").performClick()
        compose.onNodeWithTag("noDrive").assertTextEquals("Connect the USB drive to the phone")
        compose.onNodeWithTag("guideContinue").assertIsNotEnabled()
        drive.value = RemovableDrive("1234-ABCD", "SanDisk USB drive", mounted = true)
        compose.waitForIdle()
        compose.onNodeWithTag("noDrive").assertDoesNotExist()
        val found = compose.onNodeWithTag("driveFound").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { Bidi.strip(it.text) }
        assertEquals("Found ✓ SanDisk USB drive", found)
        compose.onNodeWithTag("guideContinue").assertIsEnabled().performClick()
        assertEquals(1, picked)
    }

    @Test
    fun `item 3 the watcher follows the volume source live`() {
        val source = FakeVolumeSource()
        val watcher = DriveWatcher(source)
        watcher.start()
        assertEquals(null, watcher.mounted)
        source.set(RemovableDrive("AB12-CD34", "USB", mounted = true))
        assertEquals("AB12-CD34", watcher.mounted?.uuid)
        assertEquals(true, watcher.isMounted("ab12-cd34"))
        source.set(RemovableDrive("AB12-CD34", "USB", mounted = false))
        assertEquals(null, watcher.mounted)
        assertEquals(false, watcher.isMounted("AB12-CD34"))
        source.set()
        assertEquals(false, watcher.isMounted("AB12-CD34")) // removed counts as gone
        assertEquals(null, watcher.isMounted(null)) // no volume: unknown
        watcher.stop()
        assertTrue(source.closed)
        // A source that throws never crashes the screen.
        val broken = DriveWatcher(
            object : VolumeSource {
                override fun removable(): List<RemovableDrive> = error("no")

                override fun watch(onChange: () -> Unit): AutoCloseable = error("no")
            },
        )
        broken.start()
        assertEquals(emptyList<RemovableDrive>(), broken.drives.value)
    }

    // ---------------------------------------------------------------- item 4, 5 (screen side)

    @Test
    fun `item 4 backup mode guidance and ONE progress line with the phase as a label`() {
        val running = job(state = JobState.RUNNING)
        show(
            MoveUiState(
                jobs = listOf(
                    JobView(
                        running,
                        receipt(pending = 2),
                        true,
                        totalBytes = 31_000_000_000,
                        doneBytes = 12_900_000_000,
                        etaMillis = 41 * 60_000L,
                    ),
                ),
                running = "j1",
            ),
        )
        compose.onNodeWithTag("backupMode").assertTextEquals(
            "You can keep using the phone · Don't unplug the USB drive · Best to connect the charger",
        )
        compose.onNodeWithTag("movePhase").assertTextEquals("Copying")
        val line = compose.onNodeWithTag("moveProgress").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { Bidi.strip(it.text) }
        assertEquals("12.9 of 31.0 GB · about 41 minutes left", line)
    }

    @Test
    @Config(qualifiers = "iw")
    fun `item 4 the Hebrew progress line is the owner's format`() {
        val running = job(state = JobState.RUNNING)
        show(
            MoveUiState(
                jobs = listOf(
                    JobView(
                        running,
                        receipt(pending = 2),
                        true,
                        totalBytes = 31_000_000_000,
                        doneBytes = 12_900_000_000,
                        etaMillis = 41 * 60_000L,
                    ),
                ),
                running = "j1",
            ),
        )
        val line = compose.onNodeWithTag("moveProgress").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { Bidi.strip(it.text) }
        assertEquals("12.9 מתוך 31.0GB · נשארו כ-41 דקות", line)
    }

    @Test
    fun `item 4 a network place doesn't ask to keep a USB drive plugged in`() {
        val running = job(state = JobState.RUNNING)
        show(
            MoveUiState(
                jobs = listOf(JobView(running, receipt(pending = 2), true, totalBytes = 10, network = true)),
                running = "j1",
            ),
        )
        compose.onNodeWithTag("backupMode")
            .assertTextEquals("You can keep using the phone · Best to connect the charger")
    }

    @Test
    fun `item 5 TalkBack hears the progress in steps of about 10 percent`() {
        val done = mutableStateOf(0L)
        compose.setContent {
            KeepClearTheme {
                MoveScreen(
                    summary = summary(),
                    state = MoveUiState(
                        jobs = listOf(
                            JobView(
                                job(state = JobState.RUNNING),
                                receipt(pending = 2),
                                true,
                                totalBytes = 1_000,
                                doneBytes = done.value,
                            ),
                        ),
                        running = "j1",
                    ),
                    now = now,
                    notificationsOff = false,
                    actions = MoveActions(),
                    onBack = null,
                )
            }
        }

        fun announced() = compose.onNodeWithTag("progressAnnounce").fetchSemanticsNode().let {
            assertEquals(
                androidx.compose.ui.semantics.LiveRegionMode.Polite,
                it.config[SemanticsProperties.LiveRegion],
            )
            it.config[SemanticsProperties.ContentDescription].single()
        }
        val seen = mutableListOf<String>()
        for (d in 0L..1_000L step 25) {
            done.value = d
            compose.waitForIdle()
            announced().let { if (seen.lastOrNull() != it) seen += it }
        }
        // 0 %, 10 %, …, 100 %: eleven announcements for 41 updates.
        assertEquals(11, seen.size)
        assertEquals("Backup 0% done", seen.first())
        assertEquals("Backup 100% done", seen.last())
    }

    // ---------------------------------------------------------------- item 6

    @Test
    fun `item 6 before the second check, no stamp - Copied, last step check`() {
        show(MoveUiState(jobs = listOf(JobView(job(), receipt(confirmed = 0), canReadBack = true))))
        compose.onNodeWithTag("verifiedStamp").assertDoesNotExist()
        compose.onNodeWithTag("copiedNextCheck").assertTextEquals("Copied ✓ · Last step: check")
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("checkBackup"))
        compose.onNodeWithTag("checkBackup").assertExists()
    }

    @Test
    fun `item 6 a partly checked backup has no stamp yet`() {
        val partly = JobView(job(), receipt(verified = 3, confirmed = 1), canReadBack = true)
        assertEquals(false, receiptStamped(partly))
        assertEquals(true, receiptStamped(JobView(job(), receipt(), canReadBack = true)))
        assertEquals(false, receiptStamped(JobView(job(), receipt(verified = 0, confirmed = 0), true)))
    }

    @Test
    fun `item 6 after the second check, the stamp`() {
        show(MoveUiState(jobs = listOf(JobView(job(), receipt(), canReadBack = true))))
        compose.onNodeWithTag("verifiedStamp").assertExists()
        compose.onNodeWithTag("copiedNextCheck").assertDoesNotExist()
    }

    @Test
    fun `item 6 the re-plug step shows live states, then the stamp`() {
        val step = mutableStateOf<BackupCheck>(BackupCheck.Unplug("j1"))
        compose.setContent {
            KeepClearTheme {
                MoveScreen(
                    summary = summary(),
                    state = MoveUiState(
                        jobs = listOf(JobView(job(), receipt(confirmed = 0), true)),
                        check = step.value,
                    ),
                    now = now,
                    notificationsOff = false,
                    actions = MoveActions(),
                    onBack = null,
                )
            }
        }
        compose.onNodeWithTag("checkUnplug").assertTextEquals("Unplug the USB drive")
        step.value = BackupCheck.PlugBack("j1")
        compose.waitForIdle()
        compose.onNodeWithTag("checkPlugBack").assertTextEquals("Unplugged ✓ — plug it back in")
        step.value = BackupCheck.Checking("j1")
        compose.waitForIdle()
        compose.onNodeWithTag("checkChecking").assertTextEquals("Checking…")
        compose.onNodeWithTag("checkStamp").assertDoesNotExist()
        step.value = BackupCheck.Done("j1", CheckResult(ok = 3, damaged = 0, missing = 0))
        compose.waitForIdle()
        compose.onNodeWithTag("checkStamp").assertExists()
        // Not when something didn't pass.
        step.value = BackupCheck.Done("j1", CheckResult(ok = 2, damaged = 1, missing = 0))
        compose.waitForIdle()
        compose.onNodeWithTag("checkStamp").assertDoesNotExist()
    }

    @Test
    fun `item 6 the waiter follows the volume source - gone, then back and opening`(): Unit =
        kotlinx.coroutines.runBlocking {
            var mounted: Boolean? = true
            var opens = false
            var probes = 0
            val waiter = ReplugWaiter(
                mounted = { mounted },
                present = {
                    probes++
                    opens
                },
                pollMillis = 1,
            )
            val gone = async { waiter.awaitGone() }
            kotlinx.coroutines.delay(20)
            assertEquals(false, gone.isCompleted)
            mounted = false
            gone.await()
            // A known volume is followed by its events, without opening the drive each time.
            assertEquals(0, probes)
            val back = async { waiter.awaitBack() }
            mounted = true
            kotlinx.coroutines.delay(20)
            assertEquals(false, back.isCompleted) // mounted, but doesn't open yet
            opens = true
            back.await()
        }

    @Test
    fun `item 6 without a volume the waiter looks by opening the drive`(): Unit = kotlinx.coroutines.runBlocking {
        var opens = true
        val waiter = ReplugWaiter(mounted = { null }, present = { opens }, pollMillis = 1)
        val gone = async { waiter.awaitGone() }
        kotlinx.coroutines.delay(10)
        assertEquals(false, gone.isCompleted)
        opens = false
        gone.await()
        opens = true
        waiter.awaitBack()
    }

    // ---------------------------------------------------------------- item 7

    @Test
    fun `item 7 the free result is measured - Freed and now free`() {
        val r =
            FreeResult(
                "j1",
                done = 3,
                bytes = 3_000_000,
                skipped = 0,
                failed = 0,
                forGood = true,
                targetLabel = "SanDisk",
            )
        val measured = r.measured(before = 16_800_000_000, after = 47_800_000_000)
        assertEquals(31_000_000_000, measured.measuredFreed)
        assertEquals(47_800_000_000, measured.nowFree)
        // Nothing is claimed for the trash, test mode, no difference, or an unknown measure.
        assertEquals(null, r.copy(forGood = false).measured(1, 100).measuredFreed)
        assertEquals(null, r.copy(simulated = true).measured(1, 100).measuredFreed)
        assertEquals(null, r.measured(100, 100).measuredFreed)
        assertEquals(null, r.measured(null, 100).measuredFreed)
        show(MoveUiState(freeResult = measured))
        compose.onNodeWithTag("freeBanner").assertExists()
        val all = allText("freeResult")
        assertTrue(all, all.contains("Freed 31.0 GB · now 47.8 GB free"))
    }

    // ---------------------------------------------------------------- item 8

    @Test
    fun `item 8 a full drive says what is needed and what is free, with one button`() {
        val paused = job(state = JobState.PAUSED, reason = PauseReason.TARGET_FULL)
        show(
            MoveUiState(
                jobs = listOf(
                    JobView(
                        paused,
                        receipt(pending = 3),
                        true,
                        remainingBytes = 4_100_000_000,
                        targetFreeBytes = 2_300_000_000,
                    ),
                ),
            ),
        )
        assertEquals("Not enough space on the USB drive: 4.1 GB needed, 2.3 GB free", text("pauseText"))
        compose.onNodeWithTag("resume").performClick()
        assertEquals("j1", resumed)
    }

    @Test
    @Config(qualifiers = "iw")
    fun `item 8 Hebrew pause messages are the owner's words`() {
        fun shown(reason: PauseReason, free: Long? = null): String {
            val paused = job(state = JobState.PAUSED, reason = reason)
            show(
                MoveUiState(
                    jobs = listOf(
                        JobView(
                            paused,
                            receipt(pending = 3),
                            true,
                            remainingBytes = 4_100_000_000,
                            targetFreeBytes = free,
                        ),
                    ),
                ),
            )
            return text("pauseText")
        }
        assertEquals(
            "אין מספיק מקום בדיסק און קי: צריך 4.1GB, פנויים 2.3GB",
            shown(PauseReason.TARGET_FULL, 2_300_000_000),
        )
    }

    @Test
    fun `item 8 unplugged and stopped by the phone have their own message and one button`() {
        val reason = mutableStateOf(PauseReason.TARGET_DISCONNECTED)
        compose.setContent {
            KeepClearTheme {
                MoveScreen(
                    summary(),
                    MoveUiState(
                        jobs = listOf(
                            JobView(
                                job(state = JobState.PAUSED, reason = reason.value),
                                receipt(pending = 3),
                                true,
                                remainingBytes = 4_100_000_000,
                            ),
                        ),
                    ),
                    now,
                    false,
                    MoveActions(),
                    onBack = null,
                )
            }
        }
        for ((r, expected) in listOf(
            PauseReason.TARGET_DISCONNECTED to "The USB drive was unplugged — connect it and continue",
            PauseReason.STOPPED_BY_PHONE to "The phone stopped the backup to save battery",
            PauseReason.TARGET_FULL to "Not enough space on the USB drive: 4.1 GB more needed",
        )) {
            reason.value = r
            compose.waitForIdle()
            assertEquals(expected, text("pauseText"))
            compose.onNodeWithTag("resume").assertExists()
        }
    }

    @Test
    fun `item 8 every pause has exactly one button except the battery wait, which goes on by itself`() {
        PauseReason.entries.forEach { r -> assertEquals(r.name, r != PauseReason.BATTERY_LOW, pauseHasButton(r)) }
        assertEquals(true, pauseHasButton(null))
    }

    @Test
    fun `item 8 a partial result is one line, and Show opens the list with the files`() {
        val view = JobView(
            job(),
            receipt(failed = mapOf(FailReason.SOURCE_UNREADABLE to 2, FailReason.TOO_LARGE_FOR_TARGET to 1)),
            true,
            failedFiles = listOf(
                FailedFile("/storage/emulated/0/DCIM/a.jpg", FailReason.SOURCE_UNREADABLE),
                FailedFile("/storage/emulated/0/DCIM/b.jpg", FailReason.SOURCE_UNREADABLE),
                FailedFile("/storage/emulated/0/DCIM/movie.mp4", FailReason.TOO_LARGE_FOR_TARGET),
            ),
        )
        show(MoveUiState(jobs = listOf(view)))
        assertTrue(allText("errorsLine").contains("Finished with 3 errors"))
        compose.onNodeWithTag("failedFile").assertDoesNotExist()
        compose.onNodeWithTag("errorsShow").performClick()
        compose.onNodeWithTag("failed_SOURCE_UNREADABLE").assertExists()
        assertEquals(
            3,
            compose.onAllNodes(hasTestTag("failedFile")).fetchSemanticsNodes().size,
        )
    }

    // ---------------------------------------------------------------- item 11

    @Test
    fun `item 11 the reminder's tap shows the check offer, and its button starts the check`() {
        show(MoveUiState(jobs = listOf(JobView(job(), receipt(), true)), recheck = "j1"))
        compose.onNodeWithTag("recheckCard").assertExists()
        compose.onNodeWithTag("recheckButton").performClick()
        assertEquals("j1", checked)
    }

    // ---------------------------------------------------------------- helpers

    private fun ctx(): android.content.Context =
        androidx.test.core.app.ApplicationProvider.getApplicationContext()

    private fun text(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString { Bidi.strip(it.text) }

    private fun allText(tag: String): String {
        fun collect(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { Bidi.strip(it.text) } +
                n.children.flatMap { collect(it) }
        return collect(compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()).joinToString(" ")
    }
}

/** H1b: a volume source the tests drive by hand. */
class FakeVolumeSource : VolumeSource {
    private var list = emptyList<RemovableDrive>()
    private var listener: (() -> Unit)? = null
    var closed = false

    fun set(vararg drives: RemovableDrive) {
        list = drives.toList()
        listener?.invoke()
    }

    override fun removable() = list

    override fun watch(onChange: () -> Unit): AutoCloseable {
        listener = onChange
        return AutoCloseable {
            closed = true
            listener = null
        }
    }
}
