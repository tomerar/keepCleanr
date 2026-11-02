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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
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
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().testTag("swipe")) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.swipe_progress, minOf(decidedCount + 1, items.size), items.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).testTag("swipeProgress"),
                    )
                    TextButton(
                        onClick = { setDecisions(SwipeSession.undo(current)) },
                        enabled = current.isNotEmpty(),
                        modifier = Modifier.testTag("swipeUndo"),
                    ) { Text(stringResource(R.string.swipe_undo)) }
                    TextButton(onClick = onClose, modifier = Modifier.testTag("swipeClose")) {
                        Text(stringResource(R.string.swipe_close))
                    }
                }
                if (shown != null) {
                    val item = shown
                    var drag by remember(item.item.path) { mutableFloatStateOf(0f) }
                    Text(stringResource(R.string.swipe_hint), style = MaterialTheme.typography.bodySmall)
                    // The picture takes the space left over, so the buttons always stay on screen.
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(item.item.path) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        // Toward the end edge (where Delete sits) deletes; toward the start keeps.
                                        val towardEnd = if (rtl) drag < 0 else drag > 0
                                        if (kotlin.math.abs(drag) > size.width / 4f) {
                                            decide(if (towardEnd) SwipeChoice.DELETE else SwipeChoice.KEEP)
                                        }
                                        drag = 0f
                                    },
                                    onHorizontalDrag = { _, amount -> drag += amount },
                                )
                            }
                            .testTag("swipeCard"),
                    ) { Thumb(item, 360) }
                    Text(
                        Bidi.isolateLtr(item.item.path.substringAfterLast('/')),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(ByteSize.format(item.item.size, locale), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { decide(SwipeChoice.KEEP) },
                            modifier = Modifier.weight(1f).testTag("swipeKeep"),
                        ) { Text(stringResource(R.string.swipe_keep)) }
                        TextButton(onClick = { decide(SwipeChoice.SKIP) }, modifier = Modifier.testTag("swipeSkip")) {
                            Text(stringResource(R.string.swipe_skip))
                        }
                        Button(
                            onClick = { decide(SwipeChoice.DELETE) },
                            modifier = Modifier.weight(1f).testTag("swipeDelete"),
                        ) { Text(stringResource(R.string.swipe_delete)) }
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
                    Button(
                        onClick = { onDone(toDelete) },
                        enabled = toDelete.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("swipeReview"),
                    ) { Text(stringResource(R.string.swipe_review)) }
                }
            }
        }
    }
}
