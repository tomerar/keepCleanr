// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcLane
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme

/** The lane's icon on its container colour, 40dp (decorative: the lane's label is always next to it). */
@Composable
fun LaneBadge(lane: KcLane, modifier: Modifier = Modifier) {
    val colors = KeepClearTheme.colors.lane(lane)
    Box(
        modifier = modifier.size(40.dp).clip(CircleShape).background(colors.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(KcIcons.lane(lane), contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
    }
}

/**
 * One lane (design-system.md §7): icon + [title] (the lane's name) + [count] and [size] in Rubik tabular figures,
 * an optional [supporting] line and an optional [ThumbStrip] ([thumbnailTotal] = items in the group). Never colour alone. With [onClick] the card is one
 * target; TalkBack reads one merged label ("title, count · size, supporting").
 */
@Composable
fun LaneCard(
    lane: KcLane,
    title: String,
    modifier: Modifier = Modifier,
    count: String? = null,
    size: String? = null,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    thumbnails: List<Painter> = emptyList(),
    thumbnailTotal: Int = thumbnails.size,
) {
    KcCard(modifier = modifier.semantics(mergeDescendants = true) { }, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KcSpacing.m)) {
            LaneBadge(lane)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                val numbers = listOfNotNull(count, size).joinToString(" · ")
                if (numbers.isNotEmpty()) {
                    Text(numbers, style = KeepClearTheme.type.number, color = KeepClearTheme.colors.lane(lane).accent)
                }
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onClick != null) {
                Icon(KcIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (thumbnails.isNotEmpty()) {
            Spacer(Modifier.height(KcSpacing.m))
            ThumbStrip(thumbnails, total = thumbnailTotal)
        }
    }
}
