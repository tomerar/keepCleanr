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
import androidx.compose.ui.semantics.paneTitle
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

/** Test tag of a sheet's [risk] line. */
const val CONFIRM_SHEET_RISK_TAG = "kcConfirmSheetRisk"

/** Test tag of the fade drawn above the buttons while more of the sheet is below (owner brief H2 item 5). */
const val CONFIRM_SHEET_FADE_TAG = "kcConfirmSheetFade"

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
    /** Modifier of the dismiss button (e.g. a test tag). */
    dismissModifier: Modifier = Modifier,
    /**
     * What the text button under [confirmButton] does; [onDismiss] when null. Back and a tap outside always call
     * [onDismiss] (PR #48 review: a sheet whose main button is "Keep going" puts its real action here).
     */
    dismissAction: (() -> Unit)? = null,
    /**
     * Owner brief H2 item 5: the one line about what can't be undone, in error red, right under the title (before
     * the rows), so it is seen first also at 200% font. Only for a permanent loss (e.g. other apps' bins).
     */
    risk: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    SheetFrame(onDismiss = onDismiss, modifier = modifier) {
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f, fill = false)) {
            Column(
                modifier = Modifier
                    .testTag(CONFIRM_SHEET_BODY_TAG)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(KcSpacing.m),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                if (risk != null) {
                    Text(
                        risk,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(CONFIRM_SHEET_RISK_TAG),
                    )
                }
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
            ScrollFade(scroll, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(KcSpacing.l))
        // A column, so a slot with more than one button never stacks them on top of each other (PR #48 review).
        Column(Modifier.fillMaxWidth()) { confirmButton() }
        KcButton(dismissText, dismissAction ?: onDismiss, dismissModifier.fillMaxWidth(), style = KcButtonStyle.Text)
    }
}

/**
 * Owner brief H2 item 5: while more of a sheet's body is below, a fade in the sheet's own colour above the sticky
 * buttons says "there's more" (decorative: TalkBack scrolls the body itself).
 */
@Composable
private fun ScrollFade(scroll: androidx.compose.foundation.ScrollState, modifier: Modifier) {
    if (!scroll.canScrollForward) return
    val paper = MaterialTheme.colorScheme.surfaceContainerLow
    Box(
        modifier
            .fillMaxWidth()
            .height(KcSpacing.xl * 2)
            .testTag(CONFIRM_SHEET_FADE_TAG)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(paper.copy(alpha = 0f), paper),
                ),
            ),
    )
}

/**
 * A sheet anchored to the bottom (design-system.md §7): Material's ModalBottomSheet is still experimental in
 * material3 1.4 (C70), so this is a dialog whose window sits at the bottom, with a handle, at most 90% of the
 * window high. [content] lays out the sheet's body and buttons.
 */
@Composable
internal fun SheetFrame(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
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
                content()
            }
        }
    }
}

/** Test tag of an [InfoSheet]'s scrolling part. */
const val INFO_SHEET_BODY_TAG = "kcInfoSheetBody"

/**
 * An explainer (design-system.md §7, F1b): opens as a bottom sheet from what was tapped (an InfoChip, a card's
 * button), never at the top of a screen. A heading [title], the scrolling [content], then an optional
 * [primaryButton] and the [dismissText] button.
 */
@Composable
fun InfoSheet(
    title: String,
    dismissText: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    primaryButton: (@Composable () -> Unit)? = null,
    /** Modifier of the dismiss button (e.g. a test tag). */
    dismissModifier: Modifier = Modifier,
    /** What the text button does; [onDismiss] when null (e.g. "Stop" while Back does nothing). */
    dismissAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, modifier = modifier.semantics { paneTitle = title }) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag(INFO_SHEET_BODY_TAG)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(KcSpacing.m),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
        Spacer(Modifier.height(KcSpacing.l))
        if (primaryButton != null) Box(Modifier.fillMaxWidth()) { primaryButton() }
        KcButton(dismissText, dismissAction ?: onDismiss, dismissModifier.fillMaxWidth(), style = KcButtonStyle.Text)
    }
}
