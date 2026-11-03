// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import io.github.tomerar.keepclear.core.designsystem.component.BalancedLines
import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner brief H2 item 12: a wrapped row of choices never leaves one choice alone on its line. */
class BalancedLinesTest {
    private data class Case(val widths: List<Int>, val max: Int, val expected: List<List<Int>>)

    private val gap = 10

    private val cases = listOf(
        // Everything fits: one line.
        Case(listOf(100, 100, 40), 300, listOf(listOf(0, 1, 2))),
        // Positive: the old wrap would leave "All" (2) alone; three lines of one instead.
        Case(listOf(150, 150, 40), 320, listOf(listOf(0), listOf(1), listOf(2))),
        // Four that fit two by two.
        Case(listOf(100, 100, 100, 100), 250, listOf(listOf(0, 1), listOf(2, 3))),
        // Five: three and two, never four and one.
        Case(listOf(60, 60, 60, 60, 60), 260, listOf(listOf(0, 1, 2), listOf(3, 4))),
        // Negative: one item, and an item wider than the line, stay as they are.
        Case(listOf(400), 300, listOf(listOf(0))),
        Case(emptyList(), 300, emptyList()),
    )

    @Test
    fun `table of splits`() {
        cases.forEach { c -> assertEquals(c.toString(), c.expected, BalancedLines.split(c.widths, c.max, gap)) }
    }

    @Test
    fun `no split ever has a single item on its last line next to longer lines`() {
        val random = java.util.Random(7)
        repeat(500) {
            val n = 2 + random.nextInt(6)
            val widths = List(n) { 20 + random.nextInt(200) }
            val lines = BalancedLines.split(widths, 100 + random.nextInt(400), gap)
            assertEquals(widths.indices.toList(), lines.flatten())
            if (lines.size > 1 &&
                lines.last().size == 1
            ) {
                assertEquals(lines.toString(), true, lines.all { it.size == 1 })
            }
        }
    }
}
