// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.saf

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.testing.usb.FakeUsbProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** M12: SAF behaviour the shared contract can't express (provider renames, unplugged drive). */
@RunWith(AndroidJUnit4::class)
class SafGatewayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gateway = SafGateway(context.contentResolver)
    private val root = KPath.Saf(
        DocumentsContract.buildTreeDocumentUri(FakeUsbProvider.authority(context), FakeUsbProvider.ROOT_DOC).toString(),
        FakeUsbProvider.ROOT_DOC,
    )

    @Before
    fun setUp() {
        FakeUsbProvider.reset(File(context.filesDir, "usb-saf"))
    }

    private inline fun <reified T : StorageException> expect(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.java.simpleName}")
        } catch (e: StorageException) {
            if (e !is T) throw e
        }
    }

    @Test
    fun aProviderRenameIsUndoneNotKept(): Unit = runBlocking {
        // The drive is case-insensitive: "A.txt" collides with "a.txt" and the provider would store "A (1).txt".
        gateway.create(root, "a.txt").use { it.write(1) }
        expect<StorageException.AlreadyExists> { runBlocking { gateway.create(root, "A.txt") } }
        assertEquals(listOf("a.txt"), gateway.list(root).toList().map { it.name })
    }

    @Test
    fun anUnpluggedDriveFailsWithAStorageException(): Unit = runBlocking {
        gateway.create(root, "f").use { it.write(1) }
        val f = gateway.list(root).toList().single().path
        FakeUsbProvider.ejected = true
        assertNull(gateway.stat(f))
        expect<StorageException> { runBlocking { gateway.list(root).toList() } }
        expect<StorageException> { runBlocking { gateway.delete(f) } }
        FakeUsbProvider.ejected = false
        assertEquals(1, gateway.list(root).toList().size)
    }

    @Test
    fun aFolderIsNeverMovedIntoItselfAndStaysWhereItWas(): Unit = runBlocking {
        val d = gateway.mkdir(root, "d")
        val inner = gateway.mkdir(d, "inner")
        expect<StorageException.Unsupported> { runBlocking { gateway.rename(d, inner, "d2") } }
        assertEquals("d", gateway.stat(d)!!.name)
    }

    @Test
    fun whenTheSecondStepFailsTheFirstIsUndone(): Unit = runBlocking {
        gateway.create(root, "a").use { it.write(7) }
        val a = gateway.list(root).toList().single { it.name == "a" }.path
        val dest = gateway.mkdir(root, "dest")
        // "b" is free next to "a": rename first, then move. The move fails, so the rename is undone.
        FakeUsbProvider.failMoves = true
        expect<StorageException> { runBlocking { gateway.rename(a, dest, "b") } }
        FakeUsbProvider.failMoves = false
        assertEquals("a", gateway.stat(a)!!.name)
        assertEquals(setOf("a", "dest"), gateway.list(root).toList().map { it.name }.toSet())
    }

    @Test
    fun nothingOutsideAGrantedFolderIsReachable(): Unit = runBlocking {
        expect<StorageException.Unsupported> { runBlocking { gateway.stat(KPath.Local("/storage/emulated/0")) } }
    }
}
