// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Owner brief H2 item 12: whether a laid-out text breaks only between words (never "אוקטוב/ר"). */
fun TextLayoutResult.wrapsBetweenWords(): Boolean {
    val text = layoutInput.text.text
    for (line in 0 until lineCount - 1) {
        val end = getLineEnd(line)
        if (end <= 0 || end >= text.length) continue
        if (!isWordBoundary(text[end - 1]) && !isWordBoundary(text[end])) return false
    }
    return true
}

private fun isWordBoundary(c: Char) = c.isWhitespace() || c == '⁨' || c == '⁩' || c == '‏' || c == '-'

/**
 * Owner brief H2 item 12: a font size from [max] down to [min] (in [step]s) at which the text fits its box without
 * overflowing and wraps only between words; [min] when none does. For short labels in narrow places (the navigation
 * rail) at large font sizes.
 */
class WholeWordsAutoSize(
    private val min: TextUnit,
    private val max: TextUnit,
    private val step: TextUnit = 0.5.sp,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        var size = max.value
        while (size > min.value) {
            val layout = performLayout(constraints, text, size.sp)
            if (!layout.hasVisualOverflow && layout.wrapsBetweenWords()) return size.sp
            size -= step.value
        }
        return min
    }

    override fun equals(other: Any?): Boolean =
        other is WholeWordsAutoSize && other.min == min && other.max == max && other.step == step

    override fun hashCode(): Int = (min.hashCode() * 31 + max.hashCode()) * 31 + step.hashCode()
}

/**
 * Owner brief H2 item 12: a row of choices (chips) that wraps without leaving one choice alone on the last line
 * (the "הכול" chip on a line of its own). All in one line when they fit; otherwise the largest even split whose lines
 * fit, never with one item left over; when none fits, one choice per line (a column).
 */
@Composable
fun BalancedChipRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    lineSpacing: Dp = 4.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val gap = spacing.roundToPx()
        val vGap = lineSpacing.roundToPx()
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val lines = BalancedLines.split(placeables.map { it.width }, maxWidth, gap)
        val heights = lines.map { line -> line.maxOfOrNull { placeables[it].height } ?: 0 }
        val width = lines.maxOfOrNull { line -> line.sumOf { placeables[it].width } + gap * (line.size - 1) }
            ?.coerceIn(constraints.minWidth, maxWidth) ?: constraints.minWidth
        val height = (heights.sum() + vGap * (lines.size - 1).coerceAtLeast(0)).coerceAtLeast(constraints.minHeight)
        layout(width, height) {
            var y = 0
            lines.forEachIndexed { i, line ->
                var x = 0
                line.forEach { index ->
                    // placeRelative mirrors in right-to-left layouts.
                    placeables[index].placeRelative(x, y)
                    x += placeables[index].width + gap
                }
                y += heights[i] + vGap
            }
        }
    }
}

/** The line split of [BalancedChipRow], as indices per line (pure, tested). */
object BalancedLines {
    fun split(widths: List<Int>, maxWidth: Int, gap: Int): List<List<Int>> {
        val n = widths.size
        if (n == 0) return emptyList()

        fun fits(chunk: List<Int>) = chunk.sumOf { widths[it] } + gap * (chunk.size - 1) <= maxWidth
        val all = widths.indices.toList()
        if (fits(all)) return listOf(all)
        for (perLine in n - 1 downTo 2) {
            val chunks = all.chunked(perLine)
            if (chunks.last().size >= 2 && chunks.all(::fits)) return chunks
        }
        return all.map { listOf(it) }
    }
}
