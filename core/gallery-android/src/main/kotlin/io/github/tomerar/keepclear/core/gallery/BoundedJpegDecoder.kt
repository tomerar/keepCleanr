// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Provisional bounded JPEG still decoder (C200–C203). The marker structure is validated, the declared dimensions are
 * bounded and the EXIF orientation is read before any bitmap is allocated. Only a single-frame, 8-bit, Huffman-coded
 * sequential (baseline or extended) JPEG with one or three components and nothing after its end marker is analyzed.
 *
 * Android's ImageDecoder applies the EXIF orientation itself (C200, JpegOrientationDeviceTest on API 30/36), so the
 * decoded bitmap is upright and [CanonicalPixels] receives orientation 1. The platform must report the oriented size
 * computed here; if it does not, the image is skipped instead of being oriented twice or not at all.
 */
internal object BoundedJpegDecoder {
    private const val MAX_SEGMENTS = 4_096
    private const val CANCEL_CHECK_BYTES = 64 * 1024

    internal sealed interface Check {
        data class Ok(val width: Int, val height: Int, val orientation: Int) : Check

        data class Rejected(val reason: StillSkip) : Check
    }

    suspend fun decodeBytes(bytes: ByteArray): StillDecode {
        if (!BoundedStillDecoder.isJpeg(bytes)) return StillDecode.Skipped(StillSkip.UNSUPPORTED_FORMAT)
        val header = when (val check = check(bytes)) {
            is Check.Rejected -> return StillDecode.Skipped(check.reason)
            is Check.Ok -> check
        }
        val swap = header.orientation >= 5
        return BoundedStillDecoder.decodeUpright(
            bytes,
            if (swap) header.height else header.width,
            if (swap) header.width else header.height,
        )
    }

    /** Validates the whole marker structure up to and including EOI without decoding entropy-coded data. */
    internal suspend fun check(bytes: ByteArray): Check {
        if (!BoundedStillDecoder.isJpeg(bytes)) return Check.Rejected(StillSkip.UNSUPPORTED_FORMAT)
        var offset = 2
        var segments = 0
        var frame: Check.Ok? = null
        var orientation: Int? = null
        var scans = 0
        var sinceCheck = 0
        while (true) {
            if (offset >= bytes.size || u8(bytes, offset) != 0xff) return damaged()
            while (offset < bytes.size && u8(bytes, offset) == 0xff) offset++ // Fill bytes.
            if (offset >= bytes.size) return damaged()
            val marker = u8(bytes, offset++)
            if (++segments > MAX_SEGMENTS) return damaged()
            if (segments % 64 == 0) currentCoroutineContext().ensureActive()
            when (marker) {
                // EOI: anything after it (a Motion Photo video, MPF/Ultra HDR secondary images, unknown data) means
                // the file is more than this still frame; it is not analyzed (C202).
                0xd9 -> {
                    val ok = frame ?: return damaged()
                    if (scans == 0) return damaged()
                    if (offset != bytes.size) return Check.Rejected(StillSkip.UNSUPPORTED_VARIANT)
                    return ok.copy(orientation = orientation ?: 1)
                }

                0xd8, 0x01, in 0xd0..0xd7 -> {
                    return damaged()
                } // SOI again, TEM or RST outside a scan.
            }
            if (offset + 2 > bytes.size) return damaged()
            val length = u16(bytes, offset)
            if (length < 2 || length > bytes.size - offset) return damaged()
            val body = offset + 2
            val end = offset + length
            when (marker) {
                0xc0, 0xc1 -> {
                    if (frame != null || scans > 0) return damaged()
                    frame = when (val parsed = frame(bytes, body, end)) {
                        is Check.Rejected -> return parsed
                        is Check.Ok -> parsed
                    }
                }

                // Progressive (C201), lossless, hierarchical and arithmetic-coded frames, DNL and reserved JPG
                // extensions are outside the bounded still policy.
                0xc2, 0xc3, in 0xc5..0xcf, 0xdc, 0xde, 0xdf, in 0xf0..0xfd -> {
                    return Check.Rejected(StillSkip.UNSUPPORTED_VARIANT)
                }

                0xe1 -> {
                    if (startsWith(bytes, body, end, EXIF)) {
                        // Two EXIF blocks could disagree about orientation; never guess which one the platform uses.
                        if (orientation != null) return Check.Rejected(StillSkip.UNSUPPORTED_VARIANT)
                        orientation = exifOrientation(bytes, body + EXIF.size, end) ?: return damaged()
                    }
                }

                0xe2 -> {
                    if (startsWith(bytes, body, end, MPF)) return Check.Rejected(StillSkip.UNSUPPORTED_VARIANT)
                }

                0xda -> {
                    if (frame == null) return damaged()
                    val components = if (length >= 3) u8(bytes, body) else 0
                    if (components !in 1..4 || length != 6 + 2 * components) return damaged()
                    scans++
                    // Entropy-coded data runs until a marker other than a stuffed byte, fill byte or restart.
                    var position = end
                    while (true) {
                        if (++sinceCheck >= CANCEL_CHECK_BYTES) {
                            sinceCheck = 0
                            currentCoroutineContext().ensureActive()
                        }
                        if (position >= bytes.size - 1) return damaged() // Truncated before EOI.
                        if (u8(bytes, position) != 0xff) {
                            position++
                            continue
                        }
                        val next = u8(bytes, position + 1)
                        if (next == 0x00 || next in 0xd0..0xd7) {
                            position += 2
                        } else if (next == 0xff) {
                            position++
                        } else {
                            break
                        }
                    }
                    offset = position
                    continue
                }

                // DHT, DQT, DRI, COM, other APPn: structure checked by length only; the platform decoder parses them.
                0xc4, 0xdb, 0xdd, 0xfe, in 0xe0..0xef -> {}

                else -> {
                    return damaged()
                }
            }
            offset = end
        }
    }

