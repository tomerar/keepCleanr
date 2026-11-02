// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcMotion
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme

/**
 * Where the phone's space is, in bytes. [total] is the phone's size, [free] what is free now; [safe], [review] and
 * [move] are the lanes' sizes; [other] is everything else that is used.
 */
data class StorageUsage(
    val total: Long,
    val free: Long,
    val safe: Long = 0,
    val review: Long = 0,
    val move: Long = 0,
) {
    val other: Long get() = (total - free - safe - review - move).coerceAtLeast(0)
}

/** The strip's segments, in order from the start side. */
enum class StripSegment { USED, SAFE, REVIEW, MOVE, FREE }

/** Pure geometry of the strip, tested on the JVM (start-side fill, RTL mirroring, gaps). */
internal object StripGeometry {
    /** Fractions of the whole bar per segment, in [StripSegment] order; they sum to 1 (or 0 for an empty phone). */
    fun fractions(u: StorageUsage): List<Float> {
        if (u.total <= 0) return List(StripSegment.entries.size) { 0f }
        val parts = listOf(u.other, u.safe, u.review, u.move, u.free).map { it.coerceAtLeast(0) }
        // Lanes measured on several volumes can add up to more than the phone's total: scale to fit, never overflow.
        val whole = maxOf(u.total, parts.sum())
        return parts.map { (it.toDouble() / whole).toFloat() }
    }

    /** Left and right edges (px) of each visible segment except FREE (the track), mirrored for [rtl]. */
    fun spans(
        fractions: List<Float>,
        width: Float,
        gap: Float,
        rtl: Boolean,
    ): List<Pair<StripSegment, ClosedFloatingPointRange<Float>>> {
        val out = mutableListOf<Pair<StripSegment, ClosedFloatingPointRange<Float>>>()
        var start = 0f
        fractions.forEachIndexed { i, f ->
            val segment = StripSegment.entries[i]
            val w = f * width
            if (segment != StripSegment.FREE && w > 0f) {
                val end = (start + w - gap).coerceAtLeast(start + minOf(w, 1f))
                out += segment to if (rtl) (width - end)..(width - start) else start..end
            }
            start += w
        }
        return out
    }
}

/**
 * The storage strip (design-system.md §8): a thick rounded bar that fills from the **start** side (right in Hebrew)
 * with used, SAFE, REVIEW and MOVE segments; the track is free space. With [afterCleaning] the SAFE segment is drawn
 * as a ghost (outline + light fill): what will be free after cleaning. Segments slide (spring) when the numbers change,
 * and jump under reduced motion. [description] is the whole picture in words for TalkBack (the app formats it).
 */
@Composable
fun StorageStrip(
    usage: StorageUsage,
    description: String,
    modifier: Modifier = Modifier,
    afterCleaning: Boolean = false,
    height: Dp = 14.dp,
) {
    val colors = KeepClearTheme.colors
    val reduced = KeepClearTheme.reducedMotion
    val targets = StripGeometry.fractions(usage)
    val animated: List<State<Float>> = targets.mapIndexed { i, target ->
        animateFloatAsState(
            targetValue = target,
            animationSpec = if (reduced) snap() else KcMotion.layout(),
            label = "strip$i",
        )
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(KcShapes.pill)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        drawRect(colors.stripFree)
        val fractions = if (reduced) targets else animated.map { it.value }
        val gap = 2.dp.toPx()
        StripGeometry.spans(fractions, size.width, gap, rtl).forEach { (segment, span) ->
            val color: Color = when (segment) {
                StripSegment.USED -> colors.stripUsed
                StripSegment.SAFE -> colors.safe.accent
                StripSegment.REVIEW -> colors.review.accent
                StripSegment.MOVE -> colors.move.accent
                StripSegment.FREE -> colors.stripFree
            }
            val topLeft = Offset(span.start, 0f)
            val segmentSize = Size(span.endInclusive - span.start, size.height)
            if (segment == StripSegment.SAFE && afterCleaning) {
                drawRect(color.copy(alpha = 0.25f), topLeft, segmentSize)
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = color,
                    topLeft = topLeft + Offset(stroke / 2, stroke / 2),
                    size = Size(segmentSize.width - stroke, segmentSize.height - stroke),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(width = stroke),
                )
            } else {
                drawRect(color, topLeft, segmentSize)
            }
        }
    }
}
