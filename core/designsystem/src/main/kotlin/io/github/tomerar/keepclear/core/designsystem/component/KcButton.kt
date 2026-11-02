// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcShapes

/**
 * Button styles (design-system.md §7). [Destructive] is ONLY for permanent, unrecoverable actions (empty the trash,
 * release now); going to the 30-day trash is [Primary].
 */
enum class KcButtonStyle { Primary, Tonal, Text, Destructive }

object KcButtonDefaults {
    val MinHeight = 48.dp
    val ContentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)

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
) {
    val content: @Composable RowScope.() -> Unit = { ButtonContent(text, icon) }
    val m = modifier.heightIn(min = KcButtonDefaults.MinHeight)
    when (style) {
        KcButtonStyle.Primary, KcButtonStyle.Destructive -> Button(
            onClick = onClick,
            modifier = m,
            enabled = enabled,
            shape = KcShapes.pill,
            colors = KcButtonDefaults.filledColors(permanent = style == KcButtonStyle.Destructive),
            contentPadding = KcButtonDefaults.ContentPadding,
            content = content,
        )

        KcButtonStyle.Tonal -> FilledTonalButton(
            onClick = onClick,
            modifier = m,
            enabled = enabled,
            shape = KcShapes.pill,
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
