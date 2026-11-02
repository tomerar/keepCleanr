// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme

/** One line of a confirmation: what, how many, how much, where it goes. */
data class ConfirmFact(val label: String, val value: String)

/** Test tag of the sheet's scrolling part. */
const val CONFIRM_SHEET_BODY_TAG = "kcConfirmSheetBody"

/**
 * The confirmation of every destructive action (design-system.md §7): a sheet anchored to the bottom with a heading
 * [title], the [facts] (numbers in tabular figures), an optional [note], the editable list in [content], then the
 * [confirmButton] and a [dismissText] button. Everything above the buttons scrolls, so it works at 200% font.
 *
 * Recoverable actions (to the 30-day trash) use ink; only [permanent] ones use error red for the [note]. The app
 * passes its tapjacking-safe confirm button (SEC-7) in [confirmButton], coloured with
 * `KcButtonDefaults.filledColors(permanent)`. Material's ModalBottomSheet is still experimental in material3 1.4,
 * so this is a dialog whose window sits at the bottom (ADR 0013).
 */
@Composable
fun ConfirmSheet(
    title: String,
    dismissText: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    facts: List<ConfirmFact> = emptyList(),
    note: String? = null,
    permanent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val window = LocalWindowInfo.current.containerSize
    val maxHeight = with(LocalDensity.current) { (window.height * 0.9f).toDp() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect {
            val provider = view as? DialogWindowProvider ?: view.parent as? DialogWindowProvider
            provider?.window?.setGravity(Gravity.BOTTOM)
        }
        Surface(
            modifier = modifier.fillMaxWidth().heightIn(max = if (maxHeight > 0.dp) maxHeight else 640.dp),
            shape = KcShapes.sheet,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = KcSpacing.xl).padding(bottom = KcSpacing.m)) {
                Box(Modifier.fillMaxWidth().padding(vertical = KcSpacing.m), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(width = 32.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant, KcShapes.pill),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .testTag(CONFIRM_SHEET_BODY_TAG)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(KcSpacing.m),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    facts.forEach { fact ->
                        Column(Modifier.semantics(mergeDescendants = true) { }) {
                            Text(
                                fact.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                fact.value,
                                style = KeepClearTheme.type.number,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (note != null) {
                        Text(
                            note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (permanent) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    content()
                }
                Spacer(Modifier.height(KcSpacing.l))
                Box(Modifier.fillMaxWidth()) { confirmButton() }
                KcButton(dismissText, onDismiss, Modifier.fillMaxWidth(), style = KcButtonStyle.Text)
            }
        }
    }
}
