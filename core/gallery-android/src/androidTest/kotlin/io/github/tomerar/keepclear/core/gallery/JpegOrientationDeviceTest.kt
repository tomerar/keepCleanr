// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Platform behavior needed before JPEG source support is enabled. Images are generated in memory. */
@RunWith(AndroidJUnit4::class)
class JpegOrientationDeviceTest {
    @Test
    fun allExifOrientationsAreAppliedExactlyOnce() {
        val expected = listOf(
            intArrayOf(0, 1, 2, 3),
            intArrayOf(1, 0, 3, 2),
            intArrayOf(3, 2, 1, 0),
            intArrayOf(2, 3, 0, 1),
            intArrayOf(0, 2, 1, 3),
            intArrayOf(2, 0, 3, 1),
            intArrayOf(3, 1, 2, 0),
            intArrayOf(1, 3, 0, 2),
        )
        for (orientation in 1..8) {
            val encoded = jpeg(orientation)
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) {
                decoder,
                _,
                _,
                ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
            try {
                val expectedWidth = if (orientation >= 5) 20 else 40
                val expectedHeight = if (orientation >= 5) 40 else 20
                assertEquals("width orientation $orientation", expectedWidth, decoded.width)
                assertEquals("height orientation $orientation", expectedHeight, decoded.height)
                val actual = intArrayOf(
                    nearest(decoded.getPixel(decoded.width / 4, decoded.height / 4)),
                    nearest(decoded.getPixel(decoded.width * 3 / 4, decoded.height / 4)),
                    nearest(decoded.getPixel(decoded.width / 4, decoded.height * 3 / 4)),
                    nearest(decoded.getPixel(decoded.width * 3 / 4, decoded.height * 3 / 4)),
                )
                assertArrayEquals("orientation $orientation", expected[orientation - 1], actual)
            } finally {
                decoded.recycle()
            }
        }
    }

    private val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)

    private fun nearest(pixel: Int): Int = colors.indices.minBy { index ->
        val candidate = colors[index]
        val red = Color.red(pixel) - Color.red(candidate)
        val green = Color.green(pixel) - Color.green(candidate)
        val blue = Color.blue(pixel) - Color.blue(candidate)
        red * red + green * green + blue * blue
    }

    private fun jpeg(orientation: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val index = (if (y < 10) 0 else 2) + if (x < 20) 0 else 1
                bitmap.setPixel(x, y, colors[index])
            }
        }
        val encoded = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, encoded))
        bitmap.recycle()
        val jpeg = encoded.toByteArray()
        check(jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        val exif = ByteArrayOutputStream()
        DataOutputStream(exif).apply {
            write(byteArrayOf(69, 120, 105, 102, 0, 0)) // Exif\0\0
            writeShort(0x4d4d) // Big-endian TIFF.
            writeShort(42)
            writeInt(8)
            writeShort(1)
            writeShort(0x0112) // Orientation.
            writeShort(3) // SHORT.
            writeInt(1)
            writeShort(orientation)
            writeShort(0)
            writeInt(0)
        }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            write(jpeg, 0, 2)
            writeShort(0xffe1)
            writeShort(exif.size() + 2)
            write(exif.toByteArray())
            write(jpeg, 2, jpeg.size - 2)
        }
        return output.toByteArray()
    }
}
