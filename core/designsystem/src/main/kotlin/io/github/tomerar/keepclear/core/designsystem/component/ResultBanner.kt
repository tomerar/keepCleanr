// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme

/**
 * The measured result after an action (design-system.md §7), e.g. "Freed 7.9GB · now 24.7GB free (was 16.8GB)", with
 * Undo when the action can be undone. Announced politely by TalkBack. [onDismiss] adds a close button labelled
 * [dismissLabel].
 */
@Composable
fun ResultBanner(
    message: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    undoText: String? = null,
    onUndo: (() -> Unit)? = null,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val safe = KeepClearTheme.colors.safe
    Surface(modifier = modifier.fillMaxWidth(), shape = KcShapes.card, color = safe.container) {
        Column(Modifier.padding(start = KcSpacing.l, end = KcSpacing.s, top = KcSpacing.m, bottom = KcSpacing.s)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(KcSpacing.m)) {
                Icon(
                    KcIcons.Verified,
                    contentDescription = null,
                    tint = safe.accent,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Column(
                    Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(message, style = MaterialTheme.typography.titleSmall, color = safe.onContainer)
                    if (detail != null) {
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = safe.onContainer)
                    }
                }
                if (onDismiss != null && dismissLabel != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(KcIcons.Close, contentDescription = dismissLabel, tint = safe.onContainer)
                    }
                }
            }
            if (onUndo != null && undoText != null) {
                KcButton(undoText, onUndo, style = KcButtonStyle.Text, icon = KcIcons.Undo)
            }
        }
    }
}
