// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import io.github.tomerar.keepclear.core.storage.Streams.readUpTo
import io.github.tomerar.keepclear.core.storage.Streams.skipFully
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.InputStream

class StreamsTest {
    /** Returns at most 3 bytes per read and never skips, like some content-provider streams. */
    private fun trickle(bytes: ByteArray): InputStream = object : InputStream() {
        var pos = 0

        override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(len, 3, bytes.size - pos)
            bytes.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }

        override fun skip(n: Long): Long = 0
    }

    private val data = ByteArray(200_000) { (it % 251).toByte() }

    @Test
    fun `readUpTo reads exactly max bytes across short reads`() {
        trickle(data).readUpTo(70_001) shouldBe data.copyOf(70_001)
        trickle(data).readUpTo(0).size shouldBe 0
    }

    @Test
    fun `readUpTo stops at end of stream`() {
        trickle(data).readUpTo(500_000) shouldBe data
    }

    @Test
    fun `skipFully skips exactly or throws`() {
        val s = trickle(data)
        s.skipFully(100_000)
        s.readUpTo(4) shouldBe data.copyOfRange(100_000, 100_004)
        shouldThrow<EOFException> { trickle(data).skipFully(200_001) }
    }
}
