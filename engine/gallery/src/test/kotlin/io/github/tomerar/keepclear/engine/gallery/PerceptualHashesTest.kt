// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PerceptualHashesTest {
    @Test
    fun `dHash golden bit order gradient ties and alternating columns`() {
        assertEquals(0L, PerceptualHashes.dHash(IntArray(72) { 128 }))
        assertEquals(0L, PerceptualHashes.dHash(IntArray(72) { it % 9 }))
        assertEquals(-1L, PerceptualHashes.dHash(IntArray(72) { 255 - it % 9 }))
        assertEquals(0x5555555555555555L, PerceptualHashes.dHash(IntArray(72) { (it % 9) % 2 * 255 }))
    }

    @Test
    fun `pHash golden excludes DC and handles flat and structured luminance`() {
        assertEquals(0L, PerceptualHashes.pHash(IntArray(1024)))
        assertEquals(0L, PerceptualHashes.pHash(IntArray(1024) { 255 }))
        // Independent Python scalar DCT-II oracle, row-major top-left AC coefficients, median index 31.
        val structured = IntArray(1024) { (it % 32 * 7 + it / 32 * 13) % 256 }
        assertEquals(0x0d3585376ae22d5aL, PerceptualHashes.pHash(structured))
    }

    @Test
    fun `malformed sample count and out of range luminance fail before computation`() {
        assertThrows(IllegalArgumentException::class.java) { PerceptualHashes.dHash(IntArray(73)) }
        assertThrows(IllegalArgumentException::class.java) { PerceptualHashes.pHash(IntArray(1024) { -1 }) }
        assertThrows(IllegalArgumentException::class.java) { PerceptualHashes.dHash(IntArray(72) { 256 }) }
    }
}
