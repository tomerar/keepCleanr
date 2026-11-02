// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcMotion
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
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

/**
 * The strip's segments, in order from the start side (design QA 2026-11-02): the three lanes first, then everything
 * else that is used, then free space. The legend lists them in the same order, so both mirror together in RTL.
 */
enum class StripSegment { SAFE, REVIEW, MOVE, OTHER, FREE }

/** One legend entry: the segment, its size in bytes and its share of the whole bar. */
data class StripPart(val segment: StripSegment, val bytes: Long, val fraction: Float)

/** Pure geometry of the strip, tested on the JVM (start-side fill, RTL mirroring, gaps). */
internal object StripGeometry {
    /** Bytes per segment, in [StripSegment] order. */
    fun values(u: StorageUsage): List<Long> =
        listOf(u.safe, u.review, u.move, u.other, u.free).map { it.coerceAtLeast(0) }

    /**
     * Fractions of the whole bar per segment, in [StripSegment] order: each segment's bytes over the phone's total
     * capacity. They sum to 1 (or 0 for an empty phone).
     */
    fun fractions(u: StorageUsage): List<Float> {
        if (u.total <= 0) return List(StripSegment.entries.size) { 0f }
        val parts = values(u)
        // Lanes measured on several volumes can add up to more than the phone's total: scale to fit, never overflow.
        val whole = maxOf(u.total, parts.sum())
        return parts.map { (it.toDouble() / whole).toFloat() }
    }

    /** The legend: the same segments, bytes and fractions as the bar, in the same order. */
    fun parts(u: StorageUsage): List<StripPart> {
        val f = fractions(u)
        return StripSegment.entries.mapIndexed { i, segment -> StripPart(segment, values(u)[i], f[i]) }
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

/** Test hook: the strip's drawn segments in px (without gaps), as the canvas draws them right now. */
val StripSpansKey = SemanticsPropertyKey<List<Pair<StripSegment, ClosedFloatingPointRange<Float>>>>("StripSpans")

/** Test hook: a legend entry's segment and bytes. */
val StripLegendKey = SemanticsPropertyKey<StripPart>("StripLegend")

/**
 * The storage strip (design-system.md §8): a thick rounded bar that fills from the **start** side (right in Hebrew)
 * with SAFE, REVIEW, MOVE and other used segments, each as wide as its share of the phone's capacity; the track is
 * free space. [StorageLegend] names them in the same order. With [afterCleaning] the SAFE segment is drawn
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
    var widthPx by remember { mutableIntStateOf(0) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(KcShapes.pill)
            .onSizeChanged { widthPx = it.width }
            .clearAndSetSemantics {
                contentDescription = description
                this[StripSpansKey] = StripGeometry.spans(targets, widthPx.toFloat(), 0f, rtl)
            },
    ) {
        drawRect(colors.stripFree)
        val fractions = if (reduced) targets else animated.map { it.value }
        val gap = 2.dp.toPx()
        StripGeometry.spans(fractions, size.width, gap, rtl).forEach { (segment, span) ->
            val color: Color = when (segment) {
                StripSegment.OTHER -> colors.stripUsed
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

/**
 * The strip's legend (design QA 2026-11-02): one entry per segment, in the strip's order from the start side, each a
 * colour dot, the lane's name from [labels] and its size from [sizeText] (e.g. „לנקות 7.9GB”). Wraps on narrow
 * screens; mirrors in RTL with the strip. The dots are decorative: the words carry the meaning (never colour alone).
 */
@Composable
fun StorageLegend(
    usage: StorageUsage,
    labels: Map<StripSegment, String>,
    sizeText: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = KeepClearTheme.colors
    val outline = MaterialTheme.colorScheme.outline
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KcSpacing.m),
        verticalArrangement = Arrangement.spacedBy(KcSpacing.xs),
    ) {
        StripGeometry.parts(usage).forEach { part ->
            val label = labels[part.segment] ?: return@forEach
            val dot = when (part.segment) {
                StripSegment.SAFE -> colors.safe.accent
                StripSegment.REVIEW -> colors.review.accent
                StripSegment.MOVE -> colors.move.accent
                StripSegment.OTHER -> colors.stripUsed
                StripSegment.FREE -> colors.stripFree
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .testTag("legend_${part.segment.name}")
                    .semantics(mergeDescendants = true) { this[StripLegendKey] = part },
            ) {
                Canvas(Modifier.size(10.dp)) {
                    drawCircle(dot)
                    // The free dot is as light as the track: an outline keeps it visible on paper.
                    if (part.segment == StripSegment.FREE) drawCircle(outline, style = Stroke(1.dp.toPx()))
                }
                Spacer(Modifier.width(KcSpacing.xs))
                Text(
                    "$label ${sizeText(part.bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
