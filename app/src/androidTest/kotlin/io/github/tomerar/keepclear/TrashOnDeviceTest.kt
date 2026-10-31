// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.file.FileGateway
import io.github.tomerar.keepclear.engine.trash.InMemoryTrashIndex
import io.github.tomerar.keepclear.engine.trash.TrashManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M6 on a real device file system and the real Android Keystore: trash, recover from the signed
 * sidecar after losing the index, then restore byte-exact. Uses the app's own external files dir.
 */
@RunWith(AndroidJUnit4::class)
class TrashOnDeviceTest {
    @Test
    fun trashRecoverRestore(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val volume = File(context.getExternalFilesDir(null), "trash-test-${System.nanoTime()}").apply {
            mkdirs()
        }.canonicalPath
        val photo = File(volume, "DCIM/תמונה.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(4096) { 3 })
        }
        val gateway = FileGateway()
        val index = InMemoryTrashIndex()
        val signer = KeystoreSidecarSigner()
        val clock = SystemTrashClock(context.contentResolver)

        fun manager() = TrashManager(gateway, index, signer, clock, ProtectedPaths(), listOf(volume))
        val s = gateway.stat(KPath.Local(photo.path))!!
        manager().perform(
            PlannedOp(s.path, photo.path, OpKind.TRASH, false, s.size, s.mtimeMillis, s.identity),
            gateway,
        )
        assertFalse(photo.exists())

        index.entries.clear()
        val report = manager().recover()
        assertEquals(1, report.rebuiltFromSidecars)
        val entry = index.all().single()
        assertTrue("sidecar must verify with the Keystore key", entry.trusted)

        manager().perform(
            PlannedOp(KPath.Local(entry.trashedPath), entry.trashedPath, OpKind.RESTORE, false, 0, 0),
            gateway,
        )
        assertArrayEquals(ByteArray(4096) { 3 }, photo.readBytes())
        File(volume).deleteRecursively()
    }
}
