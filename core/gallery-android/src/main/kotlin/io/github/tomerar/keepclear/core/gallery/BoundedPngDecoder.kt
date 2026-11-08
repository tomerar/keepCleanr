// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import io.github.tomerar.keepclear.engine.gallery.VisualFacts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.CRC32

/** Provisional primitive: caller must run off the UI thread and authorize/revalidate the source around this call. */
internal object BoundedPngDecoder {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_CHUNKS = 4_096
    private const val MAX_PIXELS = 100_000_000L
    private const val MAX_DECODED_PIXELS = 262_144L
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** Consumes and closes the stream. Unsupported, animated, EXIF-bearing or malformed input is skipped. */
    suspend fun decode(stream: InputStream): VisualFacts? = stream.use { source ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = MAX_BYTES - output.size()
            val count = source.read(buffer, 0, minOf(buffer.size, remaining + 1))
            if (count < 0) break
            if (count == 0) return null // A broken stream must not spin forever.
            if (output.size() > MAX_BYTES - count) return null
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        if (!isSupportedPng(bytes)) return null
        val headerWidth = uint32(bytes, 16)
        val headerHeight = uint32(bytes, 20)
        if (headerWidth == 0L || headerHeight == 0L || headerWidth > MAX_PIXELS || headerHeight > MAX_PIXELS ||
            headerWidth * headerHeight > MAX_PIXELS
        ) {
            return null
        }
        currentCoroutineContext().ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        } catch (_: RuntimeException) {
            return null
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width.toLong() != headerWidth || height.toLong() != headerHeight) return null
        var sample = 1
        while ((width.toLong() + sample - 1) / sample * ((height.toLong() + sample - 1) / sample) >
            MAX_DECODED_PIXELS
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        try {
            currentCoroutineContext().ensureActive()
            if (bitmap.width.toLong() * bitmap.height > MAX_DECODED_PIXELS) return null
            val facts = CanonicalPixels.extract(bitmap, width, height, 1)
            currentCoroutineContext().ensureActive()
            facts
        } finally {
            bitmap.recycle()
        }
    }

    /** Require complete CRC-valid chunks; never silently analyze the first frame of an animated PNG. */
    internal suspend fun isSupportedPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return false
        var offset = 8
        var first = true
        var idat = false
        var chunks = 0
        while (offset <= bytes.size - 12) {
            if (++chunks > MAX_CHUNKS) return false
            if (chunks % 64 == 0) currentCoroutineContext().ensureActive()
            val length = uint32(bytes, offset)
            if (length > bytes.size.toLong() - offset - 12) return false
            val end = offset + 12 + length.toInt()
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (first && (type != "IHDR" || length != 13L)) return false
            if (!first && type == "IHDR") return false
            if (type == "acTL" || type == "fcTL" || type == "fdAT" || type == "eXIf") return false
            val crc = CRC32().apply { update(bytes, offset + 4, 4 + length.toInt()) }.value
            if (crc != uint32(bytes, end - 4)) return false
            if (type == "IDAT") idat = true
            if (type == "IEND") return length == 0L && idat && end == bytes.size
            first = false
            offset = end
        }
        return false
    }

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255) }
}
