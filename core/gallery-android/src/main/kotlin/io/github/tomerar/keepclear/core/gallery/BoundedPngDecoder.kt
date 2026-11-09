// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import io.github.tomerar.keepclear.engine.gallery.VisualFacts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.util.zip.CRC32

/** Provisional primitive: caller must run off the UI thread and authorize/revalidate the source around this call. */
internal object BoundedPngDecoder {
    private const val MAX_CHUNKS = 4_096
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** Consumes and closes the stream. Unsupported, animated, EXIF-bearing or malformed input is skipped. */
    suspend fun decode(stream: InputStream): VisualFacts? = stream.use { source ->
        val bytes = BoundedStillDecoder.readBounded(source) ?: return null
        (decodeBytes(bytes) as? StillDecode.Decoded)?.facts
    }

    /** Decodes an already bounded byte array; PNG has no orientation, so the bitmap is upright as stored. */
    suspend fun decodeBytes(bytes: ByteArray): StillDecode {
        if (!BoundedStillDecoder.isPng(bytes)) return StillDecode.Skipped(StillSkip.UNSUPPORTED_FORMAT)
        check(bytes)?.let { return StillDecode.Skipped(it) }
        val headerWidth = uint32(bytes, 16)
        val headerHeight = uint32(bytes, 20)
        if (headerWidth == 0L || headerHeight == 0L) return StillDecode.Skipped(StillSkip.DAMAGED)
        if (headerWidth > BoundedStillDecoder.MAX_SOURCE_PIXELS ||
            headerHeight > BoundedStillDecoder.MAX_SOURCE_PIXELS ||
            headerWidth * headerHeight > BoundedStillDecoder.MAX_SOURCE_PIXELS
        ) {
            return StillDecode.Skipped(StillSkip.TOO_LARGE_IMAGE)
        }
        return BoundedStillDecoder.decodeUpright(bytes, headerWidth.toInt(), headerHeight.toInt())
    }

    /** Require complete CRC-valid chunks; never silently analyze the first frame of an animated PNG. */
    internal suspend fun isSupportedPng(bytes: ByteArray): Boolean = check(bytes) == null

    private suspend fun check(bytes: ByteArray): StillSkip? {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return StillSkip.UNSUPPORTED_FORMAT
        var offset = 8
        var first = true
        var idat = false
        var chunks = 0
        while (offset <= bytes.size - 12) {
            if (++chunks > MAX_CHUNKS) return StillSkip.DAMAGED
            if (chunks % 64 == 0) currentCoroutineContext().ensureActive()
            val length = uint32(bytes, offset)
            if (length > bytes.size.toLong() - offset - 12) return StillSkip.DAMAGED
            val end = offset + 12 + length.toInt()
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (first && (type != "IHDR" || length != 13L)) return StillSkip.DAMAGED
            if (!first && type == "IHDR") return StillSkip.DAMAGED
            if (type == "acTL" || type == "fcTL" || type == "fdAT" ||
                type == "eXIf"
            ) {
                return StillSkip.UNSUPPORTED_VARIANT
            }
            val crc = CRC32().apply { update(bytes, offset + 4, 4 + length.toInt()) }.value
            if (crc != uint32(bytes, end - 4)) return StillSkip.DAMAGED
            if (type == "IDAT") idat = true
            if (type == "IEND") return if (length == 0L && idat && end == bytes.size) null else StillSkip.DAMAGED
            first = false
            offset = end
        }
        return StillSkip.DAMAGED
    }

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255) }
}
