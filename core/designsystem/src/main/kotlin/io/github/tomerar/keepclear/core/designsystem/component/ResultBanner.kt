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
    /** F2: a second, optional text action next to Undo (e.g. "Empty now", which opens the Trash). */
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    undoModifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
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
            val undo = if (onUndo != null && undoText != null) undoText to onUndo else null
            val action = if (onAction != null && actionText != null) actionText to onAction else null
            if (undo != null || action != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(KcSpacing.s)) {
                    undo?.let { (text, click) ->
                        KcButton(text, click, undoModifier, style = KcButtonStyle.Text, icon = KcIcons.Undo)
                    }
                    action?.let { (text, click) -> KcButton(text, click, actionModifier, style = KcButtonStyle.Text) }
                }
            }
        }
    }
}
