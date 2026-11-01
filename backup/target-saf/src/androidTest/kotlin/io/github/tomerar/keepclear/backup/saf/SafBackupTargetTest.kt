// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.saf

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.file.FileGateway
import io.github.tomerar.keepclear.engine.move.InMemoryMoveJobStore
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveEngine
import io.github.tomerar.keepclear.engine.move.MoveFileState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.testing.usb.FakeUsbProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M10 DoD: the SAF target against a provider that behaves like a USB drive, including unplugging it mid-job
 * (E-MOVE-2) and a provider that renames on collision (never overwrite).
 */
@RunWith(AndroidJUnit4::class)
class SafBackupTargetTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val tree = DocumentsContract.buildTreeDocumentUri(
        FakeUsbProvider.authority(context),
        FakeUsbProvider.ROOT_DOC,
    )
    private val phone = File(context.filesDir, "phone/DCIM/Camera")

    @Before
    fun setUp() {
        FakeUsbProvider.reset(File(context.filesDir, "usb"))
        phone.deleteRecursively()
        phone.mkdirs()
    }

    /** The fake drive is declared removable explicitly; unknown providers are copy-only (H1). */
    private suspend fun open() =
        SafTargets.open(resolver, tree, "Test USB", removableAuthorities = setOf(FakeUsbProvider.authority(context)))

    private suspend fun target(): SafBackupTarget {
        val check = open()
        assertTrue("$check", check is TargetCheck.Ok)
        return (check as TargetCheck.Ok).target
    }

    private fun photos(n: Int): List<ScanItem> = (0 until n).map { i ->
        val f = File(phone, "IMG_2021010${i}_120000.jpg")
        f.writeBytes(ByteArray(50_000 + i) { (it * (i + 3)).toByte() })
        f.setLastModified(1_610_000_000_000L + i * 86_400_000L)
        ScanItem(KPath.Local(f.absolutePath), f.absolutePath, f.length(), f.lastModified())
    }

    @Test
    fun copiesVerifiesAndResumesAfterUnplug(): Unit = runBlocking {
        val items = photos(5)
        val store = InMemoryMoveJobStore()
        val t = target()
        store.putJob(MoveJob("job1", t.id, t.label, MovePlanner.rootRel("Test Phone"), 1))
        MovePlanner.plan("job1", items, "Test Phone", "Camera", t.caps).forEach { store.putFile(it) }
        // Unplug a few documents in: after the folders and the first files.
        FakeUsbProvider.ejectOnCreate = FakeUsbProvider.creates + 8
        val paused = MoveEngine(FileGateway(), t, store).run("job1")
        assertEquals(JobState.PAUSED, paused.state)
        assertEquals(PauseReason.TARGET_DISCONNECTED, paused.pauseReason)

        // Plug back in; the marker proves it's the same drive.
        FakeUsbProvider.ejected = false
        FakeUsbProvider.ejectOnCreate = 0
        val again = target()
        assertEquals(t.id, again.id)
        val done = MoveEngine(FileGateway(), again, store).run("job1")
        assertEquals(JobState.DONE, done.state)
        val files = store.files("job1")
        assertTrue(files.all { it.state == MoveFileState.VERIFIED })
        files.forEach { f ->
            val copy = again.openRead(f.relTarget).use { it.readBytes() }
            assertArrayEquals(File(f.sourcePath).readBytes(), copy)
            assertEquals(Sha256.of(File(f.sourcePath).inputStream()), f.sourceSha256)
        }
        val left = File(FakeUsbProvider.base, "KeepClear/Test Phone/Camera/2021/01").list()!!.filter {
            it.startsWith(".kc-tmp")
        }
        assertEquals(emptyList<String>(), left)
        val sums = again.openRead("KeepClear/Test Phone/SHA256SUMS").use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(5, sums.lines().count { it.isNotBlank() })
    }

    @Test
    fun neverOverwritesEvenWhenTheProviderRenames(): Unit = runBlocking {
        val t = target()
        t.mkdirs("a")
        t.create("a/x.jpg").use { it.write(byteArrayOf(1, 2, 3)) }
        try {
            t.create("a/X.JPG").close()
            fail("expected AlreadyExists")
        } catch (e: StorageException.AlreadyExists) {
            // expected
        }
        t.create("a/y.jpg").use { it.write(byteArrayOf(9)) }
        try {
            t.rename("a/y.jpg", "a/x.jpg")
            fail("expected AlreadyExists")
        } catch (e: StorageException.AlreadyExists) {
            // expected
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), t.openRead("a/x.jpg").use { it.readBytes() })
        assertEquals(setOf("x.jpg", "y.jpg"), t.list("a").map { it.name }.toSet())
    }

    @Test
    fun markerIsStableAndAForeignMarkerIsRefused(): Unit = runBlocking {
        val first = target()
        assertEquals(first.id, target().id)
        File(FakeUsbProvider.base, SafTargets.MARKER).writeText("not ours")
        assertEquals(TargetCheck.DamagedMarker, open())
        assertEquals("not ours", File(FakeUsbProvider.base, SafTargets.MARKER).readText())
    }

    @Test
    fun unpluggedDriveIsUnavailable(): Unit = runBlocking {
        FakeUsbProvider.ejected = true
        assertEquals(TargetCheck.Unavailable, open())
    }
}
