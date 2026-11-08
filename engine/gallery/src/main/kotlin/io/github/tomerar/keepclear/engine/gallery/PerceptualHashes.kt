// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

import kotlin.math.abs
import kotlin.math.cos

/** Pure numeric primitives over already canonical, bounded 8-bit luminance samples. No image decoding. */
internal object PerceptualHashes {
    /** Row-major 9×8 samples, MSB first. A bit means left luminance > right luminance; ties are zero. */
    fun dHash(samples: IntArray): Long {
        validate(samples, 72)
        var hash = 0L
        for (row in 0 until 8) {
            for (column in 0 until 8) {
                hash = (hash shl 1) or if (samples[row * 9 + column] > samples[row * 9 + column + 1]) 1L else 0L
            }
        }
        return hash
    }

    /**
     * 32×32 samples, orthonormal DCT-II top-left 8×8, DC excluded. Median of the 63 AC values, strict >;
     * bit 63 stays zero. Coefficients below 1e-8 are zeroed for stable flat-image behavior. Version changes
     * are required for any resize/sample/DCT convention change. Flat hashes are NOT equality evidence.
     */
    fun pHash(samples: IntArray): Long {
        validate(samples, 1024)
        val coefficients = mutableListOf<Double>()
        for (v in 0 until 8) {
            for (u in 0 until 8) {
                if (u == 0 && v == 0) continue
                var sum = 0.0
                for (y in 0 until 32) {
                    for (x in 0 until 32) sum += samples[y * 32 + x] * basis[u][x] * basis[v][y]
                }
                coefficients += if (abs(sum) < 1e-8) 0.0 else sum
            }
        }
        val median = coefficients.sorted()[31]
        var hash = 0L
        coefficients.forEach { hash = (hash shl 1) or if (it > median) 1L else 0L }
        return hash
    }

    private val basis = Array(8) { frequency ->
        DoubleArray(32) { position ->
            val scale = if (frequency == 0) 1.0 / kotlin.math.sqrt(32.0) else 0.25
            scale * cos((2 * position + 1) * frequency * Math.PI / 64.0)
        }
    }

    private fun validate(samples: IntArray, size: Int) {
        require(samples.size == size && samples.all { it in 0..255 })
    }
}
