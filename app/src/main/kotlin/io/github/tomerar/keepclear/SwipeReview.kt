// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Total
import java.time.ZoneId

/** REV-5: one decision per item, in order. Nothing happens until the usual confirmation at the end. */
enum class SwipeChoice { KEEP, DELETE, SKIP }

/**
 * E11: one decision, for the file at [path] (never for a position in the list, which can change). PR H2b: it also
 * remembers the file's [size] and [mtimeMillis] when it was decided, so a file that changed since (or a different
 * file now at the same path) is never acted on by an old decision ([SwipeSession.resume]).
 */
data class SwipeDecision(val path: String, val choice: SwipeChoice, val size: Long, val mtimeMillis: Long) {
    /** True when this decision was made for [item] as it is now. */
    fun isFor(item: ClassifiedItem): Boolean =
        item.item.path == path && item.item.size == size && item.item.mtimeMillis == mtimeMillis

    companion object {
        fun of(item: ClassifiedItem, choice: SwipeChoice) =
            SwipeDecision(item.item.path, choice, item.item.size, item.item.mtimeMillis)
    }
}

/**
 * Pure swipe-session logic, unit-testable: decisions in order, undo pops the last one. E11 (also a safety rule):
 * every decision is tied to the file (path, size and date). If the list changes while the user decides (a scan
 * finished, a file went away or changed), a decision still belongs to the file it was made for, and "Delete" can
 * never move to another file, nor to a newer version of the same one.
 */
object SwipeSession {
    private fun valid(items: List<ClassifiedItem>, decisions: List<SwipeDecision>): Map<String, SwipeDecision> {
        val byPath = decisions.associateBy { it.path }
        return items.mapNotNull { i -> byPath[i.item.path]?.takeIf { it.isFor(i) } }.associateBy { it.path }
    }

    /** The first item without a decision, or null when every item has one. This is the session's position. */
    fun next(items: List<ClassifiedItem>, decisions: List<SwipeDecision>): ClassifiedItem? {
        val decided = valid(items, decisions)
        return items.firstOrNull { it.item.path !in decided }
    }

    /**
     * Decides [choice] for [shown], the item on screen; nothing if it already has a decision or is null. A stale
     * decision for the same path (the file changed since) is replaced.
     */
    fun decide(
        decisions: List<SwipeDecision>,
        shown: ClassifiedItem?,
        choice: SwipeChoice,
    ): List<SwipeDecision> {
        if (shown == null || decisions.any { it.isFor(shown) }) return decisions
        return decisions.filter { it.path != shown.item.path } + SwipeDecision.of(shown, choice)
    }

    fun undo(decisions: List<SwipeDecision>) = decisions.dropLast(1)

    /** How many of [items] have a decision. */
    fun decided(items: List<ClassifiedItem>, decisions: List<SwipeDecision>): Int = valid(items, decisions).size

    /**
     * PR H2b (C148): which physical side deletes. A swipe to the **left** deletes in English and in Hebrew (the
     * common "swipe left to discard" gesture; in Hebrew it is also the row's end, as before), and the Delete button
     * sits on the left; "להחליף את כיווני ההחלקה" ([swapped]) turns both around.
     */
    fun deleteOnLeft(swapped: Boolean): Boolean = !swapped

    /**
     * What a horizontal drag of [dragX] pixels means (physical: negative is to the left, whatever the layout
     * direction), or null below [threshold].
     */
    fun choiceFor(dragX: Float, threshold: Float, deleteOnLeft: Boolean): SwipeChoice? {
        if (dragX == 0f || kotlin.math.abs(dragX) < threshold) return null
        val left = dragX < 0
        return if (left == deleteOnLeft) SwipeChoice.DELETE else SwipeChoice.KEEP
    }

    /** The items the user swiped toward Delete; never anything else (SAFETY-10: no pre-selection). */
    fun toDelete(items: List<ClassifiedItem>, decisions: List<SwipeDecision>): List<ClassifiedItem> {
        val valid = valid(items, decisions)
        return items.filter { valid[it.item.path]?.choice == SwipeChoice.DELETE }
    }

