// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.backup.saf.SafTargets
import io.github.tomerar.keepclear.backup.saf.TargetCheck
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveFileState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.move.Receipt
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.testing.usb.FakeUsbProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * M10 on a device: the real foreground service copies to a SAF tree (a fake USB drive inside the debug build).
 * MIG-4: a job interrupted mid-copy (app updated or killed) is shown as paused, resumes on request, and
 * completes with every copy verified and every original still on the phone. ONB-10/E-NOTIF: the job
 * finishes whether or not notifications are allowed; the result is read back from Room on reopen.
 */
@RunWith(AndroidJUnit4::class)
class MoveJobOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as KeepClearApplication
    private val phone = File(context.filesDir, "move-test-phone/DCIM/Camera")
    private val tree = DocumentsContract.buildTreeDocumentUri(
        FakeUsbProvider.authority(context),
        FakeUsbProvider.ROOT_DOC,
    )
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        FakeUsbProvider.reset(File(context.filesDir, "move-test-usb"))
        phone.deleteRecursively()
        phone.mkdirs()
        // A foreground activity, so the service may start in the foreground (Android 12+ rules).
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        FakeUsbProvider.enabled = false
    }

    private fun photos(n: Int): List<ScanItem> = (0 until n).map { i ->
        val f = File(phone, "IMG_2021020${i}_090000.jpg")
        f.writeBytes(ByteArray(200_000 + i) { (it * (i + 7)).toByte() })
        f.setLastModified(1_612_000_000_000L + i * 86_400_000L)
        ScanItem(KPath.Local(f.absolutePath), f.absolutePath, f.length(), f.lastModified())
    }

    private suspend fun newJob(n: Int): String {
        val check = SafTargets.open(
            context.contentResolver,
            tree,
            "Test USB",
            removableAuthorities = setOf(FakeUsbProvider.authority(context)),
        )
        assertTrue("$check", check is TargetCheck.Ok)
        val target = (check as TargetCheck.Ok).target
        val id = UUID.randomUUID().toString()
        val store = app.moveController.store
        store.putJob(
            MoveJob(id, target.id, "Test USB", MovePlanner.rootRel("Test Phone"), 1, targetRef = tree.toString()),
        )
        store.putFiles(MovePlanner.plan(id, photos(n), "Test Phone", "Camera", target.caps))
        return id
    }

    private var startTick = 0L

    /** Starts the service; [awaitEnd] then waits for this run to end, not for a state left by an earlier run. */
    private fun start(id: String) {
        startTick = app.moveController.ticks.value
        app.moveController.start(context, id)
    }

    private suspend fun awaitEnd(id: String): MoveJob = withTimeout(120_000) {
        while (true) {
            val job = app.moveController.store.job(id)!!
            // The service bumps `ticks` after clearing `running` when it ends.
            val ended = app.moveController.ticks.value > startTick && app.moveController.running.value == null
            if (ended && job.state != JobState.RUNNING && job.state != JobState.READY) return@withTimeout job
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun assertAllVerifiedAndOriginalsKept(id: String) {
        val files = app.moveController.store.files(id)
        assertTrue(files.all { it.state == MoveFileState.VERIFIED })
        files.forEach { f ->
            val original = File(f.sourcePath)
            assertTrue("original kept: ${f.sourcePath}", original.exists())
            val copy = File(FakeUsbProvider.base, f.relTarget)
            assertArrayEquals(original.readBytes(), copy.readBytes())
        }
        val temps = FakeUsbProvider.base.walk().filter { it.name.startsWith(".kc-tmp") }.toList()
        assertEquals(emptyList<File>(), temps)
    }

    @Test
    fun serviceCopiesAndVerifiesWithOrWithoutNotifications(): Unit = runBlocking {
        val id = newJob(4)
        start(id)
        val job = awaitEnd(id)
        assertEquals(JobState.DONE, job.state)
        assertAllVerifiedAndOriginalsKept(id)
        // "Reopen": the receipt comes from Room alone.
        val receipt = Receipt.of(job, app.moveController.store.files(id))
        assertEquals(4, receipt.verified)
        assertTrue(receipt.complete)
    }

    @Test
    fun interruptedJobResumesAfterAnUpdate(): Unit = runBlocking {
        val id = newJob(4)
        val store = app.moveController.store
        // State as if the process died mid-copy: job RUNNING, one file COPYING with a half-written temp file.
        store.putJob(store.job(id)!!.copy(state = JobState.RUNNING))
        val first = store.files(id).first()
        store.putFile(first.copy(state = MoveFileState.COPYING))
        val tempFile = File(FakeUsbProvider.base, first.tempRel)
        tempFile.parentFile!!.mkdirs()
        tempFile.writeBytes(ByteArray(1234) { 1 })

        // MY_PACKAGE_REPLACED arrives after the update (delivered directly: apps can't send this broadcast).
        PackageReplacedReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        withTimeout(10_000) {
            while (store.job(id)!!.state != JobState.PAUSED) delay(100)
        }
        assertEquals(PauseReason.INTERRUPTED, store.job(id)!!.pauseReason)

        start(id)
        val job = awaitEnd(id)
        assertEquals(JobState.DONE, job.state)
        assertAllVerifiedAndOriginalsKept(id)
        // Nothing was trashed: copy/verify never deletes originals (MIG-5).
        assertEquals(0, app.trashManager.entries().count { it.originalPath.startsWith(phone.absolutePath) })
    }

    @Test
    fun unpluggingPausesAndTheSameDriveResumes(): Unit = runBlocking {
        val id = newJob(4)
        FakeUsbProvider.ejectOnCreate = FakeUsbProvider.creates + 6
        start(id)
        val paused = awaitEnd(id)
        assertEquals(JobState.PAUSED, paused.state)
        assertEquals(PauseReason.TARGET_DISCONNECTED, paused.pauseReason)
        FakeUsbProvider.ejected = false
        FakeUsbProvider.ejectOnCreate = 0
        start(id)
        assertEquals(JobState.DONE, awaitEnd(id).state)
        assertAllVerifiedAndOriginalsKept(id)
    }

    @Test
    fun pauseFromTheNotificationThenContinue(): Unit = runBlocking {
        val id = newJob(6)
        FakeUsbProvider.createDelayMs = 300
        start(id)
        withTimeout(60_000) {
            while (app.moveController.store.files(id).none { it.state == MoveFileState.VERIFIED }) delay(50)
        }
        // What the notification's Pause action does (MoveControlReceiver → controller).
        MoveControlReceiver().onReceive(
            context,
            Intent(context, MoveControlReceiver::class.java).putExtra("reason", PauseReason.PAUSED_BY_USER.name),
        )
        val paused = awaitEnd(id)
        assertEquals(JobState.PAUSED, paused.state)
        assertEquals(PauseReason.PAUSED_BY_USER, paused.pauseReason)
        assertTrue(app.moveController.store.files(id).any { it.state != MoveFileState.VERIFIED })
        FakeUsbProvider.createDelayMs = 0
        start(id)
        assertEquals(JobState.DONE, awaitEnd(id).state)
        assertAllVerifiedAndOriginalsKept(id)
    }
}
