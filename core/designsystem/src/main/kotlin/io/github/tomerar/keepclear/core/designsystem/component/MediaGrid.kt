// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme

/** Test tag of a [PanelSheet]'s body. */
const val PANEL_SHEET_BODY_TAG = "kcPanelSheetBody"

/** The smallest tile of a [MediaTile] grid; the number of columns follows the width (more on tablets). */
val MEDIA_TILE_MIN = 104.dp

/**
 * A bottom sheet for a long list or a grid (H1c, design-system.md §7): like [InfoSheet], but the body doesn't scroll
 * by itself, so [content] can hold a lazy grid that fills the sheet (at most 90% of the window). The heading
 * [title] is also the pane title TalkBack announces. [primaryButton] and the [dismissText] button stay at the bottom.
 */
@Composable
fun PanelSheet(
    title: String,
    dismissText: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    primaryButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, modifier = modifier.semantics { paneTitle = title }) {
        Column(
            modifier = Modifier.weight(1f, fill = false).testTag(PANEL_SHEET_BODY_TAG),
            verticalArrangement = Arrangement.spacedBy(KcSpacing.s),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
        Spacer(Modifier.height(KcSpacing.m))
        if (primaryButton != null) Box(Modifier.fillMaxWidth()) { primaryButton() }
        KcButton(dismissText, onDismiss, Modifier.fillMaxWidth(), style = KcButtonStyle.Text)
    }
}

/**
 * A month's heading in a grid ("October 2026"). It wraps only between words: it always spans the whole row (never
 * squeezed into a FlowRow, the "אוקטוב/ר" bug at 200% font).
 */
@Composable
fun MonthHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().padding(top = KcSpacing.m, bottom = KcSpacing.xs).semantics { heading() },
    )
}

/**
 * One file in a grid (H1c): its picture, or [typeIcon] when there is none, then its [date] and [size] (tabular
 * figures). For TalkBack the tile is one node read as [description] (name, date, size) with [actions] as custom
 * actions (Restore, Preview, …). With [selected] non-null the tile is a checkbox: a tap toggles it and a check
 * shows; otherwise a tap is [onClick] (open its details). A long press is [onLongClick] when given.
 */
@Composable
fun MediaTile(
    thumbnail: Painter?,
    typeIcon: ImageVector,
    date: String,
    size: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    actions: List<CustomAccessibilityAction> = emptyList(),
    /** A short line under the size (e.g. "Changed"), or null. */
    note: String? = null,
) {
    val ring = if (selected == true) {
        BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Column(
        modifier = modifier
            .clip(KcShapes.thumbnail)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = if (selected != null) Role.Checkbox else Role.Button
                if (selected != null) this.selected = selected
                onClick {
                    onClick()
                    true
                }
                if (actions.isNotEmpty()) customActions = actions
            }
            .padding(KcSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(KcShapes.thumbnail)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(ring, KcShapes.thumbnail),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(typeIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
            }
            if (selected == true) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(KcSpacing.xs)
                        .background(MaterialTheme.colorScheme.surface, KcShapes.pill),
                ) {
                    Icon(
                        KcIcons.Selected,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        Text(
            date,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(size, style = KeepClearTheme.type.number, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (note != null) {
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A search field (H1c): one line, a search icon, a clear button once something is typed. */
@Composable
fun KcSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(KcIcons.Search, contentDescription = null) },
        trailingIcon = if (value.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onValueChange("") }) { Icon(KcIcons.Close, contentDescription = clearLabel) }
            }
        },
        singleLine = true,
        shape = KcShapes.pill,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}
