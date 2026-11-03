// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme

/** The three intro pictures (owner brief H2 item 1). */
enum class IntroPicture {
    /** The storage strip getting shorter: what cleaning frees, as an outlined ghost. */
    STRIP,

    /** A hand choosing photos: two tiles carry a check circle. */
    CHOOSE,

    /** Photos going into a USB stick, with the check stamp. */
    BACKUP,
}

/**
 * Owner brief H2 item 1: a simple "Paper & Ink" line drawing per intro page, drawn here from theme tokens only (ink,
 * outline, paper and the lane accents), so it follows light and dark. A lane colour always comes with a shape (an
 * outline, a check circle, a stamp), never colour alone. No brooms, rockets or shields. Decorative: TalkBack reads
 * the page's words.
 */
@Composable
fun IntroIllustration(picture: IntroPicture, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outline
    val paper = MaterialTheme.colorScheme.surfaceContainerHigh
    val lanes = KeepClearTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1.6f).clearAndSetSemantics { }) {
        // Drawn on a 160 × 100 grid, mirrored in right-to-left layouts like the rest of the UI.
        val k = size.width / 160f
        scale(scaleX = if (rtl) -1f else 1f, scaleY = 1f) {
            when (picture) {
                IntroPicture.STRIP -> strip(k, ink, line, paper, lanes.safe.accent, lanes.stripUsed)
                IntroPicture.CHOOSE -> choose(k, ink, line, paper, lanes.review.accent)
                IntroPicture.BACKUP -> backup(k, ink, line, paper, lanes.move.accent)
            }
        }
    }
}

private typealias C = androidx.compose.ui.graphics.Color

private fun DrawScope.rr(k: Float, x: Float, y: Float, w: Float, h: Float, r: Float, color: C, stroke: Float? = null) =
    drawRoundRect(
        color,
        Offset(x * k, y * k),
        Size(w * k, h * k),
        CornerRadius(r * k),
        style = stroke?.let { Stroke(it * k, cap = StrokeCap.Round, join = StrokeJoin.Round) }
            ?: androidx.compose.ui.graphics.drawscope.Fill,
    )

