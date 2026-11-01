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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Pure swipe-session logic, unit-testable: decisions in order, undo pops the last one. */
object SwipeSession {
    fun decide(decisions: List<SwipeChoice>, choice: SwipeChoice, total: Int) =
        if (decisions.size >= total) decisions else decisions + choice

    fun undo(decisions: List<SwipeChoice>) = decisions.dropLast(1)

    /** The items the user swiped toward Delete; never anything else (SAFETY-10: no pre-selection). */
    fun toDelete(items: List<ClassifiedItem>, decisions: List<SwipeChoice>) =
        items.zip(decisions).filter { (_, c) -> c == SwipeChoice.DELETE }.map { it.first }
}

/**
 * REV-5 swipe mode: one item at a time, swipe toward Delete or Keep (or use the buttons), Skip, Undo. Choices are
 * deferred; at the end a summary hands the items to delete to the usual editable confirmation (REV-3).
 */
@Composable
fun SwipeReview(items: List<ClassifiedItem>, onDone: (List<ClassifiedItem>) -> Unit, onClose: () -> Unit) {
    var decisions by rememberSaveable { mutableStateOf(listOf<SwipeChoice>()) }
    val locale = LocalConfiguration.current.locales[0]
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    fun decide(choice: SwipeChoice) {
        decisions = SwipeSession.decide(decisions, choice, items.size)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().testTag("swipe")) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.swipe_progress, minOf(decisions.size + 1, items.size), items.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).testTag("swipeProgress"),
                    )
                    TextButton(
                        onClick = { decisions = SwipeSession.undo(decisions) },
                        enabled = decisions.isNotEmpty(),
                        modifier = Modifier.testTag("swipeUndo"),
                    ) { Text(stringResource(R.string.swipe_undo)) }
                    TextButton(onClick = onClose, modifier = Modifier.testTag("swipeClose")) {
                        Text(stringResource(R.string.swipe_close))
                    }
                }
                if (decisions.size < items.size) {
                    val item = items[decisions.size]
                    var drag by remember(decisions.size) { mutableFloatStateOf(0f) }
                    Text(stringResource(R.string.swipe_hint), style = MaterialTheme.typography.bodySmall)
                    // The picture takes the space left over, so the buttons always stay on screen.
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(decisions.size) {
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
                    val toDelete = SwipeSession.toDelete(items, decisions)
                    val kept = decisions.count { it == SwipeChoice.KEEP }
                    val skipped = decisions.count { it == SwipeChoice.SKIP }
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
