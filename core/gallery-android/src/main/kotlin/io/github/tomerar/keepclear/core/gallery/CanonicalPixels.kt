// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.graphics.Bitmap
import io.github.tomerar.keepclear.engine.gallery.PerceptualHashes
import io.github.tomerar.keepclear.engine.gallery.QualityFacts
import io.github.tomerar.keepclear.engine.gallery.VisualFacts

/** Versioned numeric extraction only. The caller owns the bitmap and must authorize and bound the decode. */
internal object CanonicalPixels {
    private const val MAX_DECODED_PIXELS = 262_144L
    private const val MAX_SOURCE_PIXELS = 100_000_000L

    fun extract(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int, orientation: Int): VisualFacts {
        require(!bitmap.isRecycled && bitmap.width.toLong() * bitmap.height <= MAX_DECODED_PIXELS)
        require(sourceWidth > 0 && sourceHeight > 0 && sourceWidth.toLong() * sourceHeight <= MAX_SOURCE_PIXELS)
        require(orientation in 1..8)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val samples = sample(pixels, bitmap.width, bitmap.height, orientation, 32, 32)
        val orientedWidth = if (orientation >= 5) sourceHeight else sourceWidth
        val orientedHeight = if (orientation >= 5) sourceWidth else sourceHeight
        val hashSamples = letterbox(samples, orientedWidth, orientedHeight, 32, 32)
        val small = letterbox(samples, orientedWidth, orientedHeight, 9, 8)
        // A very thin image can fall between every grid center. Do not allow
        // its mostly padding hashes to establish a visual match.
        val shortFraction = minOf(orientedWidth, orientedHeight).toDouble() / maxOf(orientedWidth, orientedHeight)
        val shortGridSide = if (orientedWidth >= orientedHeight) 8 else 9
        val inset = (1.0 - shortFraction) / 2.0
        val contentSamples = (0 until shortGridSide).count {
            val center = (it + 0.5) / shortGridSide
            center >= inset && center < inset + shortFraction
        }
        val insufficientContent = contentSamples < 2
        val mean = samples.average()
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        var laplacianSum = 0.0
        var laplacianSquares = 0.0
        var count = 0
        for (y in 1 until 31) {
            for (x in 1 until 31) {
                val i = y * 32 + x
                val laplacian = samples[i - 1] + samples[i + 1] + samples[i - 32] + samples[i + 32] - 4 * samples[i]
                laplacianSum += laplacian
                laplacianSquares += laplacian.toDouble() * laplacian
                count++
            }
        }
        val sharpness = (laplacianSquares / count - (laplacianSum / count) * (laplacianSum / count)).coerceAtLeast(0.0)
        val clipped = samples.count { it <= 2 || it >= 253 }.toDouble() / samples.size
        val aspect = if (orientation >=
            5
        ) {
            sourceHeight.toDouble() / sourceWidth
        } else {
            sourceWidth.toDouble() / sourceHeight
        }
        return VisualFacts(
            dHash = PerceptualHashes.dHash(small),
            pHash = PerceptualHashes.pHash(hashSamples),
            aspect = aspect,
            lowDetail = variance < 16.0 || insufficientContent,
            quality = QualityFacts(sourceWidth.toLong() * sourceHeight, sharpness, clipped),
        )
    }

    /** Fit the full image into a white square without distortion; quality facts exclude this padding. */
    internal fun letterbox(
        content: IntArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray {
        require(content.size == 1024 && width > 0 && height > 0 && targetWidth > 0 && targetHeight > 0)
        val scale = maxOf(width, height).toDouble()
        val contentWidth = width / scale
        val contentHeight = height / scale
        val left = (1.0 - contentWidth) / 2.0
        val top = (1.0 - contentHeight) / 2.0
        return IntArray(targetWidth * targetHeight) { index ->
            val x = (index % targetWidth + 0.5) / targetWidth
            val y = (index / targetWidth + 0.5) / targetHeight
            if (x < left || x >= left + contentWidth || y < top || y >= top + contentHeight) {
                255
            } else {
                val sx = (((x - left) / contentWidth) * 32).toInt().coerceIn(0, 31)
                val sy = (((y - top) / contentHeight) * 32).toInt().coerceIn(0, 31)
                content[sy * 32 + sx]
            }
        }
    }

    /** Center sampling across the entire oriented image; no center crop. Alpha is composited on white. */
    internal fun sample(
        pixels: IntArray,
        width: Int,
        height: Int,
        orientation: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
        require(orientation in 1..8 && targetWidth > 0 && targetHeight > 0)
        val orientedWidth = if (orientation >= 5) height else width
        val orientedHeight = if (orientation >= 5) width else height
        return IntArray(targetWidth * targetHeight) { i ->
            val x = ((2L * (i % targetWidth) + 1) * orientedWidth / (2L * targetWidth)).toInt()
            val y = ((2L * (i / targetWidth) + 1) * orientedHeight / (2L * targetHeight)).toInt()
            val sx = when (orientation) {
                1, 4 -> x
                2, 3 -> width - 1 - x
                5, 6 -> y
                else -> width - 1 - y
            }
            val sy = when (orientation) {
                1, 2 -> y
                3, 4 -> height - 1 - y
                5, 8 -> x
                else -> height - 1 - x
            }
            val color = pixels[sy * width + sx]
            val alpha = color ushr 24

            fun onWhite(channel: Int) = (channel * alpha + 255 * (255 - alpha) + 127) / 255
            val red = onWhite((color ushr 16) and 255)
            val green = onWhite((color ushr 8) and 255)
            val blue = onWhite(color and 255)
            (54 * red + 183 * green + 19 * blue + 128) shr 8
        }
    }
}