private fun DrawScope.check(k: Float, cx: Float, cy: Float, r: Float, color: C, paper: C) {
    drawCircle(color, r * k, Offset(cx * k, cy * k))
    val p = Path().apply {
        moveTo((cx - r * 0.45f) * k, cy * k)
        lineTo((cx - r * 0.1f) * k, (cy + r * 0.35f) * k)
        lineTo((cx + r * 0.5f) * k, (cy - r * 0.35f) * k)
    }
    drawPath(p, paper, style = Stroke(r * 0.22f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Before: a long used bar. After: a shorter one, the freed part an outlined (dashed) ghost in the SAFE colour. */
private fun DrawScope.strip(k: Float, ink: C, line: C, paper: C, safe: C, used: C) {
    rr(k, 14f, 12f, 132f, 76f, 8f, paper)
    rr(k, 14f, 12f, 132f, 76f, 8f, line, stroke = 1.2f)
    // Before.
    rr(k, 28f, 30f, 104f, 12f, 6f, line, stroke = 1.2f)
    rr(k, 28f, 30f, 88f, 12f, 6f, used)
    // After: the used part is shorter; what was freed stays as an outline.
    rr(k, 28f, 58f, 104f, 12f, 6f, line, stroke = 1.2f)
    rr(k, 28f, 58f, 56f, 12f, 6f, used)
    drawRoundRect(
        safe,
        Offset(86f * k, 58f * k),
        Size(30f * k, 12f * k),
        CornerRadius(6f * k),
        style = Stroke(1.6f * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * k, 2.5f * k))),
    )
    // An arrow from before to after.
    val a = Path().apply {
        moveTo(122f * k, 44f * k)
        quadraticTo(132f * k, 50f * k, 120f * k, 56f * k)
    }
    drawPath(a, ink, style = Stroke(1.6f * k, cap = StrokeCap.Round))
    val head = Path().apply {
        moveTo(124f * k, 57.5f * k)
        lineTo(119.5f * k, 56.3f * k)
        lineTo(122f * k, 52.5f * k)
    }
    drawPath(head, ink, style = Stroke(1.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A grid of six photos; a finger taps one; two chosen ones carry a check circle (shape, not colour alone). */
private fun DrawScope.choose(k: Float, ink: C, line: C, paper: C, review: C) {
    val tiles = listOf(26f to 14f, 62f to 14f, 98f to 14f, 26f to 50f, 62f to 50f, 98f to 50f)
    tiles.forEachIndexed { i, (x, y) ->
        rr(k, x, y, 32f, 32f, 4f, paper)
        rr(k, x, y, 32f, 32f, 4f, if (i == 1 || i == 3) review else line, stroke = if (i == 1 || i == 3) 2f else 1.2f)
        // A small hill and sun: a photo.
        val hill = Path().apply {
            moveTo((x + 4f) * k, (y + 26f) * k)
            lineTo((x + 13f) * k, (y + 16f) * k)
            lineTo((x + 20f) * k, (y + 22f) * k)
            lineTo((x + 24f) * k, (y + 18f) * k)
            lineTo((x + 28f) * k, (y + 26f) * k)
        }
        drawPath(hill, line, style = Stroke(1.2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(line, 2.5f * k, Offset((x + 23f) * k, (y + 9f) * k), style = Stroke(1.2f * k))
    }
    check(k, 90f, 18f, 5.5f, review, paper)
    check(k, 54f, 54f, 5.5f, review, paper)
    // A finger pointing at the middle photo of the lower row.
    val finger = Path().apply {
        moveTo(80f * k, 98f * k)
        lineTo(80f * k, 74f * k)
        quadraticTo(80f * k, 69f * k, 84f * k, 69f * k)
        quadraticTo(88f * k, 69f * k, 88f * k, 74f * k)
        lineTo(88f * k, 98f * k)
    }
    drawPath(finger, paper)
    drawPath(finger, ink, style = Stroke(1.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(ink, 2f * k, Offset(84f * k, 64f * k), style = Stroke(1.2f * k))
}

/** Three photos with an arrow into a USB stick, and the round check stamp on it. */
private fun DrawScope.backup(k: Float, ink: C, line: C, paper: C, move: C) {
    listOf(14f to 20f, 22f to 30f, 30f to 40f).forEach { (x, y) ->
        rr(k, x, y, 30f, 24f, 3f, paper)
        rr(k, x, y, 30f, 24f, 3f, line, stroke = 1.2f)
    }
    drawCircle(line, 2.5f * k, Offset(52f * k, 47f * k), style = Stroke(1.2f * k))
    // Arrow.
    drawLine(ink, Offset(70f * k, 52f * k), Offset(92f * k, 52f * k), 1.6f * k, cap = StrokeCap.Round)
    val head = Path().apply {
        moveTo(87f * k, 47f * k)
        lineTo(92f * k, 52f * k)
        lineTo(87f * k, 57f * k)
    }
    drawPath(head, ink, style = Stroke(1.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // The stick: a body and its connector.
    rr(k, 100f, 34f, 46f, 36f, 6f, paper)
    rr(k, 100f, 34f, 46f, 36f, 6f, ink, stroke = 1.6f)
    rr(k, 146f, 42f, 10f, 20f, 2f, line, stroke = 1.4f)
    // The stamp: a ring with a check, slightly turned, in the MOVE colour.
    drawCircle(move, 14f * k, Offset(123f * k, 74f * k), style = Stroke(2f * k))
    val p = Path().apply {
        moveTo(116f * k, 74f * k)
        lineTo(121f * k, 79f * k)
        lineTo(130f * k, 69f * k)
    }
    drawPath(p, move, style = Stroke(2.4f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
