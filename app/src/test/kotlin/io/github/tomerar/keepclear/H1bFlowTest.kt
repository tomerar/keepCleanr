// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.backup.GatewayBackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.backup.saf.TargetKind
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.file.FileGateway
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveEngine
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.scan.ScanItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.UUID

/** PR H1b: the logic behind the backup screens (ETA, notification, measured free, stop reason, reminder, README). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H1bFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var t: TestApp? = null

    private fun setUp(): TestApp = TestApp(tmp.newFolder("phone")).also {
        t = it
        it.app.volumeSource = FakeVolumeSource()
        it.app.testMode.turnOff()
    }

    @After
    fun tearDown() {
        t?.close()
    }

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // ---------------------------------------------------------------- item 4: smoothed time left

    @Test
    fun `item 4 the time left follows a steady rate exactly`() {
        val eta = EtaTracker()
        val mb = 1_000_000L
        eta.onRunning("j")
        assertNull(eta.estimate("j", 0, 0, 1_000 * mb))
        var last: Long? = null
        for (s in 1..60) last = eta.estimate("j", s * 1_000L, s * 10 * mb, 1_000 * mb)
        // 10 MB/s, 400 MB left: 40 s.
        assertEquals(40_000L, last)
    }

    @Test
    fun `item 4 one burst barely moves the time left, the raw rate would jump`() {
        val eta = EtaTracker()
        val mb = 1_000_000L
        eta.onRunning("j")
        eta.estimate("j", 0, 0, 10_000 * mb)
        var done = 0L
        var before = 0L
        for (s in 1..60) {
            done += 10 * mb
            before = eta.estimate("j", s * 1_000L, done, 10_000 * mb) ?: before
        }
        // A big file finishes at once: 100 MB in one second (10× the rate).
        done += 100 * mb
        val after = eta.estimate("j", 61_000L, done, 10_000 * mb)!!
        val raw = (10_000 * mb - done) / (100 * mb) * 1_000
        val change = kotlin.math.abs(after - before).toDouble() / before
        assertTrue("smoothed change $change", change < 0.30)
        assertTrue("the raw estimate would have dropped to $raw", raw < before / 5)
        // A lasting change is followed: after three time constants at the new rate, within 10 %.
        var e = after
        for (s in 62..160) {
            done += 20 * mb
            e = eta.estimate("j", s * 1_000L, done, 10_000 * mb)!!
        }
        val expected = (10_000 * mb - done) / (20 * mb) * 1_000
        assertTrue("$e vs $expected", kotlin.math.abs(e - expected).toDouble() / expected < 0.10)
    }

    @Test
    fun `item 4 nothing is said in the first 10 seconds, and a restart starts again`() {
        val eta = EtaTracker()
        eta.onRunning("j")
        assertNull(eta.estimate("j", 0, 0, 100))
        assertNull(eta.estimate("j", 9_000, 50, 100))
        assertEquals(10_000L, eta.estimate("j", 10_000, 50, 100))
        eta.onRunning(null)
        eta.onRunning("j")
        assertNull(eta.estimate("j", 3_600_000, 50, 100))
    }

    // ---------------------------------------------------------------- item 5: steps and notification

    @Test
    fun `item 5 announcement steps are tenths`() {
        assertEquals(0, announceStep(0, 1_000))
        assertEquals(0, announceStep(99, 1_000))
        assertEquals(1, announceStep(100, 1_000))
        assertEquals(4, announceStep(420, 1_000))
        assertEquals(10, announceStep(1_000, 1_000))
        assertEquals(10, announceStep(5_000, 1_000))
        assertEquals(0, announceStep(5, 0))
        assertEquals("42%", percentText(429, 1_000, Locale.US))
        assertEquals("100%", percentText(1_000, 1_000, Locale.US))
        assertEquals("99%", percentText(999, 1_000, Locale.US)) // never "100%" before it is done
    }

    @Test
    fun `item 5 segments - copy fills first, the check follows`() {
        assertEquals(0, BackupSegments.copyPosition(0, 100))
        assertEquals(500, BackupSegments.copyPosition(50, 100))
        assertEquals(BackupSegments.COPY, BackupSegments.copyPosition(150, 100))
        assertEquals(BackupSegments.COPY, BackupSegments.checkPosition(0, 10))
        assertEquals(BackupSegments.COPY + BackupSegments.CHECK, BackupSegments.checkPosition(10, 10))
    }

    @Test
    fun `item 5 on Android 16 the copy is a promoted Live Update with copy and check segments`() {
        MoveNotifications.ensureChannel(context)
        val n = MoveNotifications.progress(context, CopyNotice(12_900_000_000, 31_000_000_000, 41 * 60_000L))
        assertEquals("android.app.Notification\$ProgressStyle", n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertEquals("41%", n.shortCriticalText)
        assertEquals(2, n.actions.size) // Pause, Stop
        val text = Bidi.strip(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("12.9 of 31.0 GB · about 41 minutes left", text)
        val check = MoveNotifications.task(context, TaskKind.CHECK, TaskProgress(TaskKind.CHECK, 5, 10))
        assertEquals("android.app.Notification\$ProgressStyle", check.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("50%", check.shortCriticalText)
    }

    @Test
    @Config(sdk = [30])
    fun `item 5 below Android 16 a plain progress notification`() {
        MoveNotifications.ensureChannel(context)
        val n = MoveNotifications.progress(context, CopyNotice(500, 1_000, null))
        assertFalse(n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty().contains("ProgressStyle"))
        assertEquals(BackupSegments.COPY, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(500, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(2, n.actions.size)
        val check = MoveNotifications.task(context, TaskKind.CHECK, TaskProgress(TaskKind.CHECK, 5, 10))
        assertEquals(10, check.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    }

    // ---------------------------------------------------------------- item 7: measured free

    private val ref = "davs://drive.test/remote.php/dav/files/u"

    private fun backedUp(app: KeepClearApplication, n: Int = 3): Pair<String, List<String>> = runBlocking {
        val sources = (1..n).map { i ->
            t!!.file("DCIM/Camera/IMG_$i.jpg", ByteArray(2_000 + i) { (it * i).toByte() }).absolutePath
        }
        val drive = tmp.newFolder("drive")
        val target = GatewayBackupTarget(
            FileGateway(),
            KPath.Local(drive.absolutePath),
            "drive-1",
            "Drive",
            TargetCaps(canReadBack = true, readsFromServer = true),
        )
        app.targetOpener = { _, _ -> OpenedTarget.Ok(target, TargetKind.REMOVABLE) }
        val id = "job-" + UUID.randomUUID()
        val items = sources.map { p ->
            app.localGateway.stat(KPath.Local(p))!!
                .let { ScanItem(KPath.Local(p), p, it.size, it.mtimeMillis, identity = it.identity) }
        }
        val store = app.moveController.store
        store.putJob(MoveJob(id, "drive-1", "Drive", MovePlanner.rootRel("Phone"), 1, targetRef = ref))
        store.putFiles(MovePlanner.plan(id, items, "Phone", "Camera", target.caps))
        assertEquals(JobState.DONE, MoveEngine(app.localGateway, target, store, recentMillis = 0).run(id).state)
        store.files(id).forEach { store.putFile(it.copy(confirmedAt = 1)) }
        id to sources
    }

    @Test
    fun `item 7 freeing for good reports the space measured before and after`() {
        val t = setUp()
        val (id, sources) = backedUp(t.app)
        val free = ArrayDeque(listOf(16_800_000_000L, 47_800_000_000L))
        t.app.moveController.phoneFree = { free.removeFirstOrNull() }
        val move = t.viewModel(MoveViewModel::class.java)
        move.freeOriginals(id, userConfirmed(sources))
        TestApp.awaitMain("freed") {
            t.app.moveController.task.value == null && t.app.moveController.results.value.freeResult != null
        }
        val r = t.app.moveController.results.value.freeResult!!
        assertTrue(r.forGood)
        assertEquals(31_000_000_000L, r.measuredFreed)
        assertEquals(47_800_000_000L, r.nowFree)
        assertTrue(sources.none { File(it).exists() })
    }

    // ---------------------------------------------------------------- item 8: stopped by the phone

    @Test
    fun `item 8 a job found running with no service and no user stop was stopped by the phone`() {
        val t = setUp()
        val store = t.app.moveController.store
        // PR H4 item 5: Android recorded that the system ended the process (C125's guess replaced).
        t.app.moveController.processExits = ProcessExitSource { listOf(ProcessExit(2, KillReasons.LOW_MEMORY)) }
        runBlocking { store.putJob(MoveJob("killed", "d", "Drive", "KeepClear/P", 1, JobState.RUNNING)) }
        t.viewModel(MoveViewModel::class.java)
        TestApp.awaitMain("marked") { runBlocking { store.job("killed") }?.state == JobState.PAUSED }
        assertEquals(PauseReason.STOPPED_BY_PHONE, runBlocking { store.job("killed") }!!.pauseReason)
        // A user's stop stays a user's stop; an app update stays an update.
        runBlocking {
            store.putJob(MoveJob("user", "d", "Drive", "KeepClear/P", 1, JobState.PAUSED, PauseReason.PAUSED_BY_USER))
            store.putJob(MoveJob("upd", "d", "Drive", "KeepClear/P", 1, JobState.RUNNING))
            t.app.moveController.markInterrupted()
        }
        assertEquals(PauseReason.PAUSED_BY_USER, runBlocking { store.job("user") }!!.pauseReason)
        assertEquals(PauseReason.INTERRUPTED, runBlocking { store.job("upd") }!!.pauseReason)
    }

    // ---------------------------------------------------------------- item 3 (ViewModel side)

    @Test
    fun `item 3 the Backup screen hears a drive plugged in, live`() {
        val t = setUp()
        val source = t.app.volumeSource as FakeVolumeSource
        val move = t.viewModel(MoveViewModel::class.java)
        assertNull(move.state.value.drive)
        source.set(RemovableDrive("AB12", "SanDisk", mounted = true))
        TestApp.awaitMain("found") { move.state.value.drive?.label == "SanDisk" }
        source.set()
        TestApp.awaitMain("gone") { move.state.value.drive == null }
    }

    // ---------------------------------------------------------------- item 11: reminder

    @Test
    fun `item 11 the reminder decision - off by default, 3 months after the last check, then monthly`() {
        val day = 86_400_000L
        val check = 1_000 * day

        fun remind(enabled: Boolean = true, last: Long? = check, shown: Long = 0, now: Long) =
            CheckReminderPolicy.shouldRemind(enabled, last, shown, now)
        // Positive.
        assertTrue(remind(now = check + 90 * day))
        assertTrue(remind(now = check + 200 * day))
        assertTrue(remind(shown = check + 90 * day, now = check + 120 * day)) // a month after the first
        assertTrue(remind(shown = check - 10 * day, now = check + 91 * day)) // shown before this check
        // Negative.
        assertFalse(remind(enabled = false, now = check + 200 * day))
        assertFalse(remind(last = null, now = check + 200 * day))
        assertFalse(remind(last = 0, now = check + 200 * day))
        assertFalse(remind(now = check + 89 * day))
        assertFalse(remind(shown = check + 90 * day, now = check + 100 * day))
        assertFalse(remind(now = check - day)) // clock went back
        assertFalse(setUp().app.checkReminderPrefs.enabled)
    }

    @Test
    fun `item 11 the last check is the newest second check of any backup`() {
        val t = setUp()
        val (id, _) = backedUp(t.app, n = 2)
        val store = t.app.moveController.store
        runBlocking {
            store.files(id).forEachIndexed { i, f -> store.putFile(f.copy(confirmedAt = 100L + i)) }
            store.putJob(MoveJob("never", "d", "Drive", "KeepClear/P", 1, JobState.DONE))
        }
        assertEquals(LastCheck(id, 101L), runBlocking { lastCheck(store) })
    }

    @Test
    fun `item 11 the notification text is the owner's and tapping it names the backup`() {
        val n = CheckReminder.build(context, "j1")
        assertEquals(
            "3 months since the last check — connect the USB drive for another check",
            n.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        val intent = org.robolectric.Shadows.shadowOf(n.contentIntent).savedIntent
        assertEquals("j1", intent.getStringExtra(CheckReminder.EXTRA_RECHECK))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    @Config(qualifiers = "iw")
    fun `item 11 Hebrew reminder text`() {
        assertEquals(
            "עברו 3 חודשים מהבדיקה האחרונה — לחבר את הדיסק און קי לבדיקה חוזרת",
            context.getString(io.github.tomerar.keepclear.core.i18n.R.string.check_reminder_text),
        )
    }

    // ---------------------------------------------------------------- item 10: README texts

    @Test
    fun `item 10 the README has Hebrew and English whatever the app's language`() {
        val texts = readmeTexts(context)
        assertEquals(listOf("he", "en"), texts.map { it.lang })
        assertEquals(listOf("rtl", "ltr"), texts.map { it.dir })
        assertEquals("גיבוי של KeepClear", texts[0].title)
        assertEquals("KeepClear backup", texts[1].title)
        assertTrue(texts.all { it.restore.isNotBlank() && it.open.isNotBlank() && it.what.isNotBlank() })
    }
}
