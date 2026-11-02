// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Shapes (design-system.md §4): cards 28dp, hero 32dp, pill buttons, chips 12dp. */
object KcShapes {
    val card = RoundedCornerShape(28.dp)
    val hero = RoundedCornerShape(32.dp)
    val pill = CircleShape
    val chip = RoundedCornerShape(12.dp)
    val thumbnail = RoundedCornerShape(12.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

/** The 8dp grid (design-system.md §4). */
object KcSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 20.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Inside a card. */
    val card = 20.dp

    /** Inside the hero container. */
    val hero = 24.dp
}

internal val KeepClearShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = KcShapes.chip,
    medium = RoundedCornerShape(16.dp),
    large = KcShapes.card,
    extraLarge = KcShapes.card,
)
