// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Total

/** REV-5: one decision per item, in order. Nothing happens until the summary's "Review and delete". */
enum class SwipeChoice { KEEP, DELETE, SKIP }

/** E11: one decision, for the file at [path] (never for a position in the list, which can change). */
data class SwipeDecision(val path: String, val choice: SwipeChoice)

/**
 * Pure swipe-session logic, unit-testable: decisions in order, undo pops the last one. E11 (also a safety rule):
 * every decision is tied to the file's path. If the list changes while the user decides (a scan finished, a file
 * went away), a decision still belongs to the file it was made for, and "Delete" can never move to another file.
 */
object SwipeSession {
    /** The first item without a decision, or null when every item has one. */
    fun next(items: List<ClassifiedItem>, decisions: List<SwipeDecision>): ClassifiedItem? {
        val decided = decisions.mapTo(HashSet()) { it.path }
        return items.firstOrNull { it.item.path !in decided }
    }

    /** Decides [choice] for [shown], the item on screen; nothing if it already has a decision or isn't listed. */
    fun decide(
        decisions: List<SwipeDecision>,
        shown: ClassifiedItem?,
        choice: SwipeChoice,
    ): List<SwipeDecision> {
        if (shown == null || decisions.any { it.path == shown.item.path }) return decisions
        return decisions + SwipeDecision(shown.item.path, choice)
    }

    fun undo(decisions: List<SwipeDecision>) = decisions.dropLast(1)

    /** How many of [items] have a decision. */
    fun decided(items: List<ClassifiedItem>, decisions: List<SwipeDecision>): Int {
        val decided = decisions.mapTo(HashSet()) { it.path }
        return items.count { it.item.path in decided }
    }

    /**
     * F2: what a horizontal drag of [dragX] pixels means, or null below [threshold]. Fixed by meaning, not by the
     * layout direction: Delete is toward the Delete button, which sits at the row's end (right in English, left in
     * Hebrew); Keep is toward the Keep button at the start. So in Hebrew a swipe to the left deletes.
     */
    fun choiceFor(dragX: Float, threshold: Float, rtl: Boolean): SwipeChoice? {
        if (kotlin.math.abs(dragX) < threshold) return null
        val towardEnd = if (rtl) dragX < 0 else dragX > 0
        return if (towardEnd) SwipeChoice.DELETE else SwipeChoice.KEEP
    }

    /** The items the user swiped toward Delete; never anything else (SAFETY-10: no pre-selection). */
    fun toDelete(items: List<ClassifiedItem>, decisions: List<SwipeDecision>): List<ClassifiedItem> {
        val delete = decisions.filter { it.choice == SwipeChoice.DELETE }.mapTo(HashSet()) { it.path }
        return items.filter { it.item.path in delete }
    }
}

/**
 * REV-5 swipe mode: one item at a time, swipe toward Delete or Keep (or use the buttons), Skip, Undo. Choices are
 * deferred; at the end a summary hands the items to delete to the usual editable confirmation (REV-3).
 */