    /**
     * PR H2b: a saved session's decisions that still hold for [items]: a file that is gone, or whose size or date
     * changed since, loses its decision (it comes up again). Order is kept, so "חזרה" still undoes the last one.
     */
    fun resume(items: List<ClassifiedItem>, saved: List<SwipeDecision>): List<SwipeDecision> {
        val valid = valid(items, saved)
        return saved.filter { valid[it.path] === it }
    }
}

/**
 * REV-5 swipe mode: one item at a time, swipe toward Delete or Keep (or use the buttons), Skip, „חזרה” (undo).
 * Choices are deferred: „לסיים ולבדוק” (always on screen) hands only the items marked Delete so far to the usual
 * editable confirmation (REV-3), and only that confirmation moves anything to the trash.
 */
@Composable
fun SwipeReview(
    items: List<ClassifiedItem>,
    onDone: (List<ClassifiedItem>) -> Unit,
    onClose: () -> Unit,
    /** E4: the decisions live in the ViewModel when given; otherwise here (previews and tests). */
    decisions: List<SwipeDecision>? = null,
    onDecisions: (List<SwipeDecision>) -> Unit = {},
    /** Screenshots only: a drag under way, as a fraction of the card's width (negative: to the left). */
    previewDrag: Float = 0f,
) {
    var local by remember { mutableStateOf(listOf<SwipeDecision>()) }
    val current = decisions ?: local
    val setDecisions: (List<SwipeDecision>) -> Unit = if (decisions != null) onDecisions else { d -> local = d }
    val locale = LocalConfiguration.current.locales[0]
    val prefs = LocalSwipePrefs.current
    val haptics = rememberSwipeHaptics()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val deleteOnLeft = SwipeSession.deleteOnLeft(prefs.swapped)
    val shown = SwipeSession.next(items, current)
    val decidedCount = SwipeSession.decided(items, current)
    val toDelete = SwipeSession.toDelete(items, current)
    val showHint = !prefs.hintSeen && current.isEmpty() && shown != null

    fun decide(choice: SwipeChoice) {
        val next = SwipeSession.decide(current, shown, choice)
        if (next === current) return
        when (choice) {
            SwipeChoice.KEEP -> haptics.perform(SwipeHaptic.KEEP)
            SwipeChoice.DELETE -> haptics.perform(SwipeHaptic.DELETE)
            SwipeChoice.SKIP -> Unit
        }
        if (!prefs.hintSeen) prefs.onHintSeen()
        setDecisions(next)
    }
    val undo = { setDecisions(SwipeSession.undo(current)) }
    val keepLabel = stringResource(R.string.swipe_keep)
    val deleteLabel = stringResource(R.string.swipe_delete)
    val undoLabel = stringResource(R.string.swipe_undo)
    // PR H2b: the session is kept (ViewModel and disk), so closing just closes; „להמשיך מאיפה שעצרתם” brings it back.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().testTag("swipe")) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(KcSpacing.m),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KcButton(
                        stringResource(R.string.swipe_close),
                        onClick = onClose,
                        style = KcButtonStyle.Text,
                        icon = KcIcons.Close,
                        modifier = Modifier.testTag("swipeClose"),
                    )
                    Text(
                        stringResource(
                            R.string.swipe_progress,
                            countText(minOf(decidedCount + 1, items.size)),
                            countText(items.size),
                        ),
                        style = KeepClearTheme.type.number,
                        textAlign = TextAlign.Center,
                        // TalkBack says the new position after each decision, without interrupting.
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("swipeProgress"),
                    )
                    // Owner brief H2 item 8: undo is „חזרה” with ↶ (mirrored in Hebrew), apart from „לסגור”.
                    KcButton(
                        undoLabel,
                        onClick = undo,
                        enabled = current.isNotEmpty(),
                        style = KcButtonStyle.Text,
                        icon = KcIcons.Undo,
                        modifier = Modifier.testTag("swipeUndo"),
                    )
                }
                if (shown != null) {
                    val item = shown
                    if (showHint) {
                        DirectionHint(deleteOnLeft)
                    } else {
                        Text(
                            stringResource(R.string.swipe_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    var drag by remember(item.item.path) { mutableFloatStateOf(0f) }
                    var width by remember { mutableFloatStateOf(1f) }
                    val reduced = KeepClearTheme.reducedMotion
                    val decideNow by rememberUpdatedState<(SwipeChoice) -> Unit> { decide(it) }
                    val leftDeletes by rememberUpdatedState(deleteOnLeft)
                    val shownDrag = if (drag == 0f && previewDrag != 0f) previewDrag * width else drag
                    val day = MediaGrid.dayText(item.item.mtimeMillis, ZoneId.systemDefault(), locale)
                    val detail = stringResource(
                        R.string.grid_details_line,
                        Bidi.isolate(day),
                        Bidi.isolate(ByteSize.format(item.item.size, locale)),
                    )
                    // The picture, name and date read as one item; TalkBack's actions menu decides without a swipe.
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                val actions = mutableListOf(
                                    CustomAccessibilityAction(keepLabel) {
                                        decide(SwipeChoice.KEEP)
                                        true
                                    },
                                    CustomAccessibilityAction(deleteLabel) {
                                        decide(SwipeChoice.DELETE)
                                        true
                                    },
                                )
                                if (current.isNotEmpty()) {
                                    actions += CustomAccessibilityAction(undoLabel) {
                                        undo()
                                        true
                                    }
                                }
                                customActions = actions
                            }
                            .testTag("swipeItem"),
                        verticalArrangement = Arrangement.spacedBy(KcSpacing.s),
                    ) {
                        // The picture takes the space left over, so the buttons always stay on screen. F2: it follows
                        // the finger and tilts a little; its edge says what letting go will do, with a tick.
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f)
                                .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                                .graphicsLayer {
                                    translationX = shownDrag
                                    rotationZ = if (reduced) 0f else (shownDrag / width) * MAX_TILT_DEGREES
                                }
                                .clip(KcShapes.card)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .pointerInput(item.item.path) {
                                    detectHorizontalDragGestures(
                                        onDragEnd = {
                                            SwipeSession.choiceFor(drag, size.width / 4f, leftDeletes)
                                                ?.let { decideNow(it) }
                                            drag = 0f
                                        },
                                        onDragCancel = { drag = 0f },
                                        onHorizontalDrag = { _, amount ->
                                            val t = size.width / 4f
                                            val before = SwipeSession.choiceFor(drag, t, leftDeletes)
                                            drag += amount
                                            val after = SwipeSession.choiceFor(drag, t, leftDeletes)
                                            if (after != null && after != before) haptics.perform(SwipeHaptic.THRESHOLD)
                                        },
                                    )
                                }
                                .testTag("swipeCard"),
                        ) {
                            // The name is under the picture, so none inside it (at 200 % the picture can get small).
                            Thumb(item, 360, showName = false)
                            EdgeLabel(shownDrag, width / 4f, deleteOnLeft)
                        }
                        Text(
                            Bidi.fileName(item.item.path.substringAfterLast('/')),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Owner brief H2 item 8: the photo's date and size, not only its name.
                        Text(detail, style = KeepClearTheme.type.number, modifier = Modifier.testTag("swipeDate"))
                    }
                    DecisionButtons(
                        deleteFirst = deleteOnLeft != rtl,
                        onKeep = { decide(SwipeChoice.KEEP) },
                        onSkip = { decide(SwipeChoice.SKIP) },
                        onDelete = { decide(SwipeChoice.DELETE) },
                    )
                } else {
                    val listed = items.mapTo(HashSet()) { it.item.path }
                    val kept = current.count { it.choice == SwipeChoice.KEEP && it.path in listed }
                    val skipped = current.count { it.choice == SwipeChoice.SKIP && it.path in listed }
                    Column(Modifier.testTag("swipeSummary"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (toDelete.isEmpty()) {
                                stringResource(R.string.swipe_summary_none)
                            } else {
                                stringResource(
                                    R.string.swipe_summary_delete,
                                    totalText(toDelete.fold(Total()) { t, i -> t + i }),
                                )
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(countSentence(R.plurals.swipe_kept, kept))
                        if (skipped > 0) Text(countSentence(R.plurals.swipe_skipped, skipped))
                        Text(stringResource(R.string.swipe_summary_note), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                }
                // Owner brief H2 item 8: always on screen, so it is never "2,324 decisions or nothing". It ends the
                // session and opens the usual confirmation for the items marked Delete so far, nothing else.
                KcButton(
                    if (toDelete.isEmpty()) {
                        stringResource(R.string.swipe_finish_none)
                    } else {
                        stringResource(R.string.swipe_finish, countText(toDelete.size))
                    },
                    onClick = { onDone(toDelete) },
                    enabled = toDelete.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("swipeFinish"),
                )
            }
        }
    }
}

