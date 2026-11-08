// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BoundedPngDecoderTest {
    @Test
    fun `valid still png decodes and closes source`(): Unit = runBlocking {
        val stream = TrackedStream(png())
        val result = BoundedPngDecoder.decode(stream)
        assertNotNull(result)
        assertTrue(result!!.valid())
        assertTrue(stream.closed)
    }

    @Test
    fun `animated exif damaged truncated and trailing inputs are rejected`(): Unit = runBlocking {
        val valid = png()
        assertTrue(BoundedPngDecoder.isSupportedPng(valid))
        assertFalse(BoundedPngDecoder.isSupportedPng(png("acTL")))
        assertFalse(BoundedPngDecoder.isSupportedPng(png("eXIf")))
        assertFalse(BoundedPngDecoder.isSupportedPng(png(extraChunks = 4_096)))
        assertFalse(BoundedPngDecoder.isSupportedPng(valid.copyOf(valid.size - 1)))
        assertFalse(BoundedPngDecoder.isSupportedPng(valid + byteArrayOf(0)))
        val damaged = valid.copyOf().apply { this[20] = (this[20].toInt() xor 1).toByte() }
        val stream = TrackedStream(damaged)
        assertNull(BoundedPngDecoder.decode(stream))
        assertTrue(stream.closed)
        val badData = TrackedStream(png(corruptData = true))
        assertNull(BoundedPngDecoder.decode(badData))
        assertTrue(badData.closed)
    }

    @Test
    fun `oversized compressed input is refused and closed`(): Unit = runBlocking {
        val stream = TrackedStream(ByteArray(8 * 1024 * 1024 + 32 * 1024))
        assertNull(BoundedPngDecoder.decode(stream))
        assertTrue(stream.closed)
        assertEquals(8 * 1024 * 1024 + 1, stream.consumed)
    }

    @Test
    fun `huge dimensions and unsupported jpeg are refused`(): Unit = runBlocking {
        val huge = TrackedStream(png(width = 100_000, height = 1_001))
        assertNull(BoundedPngDecoder.decode(huge))
        assertTrue(huge.closed)
        val jpeg = TrackedStream(byteArrayOf(-1, -40, -1, -39))
        assertNull(BoundedPngDecoder.decode(jpeg))
        assertTrue(jpeg.closed)
    }

    @Test
    fun `cancelled read closes stream`(): Unit = runBlocking {
        lateinit var job: kotlinx.coroutines.Job
        var closed = false
        val stream = object : ByteArrayInputStream(png()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                job.cancel()
                return super.read(buffer, offset, length)
            }

            override fun close() {
                closed = true
                super.close()
            }
        }
        job = launch { BoundedPngDecoder.decode(stream) }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(closed)
        job.cancelAndJoin()
    }

    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        val consumed: Int get() = pos

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun png(
        extraChunk: String? = null,
        width: Int = 2,
        height: Int = 1,
        corruptData: Boolean = false,
        extraChunks: Int = 0,
    ): ByteArray {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { it.write(byteArrayOf(0, -1, 0, 0, -1, 0, -1, 0, -1)) }
        val output = ByteArrayOutputStream()
        val writer = DataOutputStream(output)
        writer.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(width)
            writeInt(height)
            write(byteArrayOf(8, 6, 0, 0, 0))
        }
        chunk(writer, "IHDR", header.toByteArray())
        if (extraChunk != null) chunk(writer, extraChunk, byteArrayOf())
        repeat(extraChunks) { chunk(writer, "tEXt", byteArrayOf()) }
        chunk(writer, "IDAT", if (corruptData) byteArrayOf(1, 2, 3) else raw.toByteArray())
        chunk(writer, "IEND", byteArrayOf())
        return output.toByteArray()
    }

    private fun chunk(writer: DataOutputStream, name: String, data: ByteArray) {
        val type = name.toByteArray(Charsets.US_ASCII)
        writer.writeInt(data.size)
        writer.write(type)
        writer.write(data)
        val crc = CRC32().apply {
            update(type)
            update(data)
        }
        writer.writeInt(crc.value.toInt())
    }
}