@Composable
fun SwipeReview(
    items: List<ClassifiedItem>,
    onDone: (List<ClassifiedItem>) -> Unit,
    onClose: () -> Unit,
    /** E4: the decisions live in the ViewModel when given; otherwise here (previews and tests). */
    decisions: List<SwipeDecision>? = null,
    onDecisions: (List<SwipeDecision>) -> Unit = {},
) {
    var local by remember { mutableStateOf(listOf<SwipeDecision>()) }
    val current = decisions ?: local
    val setDecisions: (List<SwipeDecision>) -> Unit = if (decisions != null) onDecisions else { d -> local = d }
    val locale = LocalConfiguration.current.locales[0]
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shown = SwipeSession.next(items, current)
    val decidedCount = SwipeSession.decided(items, current)

    fun decide(choice: SwipeChoice) {
        setDecisions(SwipeSession.decide(current, shown, choice))
    }
    // F2: leaving with decisions made asks first ("Leave? 14 decisions will be lost"); none made: just close.
    var askExit by remember { mutableStateOf(false) }
    val close = { if (decidedCount > 0) askExit = true else onClose() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().testTag("swipe")) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.swipe_progress,
                            countText(minOf(decidedCount + 1, items.size)),
                            countText(items.size),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).testTag("swipeProgress"),
                    )
                    TextButton(
                        onClick = { setDecisions(SwipeSession.undo(current)) },
                        enabled = current.isNotEmpty(),
                        modifier = Modifier.testTag("swipeUndo"),
                    ) { Text(stringResource(R.string.swipe_undo)) }
                    TextButton(onClick = close, modifier = Modifier.testTag("swipeClose")) {
                        Text(stringResource(R.string.swipe_close))
                    }
                }
                if (shown != null) {
                    val item = shown
                    var drag by remember(item.item.path) { mutableFloatStateOf(0f) }
                    var width by remember { mutableFloatStateOf(1f) }
                    val haptics = LocalHapticFeedback.current
                    val reduced = KeepClearTheme.reducedMotion
                    val threshold = width / 4f
                    val pending = SwipeSession.choiceFor(drag, threshold, rtl)
                    Text(stringResource(R.string.swipe_hint), style = MaterialTheme.typography.bodySmall)
                    // The picture takes the space left over, so the buttons always stay on screen. F2: it follows the
                    // finger and tilts a little; past the threshold it says what letting go will do, with a tick.
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                            .graphicsLayer {
                                translationX = drag
                                rotationZ = if (reduced) 0f else (drag / width) * MAX_TILT_DEGREES
                            }
                            .clip(KcShapes.card)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(item.item.path) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        SwipeSession.choiceFor(drag, size.width / 4f, rtl)?.let { decide(it) }
                                        drag = 0f
                                    },
                                    onDragCancel = { drag = 0f },
                                    onHorizontalDrag = { _, amount ->
                                        val before = SwipeSession.choiceFor(drag, size.width / 4f, rtl)
                                        drag += amount
                                        val after = SwipeSession.choiceFor(drag, size.width / 4f, rtl)
                                        if (after != null && after != before) {
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        }
                                    },
                                )
                            }
                            .testTag("swipeCard"),
                    ) {
                        Thumb(item, 360)
                        pending?.let { choice ->
                            Text(
                                stringResource(
                                    if (choice == SwipeChoice.DELETE) R.string.swipe_delete else R.string.swipe_keep,
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                                    .background(MaterialTheme.colorScheme.primary, KcShapes.chip)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .testTag("swipeLabel"),
                            )
                        }
                    }
                    Text(
                        Bidi.isolateLtr(item.item.path.substringAfterLast('/')),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(ByteSize.format(item.item.size, locale), style = MaterialTheme.typography.bodySmall)
                    // Keep at the start, Delete at the end: the swipe directions follow these buttons (choiceFor).
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        KcButton(
                            stringResource(R.string.swipe_keep),
                            onClick = { decide(SwipeChoice.KEEP) },
                            style = KcButtonStyle.Tonal,
                            modifier = Modifier.weight(1f).testTag("swipeKeep"),
                        )
                        KcButton(
                            stringResource(R.string.swipe_skip),
                            onClick = { decide(SwipeChoice.SKIP) },
                            style = KcButtonStyle.Text,
                            modifier = Modifier.testTag("swipeSkip"),
                        )
                        KcButton(
                            stringResource(R.string.swipe_delete),
                            onClick = { decide(SwipeChoice.DELETE) },
                            modifier = Modifier.weight(1f).testTag("swipeDelete"),
                        )
                    }
                } else {
                    val toDelete = SwipeSession.toDelete(items, current)
                    val listed = items.mapTo(HashSet()) { it.item.path }
                    val kept = current.count { it.choice == SwipeChoice.KEEP && it.path in listed }
                    val skipped = current.count { it.choice == SwipeChoice.SKIP && it.path in listed }
                    Column(Modifier.testTag("swipeSummary"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(
                                R.string.swipe_summary_delete,
                                totalText(
                                    toDelete.fold(Total()) { t, i ->
                                        t +
                                            i
                                    },
                                ),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(countSentence(R.plurals.swipe_kept, kept))
                        if (skipped > 0) Text(countSentence(R.plurals.swipe_skipped, skipped))
                        Text(stringResource(R.string.swipe_summary_note), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                    KcButton(
                        stringResource(R.string.swipe_review),
                        onClick = { onDone(toDelete) },
                        enabled = toDelete.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("swipeReview"),
                    )
                }
            }
        }
    }
    if (askExit) {
        val n = java.text.NumberFormat.getIntegerInstance(locale).format(decidedCount)
        ConfirmSheet(
            title = pluralStringResource(R.plurals.swipe_exit_title, decidedCount, Bidi.isolate(n)),
            dismissText = stringResource(R.string.swipe_exit_no),
            onDismiss = { askExit = false },
            modifier = Modifier.testTag("swipeExit"),
            note = stringResource(R.string.swipe_exit_body),
            confirmButton = {
                KcButton(
                    stringResource(R.string.swipe_exit_yes),
                    onClick = {
                        askExit = false
                        onClose()
                    },
                    style = KcButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth().testTag("swipeExitYes"),
                )
            },
        )
    }
}

/** F2: how far the card tilts at the edge of the screen (subtle; none under reduced motion). */
private const val MAX_TILT_DEGREES = 8f
