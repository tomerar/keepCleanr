// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import io.github.tomerar.keepclear.core.designsystem.component.StorageUsage
import io.github.tomerar.keepclear.core.designsystem.component.StripGeometry
import io.github.tomerar.keepclear.core.designsystem.component.StripSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StripGeometryTest {
    private val usage = StorageUsage(total = 100, free = 20, safe = 10, review = 5, move = 15)

    @Test
    fun `fractions are used, SAFE, REVIEW, MOVE, free and sum to one`() {
        val f = StripGeometry.fractions(usage)
        assertEquals(listOf(0.5f, 0.1f, 0.05f, 0.15f, 0.2f), f)
        assertEquals(1f, f.sum(), 1e-6f)
    }

    @Test
    fun `lanes larger than the phone are scaled to fit, never overflow`() {
        val f = StripGeometry.fractions(StorageUsage(total = 100, free = 50, safe = 100))
        assertEquals(1f, f.sum(), 1e-6f)
        assertEquals(0f, f[0])
    }

    @Test
    fun `an empty or unknown phone draws nothing`() {
        assertEquals(List(5) { 0f }, StripGeometry.fractions(StorageUsage(total = 0, free = 0)))
        assertEquals(List(5) { 0f }, StripGeometry.fractions(StorageUsage(total = -1, free = -5)))
    }

    @Test
    fun `left to right fills from the left, right to left from the right (start side)`() {
        val f = StripGeometry.fractions(usage)
        val ltr = StripGeometry.spans(f, width = 1000f, gap = 0f, rtl = false).toMap()
        val rtl = StripGeometry.spans(f, width = 1000f, gap = 0f, rtl = true).toMap()
        assertEquals(0f..500f, ltr[StripSegment.USED])
        assertEquals(500f..600f, ltr[StripSegment.SAFE])
        assertEquals(500f..1000f, rtl[StripSegment.USED])
        assertEquals(400f..500f, rtl[StripSegment.SAFE])
        // The free track is never drawn as a segment, and the gap is taken from each segment's end.
        assertTrue(StripSegment.FREE !in ltr)
        val gapped = StripGeometry.spans(f, width = 1000f, gap = 4f, rtl = false).toMap()
        assertEquals(0f..496f, gapped[StripSegment.USED])
    }
}
