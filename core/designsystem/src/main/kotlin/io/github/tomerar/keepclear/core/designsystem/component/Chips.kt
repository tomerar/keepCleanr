// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme

/** From this many days left, a trash item's chip asks for attention (amber, never red). */
const val DAYS_LEFT_ATTENTION = 3

/**
 * Days until a trashed item is deleted for good (design-system.md §8), e.g. "23 days left". [text] comes from a
 * plurals resource; [daysLeft] only picks the colour: amber at [DAYS_LEFT_ATTENTION] days or fewer.
 */
@Composable
fun DaysLeftChip(text: String, daysLeft: Int, modifier: Modifier = Modifier) {
    val soon = daysLeft <= DAYS_LEFT_ATTENTION
    val move = KeepClearTheme.colors.move
    val container = if (soon) move.container else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (soon) move.onContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(modifier = modifier.semantics(mergeDescendants = true) { }, shape = KcShapes.chip, color = container) {
        Row(
            modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = KcSpacing.m - 4.dp, vertical = KcSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(KcIcons.Schedule, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

/**
 * "Why is this here?" (design-system.md §7): opens an explainer sheet next to what was tapped. One target, role
 * Button.
 */
@Composable
fun InfoChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { role = Role.Button },
        shape = KcShapes.chip,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = KcSpacing.m - 4.dp, vertical = KcSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                KcIcons.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
