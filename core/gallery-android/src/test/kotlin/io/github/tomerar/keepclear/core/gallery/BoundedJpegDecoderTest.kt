// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import io.github.tomerar.keepclear.engine.gallery.VisualFacts
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoundedJpegDecoderTest {
    @Test
    fun `valid baseline jpeg decodes without changing the input and closes the source`(): Unit = runBlocking {
        val jpeg = JpegFixtures.encode(64, 32)
        val copy = jpeg.copyOf()
        val stream = TrackedStream(jpeg)
        val facts = decoded(BoundedStillDecoder.decode(stream))
        assertTrue(facts.valid())
        assertEquals(64L * 32, facts.quality.usablePixels)
        assertEquals(2.0, facts.aspect, 0.0)
        assertTrue(stream.closed)
        assertArrayEquals(copy, jpeg)
    }

    @Test
    fun `each exif orientation is applied exactly once through decode and canonical features`(): Unit = runBlocking {
        val reference = decoded(BoundedJpegDecoder.decodeBytes(JpegFixtures.oriented(1)))
        val rotated = mutableSetOf<Pair<Long, Long>>()
        for (orientation in 1..8) {
            val bytes = JpegFixtures.oriented(orientation)
            assertEquals(orientation, (BoundedJpegDecoder.check(bytes) as BoundedJpegDecoder.Check.Ok).orientation)
            val facts = decoded(BoundedJpegDecoder.decodeBytes(bytes))
            assertEquals("dHash orientation $orientation", reference.dHash, facts.dHash)
            assertEquals("pHash orientation $orientation", reference.pHash, facts.pHash)
            assertEquals("aspect orientation $orientation", 2.0, facts.aspect, 0.0)
            assertEquals(64L * 32, facts.quality.usablePixels)
            // Ignoring the EXIF block shows the stored arrangement: each orientation must really change the picture.
            val stored = JpegFixtures.oriented(orientation).let { stripExif(it) }
            val raw = decoded(BoundedJpegDecoder.decodeBytes(stored))
            rotated += raw.dHash to raw.pHash
        }
        assertEquals("the eight stored arrangements are distinct", 8, rotated.size)
    }

    @Test
    fun `large dimensions are downsampled to the canonical analysis bound`(): Unit = runBlocking {
        val facts = decoded(BoundedJpegDecoder.decodeBytes(JpegFixtures.encode(1024, 768)))
        assertEquals(1024L * 768, facts.quality.usablePixels)
    }

    @Test
    fun `truncated and structurally damaged jpegs are damaged, never complete`(): Unit = runBlocking {
        val valid = JpegFixtures.encode(64, 32)
        for (cut in listOf(3, 20, valid.size / 2, valid.size - 2, valid.size - 1)) {
            assertEquals("cut $cut", damaged, BoundedJpegDecoder.decodeBytes(valid.copyOf(cut)))
        }
        // Entropy data cut but EOI kept passes the structure check. The platform decoder (Robolectric native graphics)
        // then fills the missing data and reports a complete image: JPEG has no checksum, so this primitive cannot
        // detect it (C203, documented limitation). Truncation without EOI is always refused above.
        val sos = indexOfMarker(valid, 0xda)
        val shortened = valid.copyOfRange(0, sos + 14 + (valid.size - sos - 16) / 2) + byteArrayOf(-1, -39)
        assertTrue(BoundedJpegDecoder.check(shortened) is BoundedJpegDecoder.Check.Ok)
        // A segment length that runs past the end, an unknown marker, a scan before the frame, a second SOI.
        val badLength = valid.copyOf().apply { this[4] = 0x7f }
        assertEquals(damaged, BoundedJpegDecoder.decodeBytes(badLength))
        assertEquals(broken, check(JpegFixtures.insertSegment(valid, 0x02, byteArrayOf(1))))
        assertEquals(broken, check(byteArrayOf(-1, -40, -1, -38, 0, 8, 1, 1, 0, 0, 63, 0, -1, -39)))
        assertEquals(broken, check(byteArrayOf(-1, -40) + valid))
        assertEquals(broken, check(JpegFixtures.skeleton(width = 0)))
    }

    @Test
    fun `damaged or ambiguous exif is never guessed`(): Unit = runBlocking {
        val plain = JpegFixtures.encode(64, 32)
        assertEquals(broken, check(JpegFixtures.withExif(plain, 9)))
        assertEquals(broken, check(JpegFixtures.withExif(plain, 0)))
        assertEquals(broken, check(JpegFixtures.withExif(plain, 6, type = 4)))
        assertEquals(variant, check(JpegFixtures.withExif(plain, 6, copies = 2)))
        val little = JpegFixtures.withExif(plain, 6, littleEndian = true)
        assertEquals(6, (check(little) as BoundedJpegDecoder.Check.Ok).orientation)
        val brokenOrder = JpegFixtures.withExif(plain, 1).also { it[indexOfMarker(it, 0xe1) + 10] = 0x41 }
        assertEquals(broken, check(brokenOrder))
    }

    @Test
    fun `oversized dimensions and bytes are refused before decoding`(): Unit = runBlocking {
        assertEquals(
            BoundedJpegDecoder.Check.Rejected(StillSkip.TOO_LARGE_IMAGE),
            check(JpegFixtures.skeleton(width = 20_000, height = 6_000)),
        )
        assertTrue(check(JpegFixtures.skeleton(width = 10_000, height = 10_000)) is BoundedJpegDecoder.Check.Ok)
        val oversize = JpegFixtures.encode(8, 8) + ByteArray(BoundedStillDecoder.MAX_BYTES)
        val stream = TrackedStream(oversize)
        assertEquals(StillDecode.Skipped(StillSkip.TOO_LARGE_FILE), BoundedStillDecoder.decode(stream))
        assertEquals(BoundedStillDecoder.MAX_BYTES + 1, stream.consumed)
        assertTrue(stream.closed)
    }

    @Test
    fun `trailing data, motion photos and multi-picture files are unsupported`(): Unit = runBlocking {
        val valid = JpegFixtures.encode(64, 32)
        assertEquals(StillDecode.Skipped(StillSkip.UNSUPPORTED_VARIANT), BoundedJpegDecoder.decodeBytes(valid + 0))
        // A Motion Photo appends an MP4 after EOI; an MPF/Ultra HDR file appends further JPEGs.
        val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypmp42".toByteArray(Charsets.US_ASCII)
        assertEquals(variant, check(valid + mp4))
        assertEquals(variant, check(valid + valid))
        val mpf = byteArrayOf(0x4d, 0x50, 0x46, 0, 0x4d, 0x4d, 0, 42, 0, 0, 0, 8)
        assertEquals(variant, check(JpegFixtures.insertSegment(valid, 0xe2, mpf)))
    }

    @Test
    fun `progressive cmyk 12-bit arithmetic lossless and dnl frames are unsupported`(): Unit = runBlocking {
        assertTrue(check(JpegFixtures.skeleton()) is BoundedJpegDecoder.Check.Ok)
        assertTrue(check(JpegFixtures.skeleton(frameMarker = 0xc1, components = 1)) is BoundedJpegDecoder.Check.Ok)
        for (marker in listOf(0xc2, 0xc3, 0xc5, 0xc9, 0xca, 0xcb, 0xcd)) {
            assertEquals("marker $marker", variant, check(JpegFixtures.skeleton(frameMarker = marker)))
        }
        assertEquals(variant, check(JpegFixtures.skeleton(components = 4)))
        assertEquals(variant, check(JpegFixtures.skeleton(precision = 12)))
        assertEquals(variant, check(JpegFixtures.skeleton(height = 0)))
        assertEquals(variant, check(JpegFixtures.skeleton(extra = listOf(0xcc to byteArrayOf(0, 1)))))
        assertEquals(
            broken,
            check(JpegFixtures.skeleton(extra = listOf(0xc0 to byteArrayOf(8, 0, 16, 0, 16, 1, 1, 0x11, 0)))),
        )
    }

    @Test
    fun `signature selects the decoder and every other format is unsupported`(): Unit = runBlocking {
        val png = JpegFixtures.png()
        assertTrue(BoundedStillDecoder.decode(ByteArrayInputStream(png)) is StillDecode.Decoded)
        assertTrue(BoundedStillDecoder.decode(ByteArrayInputStream(JpegFixtures.encode(8, 8))) is StillDecode.Decoded)
        val others = listOf(
            "GIF89a".toByteArray() + ByteArray(32),
            "RIFF".toByteArray() + byteArrayOf(40, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(32),
            byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(32),
            byteArrayOf(0, 0, 0, 28) + "ftypavif".toByteArray() + ByteArray(32),
            "BM".toByteArray() + ByteArray(64),
            byteArrayOf(0x49, 0x49, 42, 0) + ByteArray(64),
            byteArrayOf(-1, -40), // SOI alone is not a JPEG marker stream.
            ByteArray(0),
        )
        for (bytes in others) {
            val stream = TrackedStream(bytes)
            assertEquals(StillDecode.Skipped(StillSkip.UNSUPPORTED_FORMAT), BoundedStillDecoder.decode(stream))
            assertTrue(stream.closed)
        }
        // Each format decoder refuses the other's bytes even if called directly.
        assertEquals(StillDecode.Skipped(StillSkip.UNSUPPORTED_FORMAT), BoundedJpegDecoder.decodeBytes(png))
        assertEquals(
            StillDecode.Skipped(StillSkip.UNSUPPORTED_FORMAT),
            BoundedPngDecoder.decodeBytes(JpegFixtures.encode(8, 8)),
        )
    }

    @Test
    fun `cancelled read closes the stream`(): Unit = runBlocking {
        lateinit var job: Job
        var closed = false
        val stream = object : ByteArrayInputStream(JpegFixtures.encode(64, 32)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                job.cancel()
                return super.read(buffer, offset, length)
            }

            override fun close() {
                closed = true
                super.close()
            }
        }
        job = launch { BoundedStillDecoder.decode(stream) }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(closed)
    }

    @Test
    fun `marker scan of long entropy data stops when cancelled`(): Unit = runBlocking {
        val long = JpegFixtures.skeleton(entropy = ByteArray(256 * 1024) { 0x11 })
        assertTrue(check(long) is BoundedJpegDecoder.Check.Ok)
        var finished = false
        val job = launch {
            coroutineContext[Job]!!.cancel()
            BoundedJpegDecoder.check(long)
            finished = true
        }
        job.join()
        assertFalse("cancellation must stop the marker scan", finished)
    }

    private val damaged = StillDecode.Skipped(StillSkip.DAMAGED)
    private val variant = BoundedJpegDecoder.Check.Rejected(StillSkip.UNSUPPORTED_VARIANT)

    private val broken = BoundedJpegDecoder.Check.Rejected(StillSkip.DAMAGED)

    private suspend fun check(bytes: ByteArray) = BoundedJpegDecoder.check(bytes)

    private fun decoded(result: StillDecode): VisualFacts {
        assertTrue("expected features, got $result", result is StillDecode.Decoded)
        return (result as StillDecode.Decoded).facts
    }

    private fun indexOfMarker(bytes: ByteArray, marker: Int): Int =
        (2 until bytes.size - 1).first { bytes[it] == (-1).toByte() && (bytes[it + 1].toInt() and 255) == marker }

    /** Removes the inserted EXIF APP1 so the stored pixels are decoded as they are. */
    private fun stripExif(bytes: ByteArray): ByteArray {
        val at = indexOfMarker(bytes, 0xe1)
        val length = ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            write(bytes, 0, at)
            write(bytes, at + 2 + length, bytes.size - at - 2 - length)
        }
        return output.toByteArray()
    }

    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        val consumed: Int get() = pos

        override fun close() {
            closed = true
            super.close()
        }
    }
}