/** Keep and Delete with equal weight and an icon each (neither is the filled primary; Delete goes to the trash). */
@Composable
private fun DecisionButtons(deleteFirst: Boolean, onKeep: () -> Unit, onSkip: () -> Unit, onDelete: () -> Unit) {
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > LARGE_FONT
    val skip = @Composable { m: Modifier ->
        KcButton(
            stringResource(R.string.swipe_skip),
            onClick = onSkip,
            style = KcButtonStyle.Text,
            modifier = m.testTag("swipeSkip"),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(KcSpacing.s), modifier = Modifier.fillMaxWidth()) {
        val keep = @Composable {
            KcButton(
                stringResource(R.string.swipe_keep),
                onClick = onKeep,
                style = KcButtonStyle.Tonal,
                icon = KcIcons.Selected,
                compact = large,
                modifier = Modifier.weight(1f).testTag("swipeKeep"),
            )
        }
        val delete = @Composable {
            KcButton(
                stringResource(R.string.swipe_delete),
                onClick = onDelete,
                style = KcButtonStyle.Tonal,
                icon = KcIcons.Trash,
                compact = large,
                modifier = Modifier.weight(1f).testTag("swipeDelete"),
            )
        }
        // The Delete button is on the side a swipe deletes toward (C148): the left, unless the directions are swapped.
        if (deleteFirst) delete() else keep()
        if (!large) skip(Modifier)
        if (deleteFirst) keep() else delete()
    }
    // With a large font Skip gets its own line, so „להשאיר” and „למחוק” keep whole words.
    if (large) skip(Modifier.fillMaxWidth())
}

/**
 * Owner brief H2 item 8: while the card is dragged, an edge says what letting go means („למחוק” / „להשאיר”), fading
 * in up to the threshold. It sits on the side the card moves away from, so it stays in view.
 */
@Composable
private fun BoxScope.EdgeLabel(drag: Float, threshold: Float, deleteOnLeft: Boolean) {
    if (drag == 0f) return
    val left = drag < 0
    val choice = if (left == deleteOnLeft) SwipeChoice.DELETE else SwipeChoice.KEEP
    val strength = (kotlin.math.abs(drag) / threshold.coerceAtLeast(1f)).coerceIn(0f, 1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KcSpacing.xs),
        modifier = Modifier.align(if (left) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft)
            .padding(KcSpacing.m)
            .alpha(EDGE_MIN_ALPHA + (1f - EDGE_MIN_ALPHA) * strength)
            .background(MaterialTheme.colorScheme.surface, KcShapes.chip)
            .border(2.dp, MaterialTheme.colorScheme.primary, KcShapes.chip)
            .padding(horizontal = KcSpacing.m, vertical = KcSpacing.s)
            .testTag("swipeLabel"),
    ) {
        Icon(
            if (choice == SwipeChoice.DELETE) KcIcons.Trash else KcIcons.Selected,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            stringResource(if (choice == SwipeChoice.DELETE) R.string.swipe_delete else R.string.swipe_keep),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("swipeLabel_${choice.name}"),
        )
    }
}

/** Owner brief H2 item 8: shown until the first decision ever; says which way deletes (C148). */
@Composable
private fun DirectionHint(deleteOnLeft: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = KcShapes.chip,
        modifier = Modifier.fillMaxWidth().testTag("swipeHint"),
    ) {
        Column(Modifier.padding(KcSpacing.m), verticalArrangement = Arrangement.spacedBy(KcSpacing.xs)) {
            Text(
                stringResource(if (deleteOnLeft) R.string.swipe_dir_left_deletes else R.string.swipe_dir_right_deletes),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("swipeHintText"),
            )
            Text(stringResource(R.string.swipe_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** F2: how far the card tilts at the edge of the screen (subtle; none under reduced motion). */
private const val MAX_TILT_DEGREES = 8f

/** From this font scale on, Skip is on its own line under Keep and Delete. */
private const val LARGE_FONT = 1.3f

/** The edge label is faint at the first touch and fully there at the threshold. */
private const val EDGE_MIN_ALPHA = 0.35f
