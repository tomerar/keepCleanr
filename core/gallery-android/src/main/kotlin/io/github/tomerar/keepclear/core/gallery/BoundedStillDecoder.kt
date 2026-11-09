// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import io.github.tomerar.keepclear.engine.gallery.VisualFacts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.abs

/** Why a still image produced no features. A skip is never a "complete" or "nothing similar" result. */
internal enum class StillSkip {
    /** The content signature is not PNG or JPEG (for example GIF, WebP, HEIF/AVIF, BMP, TIFF/DNG). */
    UNSUPPORTED_FORMAT,

    /** A PNG/JPEG this policy does not analyze: animation, EXIF in PNG, progressive/CMYK/12-bit/arithmetic, extra data. */
    UNSUPPORTED_VARIANT,

    /** The encoded file exceeds the byte cap. */
    TOO_LARGE_FILE,

    /** The declared dimensions exceed the source-pixel bound. */
    TOO_LARGE_IMAGE,

    /** Malformed, truncated or incomplete data, or a platform decoder failure. */
    DAMAGED,
}

internal sealed interface StillDecode {
    data class Decoded(val facts: VisualFacts) : StillDecode

    data class Skipped(val reason: StillSkip) : StillDecode
}

/**
 * Provisional bounded still-image decoder. It dispatches on the content signature only; a file name or MIME type
 * never selects the decoder. The caller must run off the UI thread and authorize/revalidate the source around this
 * call. Consumes and closes the stream.
 */
internal object BoundedStillDecoder {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_SOURCE_PIXELS = 100_000_000L
    const val MAX_DECODED_PIXELS = 262_144L
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    suspend fun decode(stream: InputStream): StillDecode = stream.use { source ->
        val bytes = readBounded(source) ?: return StillDecode.Skipped(StillSkip.TOO_LARGE_FILE)
        when {
            isPng(bytes) -> BoundedPngDecoder.decodeBytes(bytes)
            isJpeg(bytes) -> BoundedJpegDecoder.decodeBytes(bytes)
            else -> StillDecode.Skipped(StillSkip.UNSUPPORTED_FORMAT)
        }
    }

    fun isPng(bytes: ByteArray): Boolean = bytes.size >= 8 && (0 until 8).all { bytes[it] == pngSignature[it] }

    /** SOI followed by the start of a marker. */
    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()

    /** Reads at most [MAX_BYTES]; returns null when the stream holds more. A stalled stream is an I/O failure. */
    suspend fun readBounded(source: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = MAX_BYTES - output.size()
            val count = source.read(buffer, 0, minOf(buffer.size, remaining + 1))
            if (count < 0) break
            if (count == 0) throw IOException("Stream returned no data") // Never spin forever.
            if (output.size() > MAX_BYTES - count) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /**
     * Decodes [bytes] whose header already passed format validation. [width] and [height] are the dimensions after
     * any orientation the platform applies; the platform must report exactly these, otherwise the image is skipped
     * rather than risking a second or missing orientation. The bitmap is passed to [CanonicalPixels] upright.
     */
    suspend fun decodeUpright(bytes: ByteArray, width: Int, height: Int): StillDecode {
        currentCoroutineContext().ensureActive()
        var sample = 1
        while ((width.toLong() + sample - 1) / sample * ((height.toLong() + sample - 1) / sample) >
            MAX_DECODED_PIXELS
        ) {
            sample *= 2
        }
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                require(info.size.width == width && info.size.height == height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sample)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return StillDecode.Skipped(StillSkip.DAMAGED)
        try {
            currentCoroutineContext().ensureActive()
            if (bitmap.width.toLong() * bitmap.height > MAX_DECODED_PIXELS ||
                abs(bitmap.width - width.toDouble() / sample) > 1.0 ||
                abs(bitmap.height - height.toDouble() / sample) > 1.0
            ) {
                return StillDecode.Skipped(StillSkip.DAMAGED)
            }
            val facts = CanonicalPixels.extract(bitmap, width, height, 1)
            currentCoroutineContext().ensureActive()
            return StillDecode.Decoded(facts)
        } finally {
            bitmap.recycle()
        }
    }
}
