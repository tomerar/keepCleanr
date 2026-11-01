// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.saf

import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** PR #22 review: a drive that fails after the stream is open still reports a StorageException. */
class MappedStreamsTest {
    private val path = KPath.Saf("content://x/tree/usb", "usb/a")

    private val failingIn = object : InputStream() {
        override fun read(): Int = throw IOException("drive removed")

        override fun close(): Unit = throw IOException("drive removed")
    }

    private val failingOut = object : OutputStream() {
        override fun write(b: Int): Unit = throw IOException("drive removed")

        override fun flush(): Unit = throw IllegalStateException("provider died")
    }

    @Test
    fun `read and close failures become StorageExceptions`() {
        val s = MappedInput(failingIn, path)
        assertThrows(StorageException::class.java) { s.read() }
        assertThrows(StorageException::class.java) { s.read(ByteArray(4), 0, 4) }
        assertThrows(StorageException::class.java) { s.close() }
    }

    @Test
    fun `write and flush failures become StorageExceptions`() {
        val s = MappedOutput(failingOut, path)
        assertThrows(StorageException::class.java) { s.write(1) }
        assertThrows(StorageException::class.java) { s.flush() }
    }
}
