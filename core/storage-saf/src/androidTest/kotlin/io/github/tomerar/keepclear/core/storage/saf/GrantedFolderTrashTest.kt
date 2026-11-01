// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.saf

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.trash.FakeClock
import io.github.tomerar.keepclear.engine.trash.FakeSigner
import io.github.tomerar.keepclear.engine.trash.InMemoryTrashIndex
import io.github.tomerar.keepclear.engine.trash.TrashManager
import io.github.tomerar.keepclear.testing.usb.FakeUsbProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M12b on a device: a file in a granted folder (mounted at WhatsApp's real path) goes through the real executor and
 * trash into `<folder>/.KeepClearTrash`, and restore puts it back byte-exact. Chat backups stay PROTECTED.
 */
@RunWith(AndroidJUnit4::class)
class GrantedFolderTrashTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val point = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp"
    private val drive = File(context.filesDir, "usb-whatsapp")
    private lateinit var mount: SafTreeMount
    private val index = InMemoryTrashIndex()
    private val protectedPaths = ProtectedPaths()

    @Before
    fun setUp() {
        FakeUsbProvider.reset(drive)
        File(drive, "Media/WhatsApp Images/Sent").mkdirs()
        File(drive, "Media/WhatsApp Images/Sent/IMG-1.jpg").writeBytes(ByteArray(3_000) { (it % 251).toByte() })
        File(drive, "Databases").mkdirs()
        File(drive, "Databases/msgstore.db.crypt14").writeBytes(ByteArray(10))
        val tree = DocumentsContract.buildTreeDocumentUri(FakeUsbProvider.authority(context), FakeUsbProvider.ROOT_DOC)
        mount = SafTreeMount(
            SafGateway(context.contentResolver),
            KPath.Saf(tree.toString(), FakeUsbProvider.ROOT_DOC),
            point,
        )
    }

    private fun manager() = TrashManager(
        mount,
        index,
        FakeSigner(),
        FakeClock(),
        protectedPaths,
        volumeRoots = emptyList(),
        grantedRoots = { listOf(point) },
    )

    private suspend fun run(manager: TrashManager, path: String, kind: OpKind): ExecutionReport {
        val s = mount.stat(KPath.Local(path))!!
        val op = PlannedOp(s.path, path, kind, s.isDirectory, s.size, s.mtimeMillis)
        val guard = SafetyGuard(protectedPaths, grantedTrashRoots = listOf(point))
        val executor = ActionExecutor(mount, guard, protectedPaths, manager, "s")
        return executor.run(ActionPlan("p", listOf(op), "t").confirm(1, "s"))
    }

    @Test
    fun trashAndRestoreInsideTheGrantedFolder(): Unit = runBlocking {
        val manager = manager()
        val sent = "$point/Media/WhatsApp Images/Sent/IMG-1.jpg"
        val bytes = File(drive, "Media/WhatsApp Images/Sent/IMG-1.jpg").readBytes()
        assertEquals(1, (run(manager, sent, OpKind.TRASH) as ExecutionReport.Ran).done.size)
        val entry = index.all().single()
        assertTrue(entry.trashedPath.startsWith("$point/.KeepClearTrash/"))
        assertFalse(File(drive, "Media/WhatsApp Images/Sent/IMG-1.jpg").exists())
        assertTrue(File(drive, ".KeepClearTrash/.nomedia").exists())

        assertEquals(1, (run(manager, entry.trashedPath, OpKind.RESTORE) as ExecutionReport.Ran).done.size)
        assertArrayEquals(bytes, File(drive, "Media/WhatsApp Images/Sent/IMG-1.jpg").readBytes())
        assertTrue(index.all().isEmpty())
    }

    @Test
    fun emptyNowDeletesFromTheGrantedFoldersTrash(): Unit = runBlocking {
        val manager = manager()
        val sent = "$point/Media/WhatsApp Images/Sent/IMG-1.jpg"
        assertEquals(1, (run(manager, sent, OpKind.TRASH) as ExecutionReport.Ran).done.size)
        val entry = index.all().single()
        // PR #23 review: the guard must accept the granted folder's own trash, or "Empty now" deletes nothing.
        assertEquals(1, (run(manager, entry.trashedPath, OpKind.PURGE) as ExecutionReport.Ran).done.size)
        assertTrue(index.all().isEmpty())
        assertFalse(File(drive, entry.trashedPath.removePrefix("$point/")).exists())
    }

    @Test
    fun chatBackupsInTheGrantedFolderStayProtected(): Unit = runBlocking {
        val report = run(manager(), "$point/Databases/msgstore.db.crypt14", OpKind.TRASH)
        // Refused by the guard (or skipped): never done.
        assertTrue(report !is ExecutionReport.Ran || report.done.isEmpty())
        assertTrue(File(drive, "Databases/msgstore.db.crypt14").exists())
    }
}
