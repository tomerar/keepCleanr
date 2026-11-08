// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CanonicalPixelsTest {
    @Test
    fun `thin images are withheld even when content has high contrast`() {
        val bitmap = Bitmap.createBitmap(256, 4, Bitmap.Config.ARGB_8888)
        for (y in 0 until 4) {
            for (x in 0 until 256) {
                bitmap.setPixel(x, y, if (x < 128) Color.BLACK else Color.WHITE)
            }
        }
        assertTrue(CanonicalPixels.extract(bitmap, 256, 4, 1).lowDetail)
        assertTrue(CanonicalPixels.extract(bitmap, 256, 4, 6).lowDetail)
        val normal = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                normal.setPixel(x, y, if (x < 16) Color.BLACK else Color.WHITE)
            }
        }
        assertFalse(CanonicalPixels.extract(normal, 32, 32, 1).lowDetail)
    }

    @Test
    fun `wide and tall sources are padded without distortion`() {
        val black = IntArray(1024)
        assertArrayEquals(
            intArrayOf(255, 255, 255, 255, 0, 0, 0, 0, 0, 0, 0, 0, 255, 255, 255, 255),
            CanonicalPixels.letterbox(black, 2, 1, 4, 4),
        )
        assertArrayEquals(
            intArrayOf(255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255),
            CanonicalPixels.letterbox(black, 1, 2, 4, 4),
        )
    }

    @Test
    fun `all exif orientations map each pixel once`() {
        val pixels = (1..6).map { Color.rgb(it * 30, it * 30, it * 30) }.toIntArray()
        val expected = listOf(
            intArrayOf(30, 60, 90, 120, 150, 180),
            intArrayOf(90, 60, 30, 180, 150, 120),
            intArrayOf(180, 150, 120, 90, 60, 30),
            intArrayOf(120, 150, 180, 30, 60, 90),
            intArrayOf(30, 120, 60, 150, 90, 180),
            intArrayOf(120, 30, 150, 60, 180, 90),
            intArrayOf(180, 90, 150, 60, 120, 30),
            intArrayOf(90, 180, 60, 150, 30, 120),
        )
        for (orientation in 1..8) {
            val width = if (orientation >= 5) 2 else 3
            val height = if (orientation >= 5) 3 else 2
            assertArrayEquals(
                expected[orientation - 1],
                CanonicalPixels.sample(pixels, 3, 2, orientation, width, height),
            )
        }
    }

    @Test
    fun `transparent pixels are composited on white`() {
        val samples = CanonicalPixels.sample(intArrayOf(Color.TRANSPARENT, Color.BLACK), 2, 1, 1, 2, 1)
        assertArrayEquals(intArrayOf(255, 0), samples)
    }

    @Test
    fun `flat bitmap is low detail with stable finite quality and is not mutated`() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val first = CanonicalPixels.extract(bitmap, 640, 480, 6)
        assertEquals(first, CanonicalPixels.extract(bitmap, 640, 480, 6))
        assertTrue(first.lowDetail)
        assertEquals(0.0, first.quality.sharpness, 0.0)
        assertEquals(0.0, first.quality.clippedFraction, 0.0)
        assertEquals(0.75, first.aspect, 0.0)
        assertEquals(307200L, first.quality.usablePixels)
        assertFalse(bitmap.isRecycled)
        assertEquals(Color.GRAY, bitmap.getPixel(0, 0))
    }

    @Test
    fun `edge pattern has more measured sharpness than a flat image`() {
        val flat = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val edge = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) for (x in 0 until 32) edge.setPixel(x, y, if (x < 16) Color.BLACK else Color.WHITE)
        val flatFacts = CanonicalPixels.extract(flat, 32, 32, 1)
        val edgeFacts = CanonicalPixels.extract(edge, 32, 32, 1)
        assertTrue(edgeFacts.quality.sharpness > flatFacts.quality.sharpness)
        assertFalse(edgeFacts.lowDetail)
        assertEquals(1.0, edgeFacts.quality.clippedFraction, 0.0)
    }

    @Test
    fun `invalid dimensions orientation and recycled images are rejected`() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        assertThrows(IllegalArgumentException::class.java) { CanonicalPixels.extract(bitmap, 0, 2, 1) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalPixels.extract(bitmap, 100001, 1000, 1) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalPixels.extract(bitmap, 32, 32, 9) }
        val huge = Bitmap.createBitmap(513, 512, Bitmap.Config.ARGB_8888)
        assertThrows(IllegalArgumentException::class.java) { CanonicalPixels.extract(huge, 32, 32, 1) }
        bitmap.recycle()
        assertThrows(IllegalArgumentException::class.java) { CanonicalPixels.extract(bitmap, 32, 32, 1) }
    }
}