    private fun frame(bytes: ByteArray, body: Int, end: Int): Check {
        if (end - body < 6) return damaged()
        val precision = u8(bytes, body)
        val height = u16(bytes, body + 1)
        val width = u16(bytes, body + 3)
        val components = u8(bytes, body + 5)
        if (end - body != 6 + 3 * components) return damaged()
        if (precision != 8) return Check.Rejected(StillSkip.UNSUPPORTED_VARIANT) // 12-bit.
        // One (grey) or three (YCbCr/RGB) components; CMYK/YCCK (four) has no reliable colour conversion here (C201).
        if (components != 1 && components != 3) return Check.Rejected(StillSkip.UNSUPPORTED_VARIANT)
        if (height == 0) return Check.Rejected(StillSkip.UNSUPPORTED_VARIANT) // Height defined later by DNL.
        if (width == 0) return damaged()
        for (index in 0 until components) {
            val sampling = u8(bytes, body + 6 + 3 * index + 1)
            val table = u8(bytes, body + 6 + 3 * index + 2)
            if (sampling shr 4 !in 1..4 || sampling and 15 !in 1..4 || table > 3) return damaged()
        }
        if (width.toLong() * height > BoundedStillDecoder.MAX_SOURCE_PIXELS) {
            return Check.Rejected(StillSkip.TOO_LARGE_IMAGE)
        }
        return Check.Ok(width, height, 1)
    }

    /** Orientation from IFD0 of a TIFF block; 1 when absent, null when the block or the value is malformed. */
    private fun exifOrientation(bytes: ByteArray, start: Int, end: Int): Int? {
        if (end - start < 8) return null
        val little = when {
            u8(bytes, start) == 0x49 && u8(bytes, start + 1) == 0x49 -> true
            u8(bytes, start) == 0x4d && u8(bytes, start + 1) == 0x4d -> false
            else -> return null
        }

        fun short(at: Int) = if (little) u8(bytes, at) or (u8(bytes, at + 1) shl 8) else u16(bytes, at)

        fun int(at: Int): Long = if (little) {
            (short(at).toLong()) or (short(at + 2).toLong() shl 16)
        } else {
            (u16(bytes, at).toLong() shl 16) or u16(bytes, at + 2).toLong()
        }
        if (short(start + 2) != 42) return null
        val ifd = int(start + 4)
        if (ifd < 8 || ifd > end - start - 2L) return null
        val entries = start + ifd.toInt()
        val count = short(entries)
        if (count == 0 || 2L + 12L * count > end - entries) return null
        var found: Int? = null
        for (index in 0 until count) {
            val entry = entries + 2 + 12 * index
            if (short(entry) != 0x0112) continue
            if (found != null || short(entry + 2) != 3 || int(entry + 4) != 1L) return null
            found = short(entry + 8).takeIf { it in 1..8 } ?: return null
        }
        return found ?: 1
    }

    private fun startsWith(bytes: ByteArray, body: Int, end: Int, prefix: ByteArray): Boolean =
        end - body >= prefix.size && prefix.indices.all { bytes[body + it] == prefix[it] }

    private fun damaged() = Check.Rejected(StillSkip.DAMAGED)

    private fun u8(bytes: ByteArray, at: Int) = bytes[at].toInt() and 255

    private fun u16(bytes: ByteArray, at: Int) = (u8(bytes, at) shl 8) or u8(bytes, at + 1)

    private val EXIF = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"
    private val MPF = byteArrayOf(0x4d, 0x50, 0x46, 0) // "MPF\0"
}
