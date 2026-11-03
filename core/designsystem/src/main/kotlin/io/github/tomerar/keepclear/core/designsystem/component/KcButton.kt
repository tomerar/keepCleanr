// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcShapes

/**
 * Button styles (design-system.md §7). [Destructive] and [OutlinedDestructive] are ONLY for permanent, unrecoverable
 * actions; going to the 30-day trash is [Primary]. [OutlinedDestructive] (red outline and label, no fill) is for a
 * permanent action that is not the screen's main action, e.g. "Empty the trash" at the bottom of the Trash (design QA
 * 2026-11-02); its confirmation is still red.
 */
enum class KcButtonStyle { Primary, Tonal, Text, Destructive, OutlinedDestructive }

object KcButtonDefaults {
    val MinHeight = 48.dp
    val ContentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)

    /** PR H2b: two buttons side by side at a large font size (swipe's Keep / Delete) keep whole words. */
    val CompactPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)

    /**
     * Design QA item 5: a confirm button that is getting ready shows a track of its own colour at this alpha, filling
     * with the full colour; its label stays readable on both (ContrastTest).
     */
    val ProgressTrackAlpha = 0.16f

    /** Colours of a filled pill: ink, or error red for [permanent] actions. Also used by the app's confirm button. */
    @Composable
    fun filledColors(permanent: Boolean): ButtonColors = if (permanent) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** KeepClear's only button: a pill with an optional leading icon (decorative; the text names the action). */
@Composable
fun KcButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KcButtonStyle = KcButtonStyle.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    /** Narrower side padding ([KcButtonDefaults.CompactPadding]) for filled and tonal styles. */
    compact: Boolean = false,
) {
    val content: @Composable RowScope.() -> Unit = { ButtonContent(text, icon) }
    val padding = if (compact) KcButtonDefaults.CompactPadding else KcButtonDefaults.ContentPadding
    val m = modifier.heightIn(min = KcButtonDefaults.MinHeight)
    when (style) {
        KcButtonStyle.Primary, KcButtonStyle.Destructive -> Button(
            onClick = onClick,
            modifier = m,
            enabled = enabled,
            shape = KcShapes.pill,
            colors = KcButtonDefaults.filledColors(permanent = style == KcButtonStyle.Destructive),
            contentPadding = padding,
            content = content,
        )

        KcButtonStyle.Tonal -> FilledTonalButton(
            onClick = onClick,
            modifier = m,
            enabled = enabled,
            shape = KcShapes.pill,
            contentPadding = padding,
            content = content,
        )

        KcButtonStyle.OutlinedDestructive -> OutlinedButton(
            onClick = onClick,
            modifier = m,
            enabled = enabled,
            shape = KcShapes.pill,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(
                1.dp,
                if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
            ),
            contentPadding = KcButtonDefaults.ContentPadding,
            content = content,
        )

        KcButtonStyle.Text -> TextButton(
            onClick = onClick,
            modifier = m,
            enabled = enabled,
            shape = KcShapes.pill,
            content = content,
        )
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
