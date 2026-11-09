// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Synthetic JPEGs generated in memory; no personal or downloaded images. */
internal object JpegFixtures {
    /** Distinct luminances so every one of the eight orientations gives a different arrangement. */
    val quadrants = intArrayOf(Color.rgb(250, 250, 250), Color.rgb(170, 170, 170), Color.rgb(90, 90, 90), Color.BLACK)

    /** The upright picture: 64×32, four 16-pixel-aligned quadrants. */
    fun upright(x: Int, y: Int, width: Int = 64, height: Int = 32): Int =
        quadrants[(if (y < height / 2) 0 else 2) + if (x < width / 2) 0 else 1]

    /**
     * Encodes stored pixels such that applying EXIF [orientation] shows [upright] (64×32). The mapping from a
     * displayed pixel to a stored pixel restates the EXIF definition; the device test checks the platform corners.
     */
    fun oriented(orientation: Int): ByteArray {
        val width = 64
        val height = 32
        val storedWidth = if (orientation >= 5) height else width
        val storedHeight = if (orientation >= 5) width else height
        val stored = IntArray(storedWidth * storedHeight)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (sx, sy) = when (orientation) {
                    1 -> x to y
                    2 -> storedWidth - 1 - x to y
                    3 -> storedWidth - 1 - x to storedHeight - 1 - y
                    4 -> x to storedHeight - 1 - y
                    5 -> y to x
                    6 -> y to storedHeight - 1 - x
                    7 -> storedWidth - 1 - y to storedHeight - 1 - x
                    else -> storedWidth - 1 - y to x
                }
                stored[sy * storedWidth + sx] = upright(x, y)
            }
        }
        return withExif(encode(storedWidth, storedHeight, stored), orientation)
    }

    fun encode(
        width: Int,
        height: Int,
        pixels: IntArray = IntArray(width * height) {
            upright(it % width, it / width, width, height)
        },
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
        bitmap.recycle()
        return output.toByteArray()
    }

    fun png(width: Int = 8, height: Int = 8): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        return output.toByteArray()
    }

    /** Inserts an APP1 EXIF block with one IFD0 entry right after SOI. */
    fun withExif(
        jpeg: ByteArray,
        orientation: Int,
        type: Int = 3,
        littleEndian: Boolean = false,
        copies: Int = 1,
    ): ByteArray {
        val tiff = ByteArrayOutputStream()
        DataOutputStream(tiff).apply {
            fun short(value: Int) = if (littleEndian) {
                write(value and 255)
                write(value shr 8 and 255)
            } else {
                writeShort(value)
            }

            fun int(value: Int) = if (littleEndian) {
                short(value and 0xffff)
                short(value ushr 16)
            } else {
                writeInt(value)
            }
            if (littleEndian) write(byteArrayOf(0x49, 0x49)) else write(byteArrayOf(0x4d, 0x4d))
            short(42)
            int(8)
            short(1)
            short(0x0112)
            short(type)
            int(1)
            short(orientation)
            short(0)
            int(0)
        }
        val segment = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) + tiff.toByteArray()
        var result = jpeg
        repeat(copies) { result = insertSegment(result, 0xe1, segment) }
        return result
    }

    fun insertSegment(jpeg: ByteArray, marker: Int, body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            write(jpeg, 0, 2)
            writeShort(0xff00 or marker)
            writeShort(body.size + 2)
            write(body)
            write(jpeg, 2, jpeg.size - 2)
        }
        return output.toByteArray()
    }

    /**
     * A structurally plausible JPEG skeleton for header-policy tests: SOI, one frame header, one scan and EOI. It is
     * not decodable (no tables), so it only exercises [BoundedJpegDecoder.check].
     */
    fun skeleton(
        frameMarker: Int = 0xc0,
        precision: Int = 8,
        width: Int = 16,
        height: Int = 16,
        components: Int = 3,
        entropy: ByteArray = byteArrayOf(0x12, 0x34),
        extra: List<Pair<Int, ByteArray>> = emptyList(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            writeShort(0xffd8)
            for ((marker, body) in extra) {
                writeShort(0xff00 or marker)
                writeShort(body.size + 2)
                write(body)
            }
            writeShort(0xff00 or frameMarker)
            writeShort(8 + 3 * components)
            write(precision)
            writeShort(height)
            writeShort(width)
            write(components)
            repeat(components) {
                write(it + 1)
                write(0x11)
                write(0)
            }
            writeShort(0xffda)
            writeShort(6 + 2 * components)
            write(components)
            repeat(components) {
                write(it + 1)
                write(0)
            }
            write(byteArrayOf(0, 63, 0))
            write(entropy)
            writeShort(0xffd9)
        }
        return output.toByteArray()
    }
}
