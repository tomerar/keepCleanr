// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmSheet
import io.github.tomerar.keepclear.core.designsystem.component.InfoChip
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.designsystem.component.ThumbStrip
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.Confirmation
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.GroupSummary
import io.github.tomerar.keepclear.engine.classify.Total
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun categoryTitle(category: Category): String = stringResource(
    when (category) {
        Category.SAFE -> R.string.home_card_safe_title
        Category.REVIEW -> R.string.home_card_review_title
        Category.MOVE -> R.string.home_card_move_title
        Category.PROTECTED -> R.string.group_protected
    },
)

@Composable
fun totalText(total: Total): String {
    val locale = LocalConfiguration.current.locales[0]
    val count =
        pluralStringResource(
            R.plurals.file_count,
            total.files,
            NumberFormat.getIntegerInstance(locale).format(total.files),
        )
    // Both parts isolated: a line that starts with "7.9 GB" still takes the UI's direction (review PR C).
    return stringResource(R.string.total_value, Bidi.isolate(ByteSize.format(total.bytes, locale)), Bidi.isolate(count))
}

@Composable
private fun bucketTitle(bucket: String?): String {
    val locale = LocalConfiguration.current.locales[0]
    return when {
        bucket == null -> stringResource(R.string.bucket_other)

        else -> runCatching {
            YearMonth.parse(bucket).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        }.getOrDefault(bucket)
    }
}

/** Owner brief H2 item 12: the smallest review tile; 4 columns on a 360 dp phone, more on wider screens. */
internal val REVIEW_TILE_MIN = 80.dp

/** All groups of one category, largest first (REV-1). Nothing is selected or selectable yet. */
@Composable
fun CategoryListScreen(
    summary: ClassificationSummary,
    category: Category,
    onOpenGroup: (String) -> Unit,
    onBack: () -> Unit,
    /** Owner brief H2 item 11: the empty list's one next step (the Backup tab). */
    onBackUp: () -> Unit = {},
) {
    Scaffold(topBar = { SubScreenTopBar(categoryTitle(category), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("categoryList"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val groups = summary.groups(category)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (groups.isNotEmpty()) {
                        Text(totalText(summary.total(category)), style = KeepClearTheme.type.number)
                    }
                    // Owner brief H2 item 7: the lane is about deleting, and nothing happens without the user.
                    if (category == Category.REVIEW) {
                        Text(
                            stringResource(R.string.review_nothing_without_you),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("nothingWithoutYou"),
                        )
                    }
                }
            }
            if (groups.isEmpty()) {
                item {
                    EmptyNext(R.string.review_list_empty, R.string.home_big_win_backup_button, "reviewEmpty", onBackUp)
                }
            }
            items(groups, key = { it.ruleId }) { group ->
                val text = RuleText.of(group.ruleId)
                // F2: a group is recognised by its pictures (ThumbStrip), not by a paragraph.
                val thumbs = rememberThumbPainters(group.items)
                KcCard(onClick = { onOpenGroup(group.ruleId) }, modifier = Modifier.testTag("group_${group.ruleId}")) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(text.group), style = MaterialTheme.typography.titleMedium)
                        Text(totalText(group.total), style = KeepClearTheme.type.number)
                        if (thumbs.isNotEmpty()) ThumbStrip(thumbs, total = group.total.files)
                        Text(
                            stringResource(text.why),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One group as a thumbnail grid with month headers (REV-2). For SAFE and REVIEW groups the user can
 * select items or whole months (nothing is pre-selected, SAFETY-10), review the selection in an editable
 * confirmation (REV-3) and move it to the trash. MOVE groups stay read-only until backup exists (M10).
 */
@Composable
fun GroupScreen(
    group: GroupSummary,
    onOpenItem: (ClassifiedItem) -> Unit,
    onBack: () -> Unit,
    onDelete: (List<ClassifiedItem>, Confirmation) -> Unit = { _, _ -> },
    result: CleanResult? = null,
    onUndo: (CleanResult, Confirmation) -> Unit = { _, _ -> },
    /** E4: the selection and one-by-one decisions, held by the ViewModel; null keeps them here (tests). */
    selection: GroupSelection? = null,
    /** PR #48 review: the result's "Empty now" opens the Trash, where emptying is confirmed separately. */
    onOpenTrash: () -> Unit = {},
) {
    val text = RuleText.of(group.ruleId)
    // SAFE-9: other apps' own folders are never selectable for deletion here.
    val selectable = (group.category == Category.SAFE || group.category == Category.REVIEW) &&
        group.ruleId != io.github.tomerar.keepclear.engine.classify.AppContent.RULE_ID
    // E4: never a 10,000-path set in saved state (TransactionTooLarge); the ViewModel keeps it.
    var localSelected by remember { mutableStateOf(setOf<String>()) }
    var localSwipe by remember { mutableStateOf<List<SwipeDecision>?>(null) }
    var localSaved by remember { mutableStateOf<List<SwipeDecision>?>(null) }
    val selected = selection?.selected ?: localSelected
    val setSelected: (Set<String>) -> Unit = selection?.onSelect ?: { localSelected = it }
    val swipe = if (selection != null) selection.swipe else localSwipe
    // PR H2b: without a ViewModel (tests) the session is kept here, as the ViewModel keeps it on disk.
    val setSwipe: (List<SwipeDecision>?) -> Unit = selection?.onSwipe ?: { d ->
        localSwipe = d
        if (d != null) localSaved = d
    }
    val saved = if (selection != null) selection.saved else localSaved
    val endSwipe: () -> Unit = selection?.onSwipeEnd ?: { localSaved = null }
    var askResume by remember { mutableStateOf<List<SwipeDecision>?>(null) }
    var confirming by remember { mutableStateOf<List<ClassifiedItem>?>(null) }
    var why by remember { mutableStateOf(false) }
    val selectedItems = remember(group.items, selected) { group.items.filter { it.item.path in selected } }
    Scaffold(
        topBar = { SubScreenTopBar(stringResource(text.group), onBack) },
        bottomBar = {
            // Review PR C: no action bar until something is selected; padded above the navigation bar.
            if (selectable && selectedItems.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    KcButton(
                        stringResource(
                            R.string.review_delete,
                            totalText(
                                selectedItems.fold(Total()) { t, i ->
                                    t + i
                                },
                            ),
                        ),
                        onClick = { confirming = selectedItems },
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
                            .testTag("deleteSelected"),
                    )
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            // F2: four columns of thumbnails on a phone (brief "Review group"); owner brief H2 item 12: more on
            // tablets and foldables (at least 80 dp each).
            columns = GridCells.Adaptive(minSize = REVIEW_TILE_MIN),
            modifier = Modifier.padding(padding).fillMaxSize().testTag("grid"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result?.let { r ->
                        CleanResultBanner(
                            r,
                            onUndo,
                            onOpenTrash = onOpenTrash,
                            modifier = Modifier.testTag("deleteResult"),
                        )
                    }
                    Text(totalText(group.total), style = KeepClearTheme.type.number)
                    // F2: "Why is this here?" opens a sheet; no wall of text above the pictures.
                    InfoChip(stringResource(R.string.label_why), onClick = { why = true }, Modifier.testTag("whyChip"))
                    if (selectable) {
                        val all = remember(group.items) { group.items.mapTo(HashSet()) { it.item.path } }
                        val allSelected = all.isNotEmpty() && selected.containsAll(all)
                        // One row, so the pictures stay where they were on small screens.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // REV-5: decide one by one; nothing happens until the usual confirmation at the end.
                            if (group.items.size > 1) {
                                KcButton(
                                    stringResource(R.string.swipe_start),
                                    onClick = {
                                        // PR H2b: a session left earlier is offered first („להמשיך מאיפה שעצרתם”).
                                        // Nothing left of it (every file changed or went away): a new start.
                                        val raw = saved.orEmpty()
                                        if (SwipeSession.resume(group.items, raw).isNotEmpty()) {
                                            askResume = raw
                                        } else {
                                            if (raw.isNotEmpty()) endSwipe()
                                            setSwipe(emptyList())
                                        }
                                    },
                                    style = KcButtonStyle.Tonal,
                                    modifier = Modifier.testTag("startSwipe"),
                                )
                            }
                            KcButton(
                                stringResource(
                                    if (allSelected) R.string.review_unselect_all else R.string.review_select_all,
                                ),
                                onClick = { setSelected(if (allSelected) emptySet() else all) },
                                style = KcButtonStyle.Text,
                                modifier = Modifier.testTag("selectAll"),
                            )
                        }
                        Text(
                            stringResource(R.string.review_long_press_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            for ((bucket, items) in group.buckets) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "h_$bucket") {
                    // Owner brief H2 item 12: the month's name keeps its whole line when the button doesn't fit beside
                    // it (at 200% it went "אוקטוב/ר 2026"); the button then wraps below it.
                    FlowRow(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    ) {
                        Text(
                            bucketTitle(bucket),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.align(Alignment.CenterVertically).semantics { heading() }
                                .testTag("bucket_$bucket"),
                        )
                        if (selectable) {
                            val paths = items.map { it.item.path }.toSet()
                            val all = selected.containsAll(paths)
                            KcButton(
                                stringResource(
                                    if (all) R.string.review_unselect_month else R.string.review_select_month,
                                ),
                                onClick = { setSelected(if (all) selected - paths else selected + paths) },
                                style = KcButtonStyle.Text,
                                modifier = Modifier.testTag("selectMonth_$bucket"),
                            )
                        }
                    }
                }
                items(items, key = { it.item.path }) { item ->
                    val isSelected = item.item.path in selected
                    Tile(
                        item,
                        selected = isSelected,
                        selectable = selectable,
                        onToggle = {
                            setSelected(if (isSelected) selected - item.item.path else selected + item.item.path)
                        },
                        onPreview = { onOpenItem(item) },
                    )
                }
            }
        }
    }

    if (why) {
        InfoSheet(
            title = stringResource(text.group),
            dismissText = stringResource(R.string.home_notice_dismiss),
            onDismiss = { why = false },
            modifier = Modifier.testTag("whySheet"),
        ) {
            Text(stringResource(R.string.label_why), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(text.why), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.label_if_deleted), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(text.ifDeleted), style = MaterialTheme.typography.bodyMedium)
        }
    }
    askResume?.let { raw ->
        ResumeSwipeSheet(
            items = group.items,
            saved = raw,
            onContinue = {
                askResume = null
                setSwipe(SwipeSession.resume(group.items, raw))
            },
            onRestart = {
                askResume = null
                endSwipe()
                setSwipe(emptyList())
            },
            onDismiss = { askResume = null },
        )
    }
    if (swipe != null) {
        SwipeReview(
            items = group.items,
            onDone = { list ->
                // „לסיים ולבדוק” ends the session: what was marked goes to the usual confirmation, nothing else.
                setSwipe(null)
                endSwipe()
                setSelected(list.map { it.item.path }.toSet())
                if (list.isNotEmpty()) confirming = list
            },
            onClose = { setSwipe(null) },
            decisions = swipe,
            onDecisions = { setSwipe(it) },
        )
    }
    confirming?.let { list ->
        EditableConfirm(
            items = list,
            onRemove = { removed ->
                val rest = list - removed
                confirming = rest.ifEmpty { null }
                setSelected(selected - removed.item.path)
            },
            onConfirm = {
                confirming = null
                setSelected(emptySet())
                onDelete(list, userConfirmed(list.map { it.item.path }))
            },
            onDismiss = { confirming = null },
        )
    }
}

/** E4: a group's selection and one-by-one decisions, kept by the ViewModel ([HomeUiState.selections]). */
class GroupSelection(
    val selected: Set<String>,
    val onSelect: (Set<String>) -> Unit,
    val swipe: List<SwipeDecision>?,
    val onSwipe: (List<SwipeDecision>?) -> Unit,
    /** PR H2b: the session saved for this group (as left; [SwipeSession.resume] drops what changed), or null. */
    val saved: List<SwipeDecision>? = null,
    /** PR H2b: the session ended („לסיים ולבדוק” or „להתחיל מחדש”): forget the saved one. */
    val onSwipeEnd: () -> Unit = {},
)

/**
 * PR H2b: „להמשיך מאיפה שעצרתם”. Says how far the user got and how many are marked, and how many decisions were
 * dropped because their file changed or went away since; starting over forgets the saved session.
 */
@Composable
private fun ResumeSwipeSheet(
    items: List<ClassifiedItem>,
    saved: List<SwipeDecision>,
    onContinue: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val kept = SwipeSession.resume(items, saved)
    val dropped = saved.size - kept.size
    val marked = SwipeSession.toDelete(items, kept).size
    val title = stringResource(
        R.string.swipe_resume_title,
        countText(SwipeSession.decided(items, kept)),
        countText(items.size),
    )
    InfoSheet(
        title = title,
        dismissText = stringResource(R.string.swipe_resume_restart),
        onDismiss = onDismiss,
        dismissAction = onRestart,
        dismissModifier = Modifier.testTag("swipeResumeRestart"),
        modifier = Modifier.testTag("swipeResume"),
        primaryButton = {
            KcButton(
                stringResource(R.string.swipe_resume_continue),
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().testTag("swipeResumeContinue"),
            )
        },
    ) {
        Text(
            if (marked == 0) {
                stringResource(R.string.swipe_resume_none_marked)
            } else {
                countSentence(R.plurals.swipe_resume_marked, marked)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (dropped > 0) {
            Text(
                countSentence(R.plurals.swipe_resume_dropped, dropped),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("swipeResumeDropped"),
            )
        }
    }
}

/** REV-3: the user sees and can trim exactly what will move to the trash. */
@Composable
private fun EditableConfirm(
    items: List<ClassifiedItem>,
    onRemove: (ClassifiedItem) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Owner decision 2026-11-02: a SAFE group opened from "Clean now" can hold items that are deleted for good.
    val text = cleanSheetText(items)
    ConfirmSheet(
        title = stringResource(R.string.review_confirm_title, sizeOnly(items)),
        dismissText = stringResource(R.string.trash_cancel),
        onDismiss = onDismiss,
        facts = text.facts,
        note = if (text.anyForGood) text.note else stringResource(R.string.review_confirm_body),
        risk = text.risk,
        modifier = Modifier.testTag("reviewConfirm"),
        confirmButton = {
            SafeConfirmButton(
                stringResource(text.confirmLabel),
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().testTag("confirmDelete"),
                permanent = false,
            )
        },
    ) {
        // Every row can be taken out (REV-3). A lazy list with a bounded height inside the scrolling sheet, so a
        // selection of thousands composes only what is on screen.
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp).testTag("confirmList")) {
            items(items, key = { it.item.path }) { item -> ConfirmRow(item, onRemove) }
        }
    }
}

/** REV-3: a thumbnail and the folder, so files with the same name can be told apart; ✕ takes it out. */
@Composable
private fun ConfirmRow(item: ClassifiedItem, onRemove: (ClassifiedItem) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("confirmRow")) {
        Box(
            modifier = Modifier.size(48.dp).clip(KcShapes.thumbnail)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Thumb(item, 48, showName = false) }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                Bidi.fileName(item.item.path.substringAfterLast('/')),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                Bidi.isolateLtr(item.item.path.substringBeforeLast('/')),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.testTag("confirmFolder"),
            )
        }
        IconButton(
            onClick = { onRemove(item) },
            modifier = Modifier.testTag("remove_${item.item.path.substringAfterLast('/')}"),
        ) {
            Icon(KcIcons.Close, contentDescription = stringResource(R.string.review_remove_from_list))
        }
    }
}

/**
 * One picture (REV-2). Review PR C: for TalkBack it is a checkbox with the file name and its state; a tap toggles it,
 * a long press shows it large. Design QA item 6: no corner glyph; TalkBack offers "Show it large" as a custom action
 * (and as the long-press label), so the preview stays reachable without a long press.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    item: ClassifiedItem,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectable: Boolean = true,
    onToggle: () -> Unit = {},
    onPreview: () -> Unit = {},
) {
    val name = item.item.path.substringAfterLast('/')
    val state = stringResource(if (selected) R.string.review_tile_selected else R.string.review_tile_not_selected)
    val previewLabel = stringResource(R.string.review_preview)
    // The outer box only names the picture for tests (outside the tile's merged semantics).
    Box(modifier.aspectRatio(1f).testTag("preview_$name")) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(KcShapes.thumbnail)
                .combinedClickable(
                    onClick = { if (selectable) onToggle() else onPreview() },
                    onLongClick = onPreview,
                    onLongClickLabel = previewLabel,
                    role = if (selectable) Role.Checkbox else Role.Image,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = Bidi.strip(name)
                    if (selectable) {
                        toggleableState = ToggleableState(selected)
                        stateDescription = state
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(previewLabel) {
                            onPreview()
                            true
                        },
                    )
                }
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .testTag(if (selected) "tileSelected" else "tile"),
            contentAlignment = Alignment.Center,
        ) {
            Thumb(item, 96)
            if (selected) {
                // F2: a check-circle on the selected picture (with the state, never colour alone).
                Icon(
                    KcIcons.Selected,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                        .testTag("tileCheck")
                        .clearAndSetSemantics { },
                )
            }
        }
    }
}

/** The thumbnail, or the file name when there is none (no loader, unsupported type, or loading failed). */
@Composable
internal fun Thumb(item: ClassifiedItem, sizeDp: Int, showName: Boolean = true) {
    // PR #28 review: a new path starts from no picture, so the previous file's bitmap never shows next to the
    // next file's name while it loads (a quick Keep/Delete would otherwise act on a different picture).
    androidx.compose.runtime.key(item.item.path) { ThumbContent(item, sizeDp, showName) }
}

@Composable
private fun ThumbContent(item: ClassifiedItem, sizeDp: Int, showName: Boolean) {
    val loader = LocalThumbnailLoader.current
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.item.path, loader) {
        value = loader?.load(item.item, px)
    }
    val image = bitmap
    if (image != null) {
        Image(
            image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else if (showName) {
        Text(
            Bidi.fileName(item.item.path.substringAfterLast('/')),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(4.dp).testTag("tileName"),
        )
    }
}

/** CLS-7: the user's own choices from the item screen. */
class PreviewActions(
    /** "This is important to me": never offer this file again. */
    val onKeep: (String) -> Unit = {},
    /** "Always treat this folder as junk". */
    val onAlwaysJunk: (String) -> Unit = {},
)

/** One item: preview, exact size and date, and the plain-language explanation (CLS-2). */
@Composable
fun PreviewScreen(item: ClassifiedItem, onBack: () -> Unit, actions: PreviewActions = PreviewActions()) {
    var confirmJunk by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val folder = item.item.path.substringBeforeLast('/')
    // Not for what may be a memory or a chat, nor for what is already protected (decided conservatively, C12).
    val canKeep = item.category != Category.PROTECTED
    val canJunk = canKeep && item.ruleId != io.github.tomerar.keepclear.engine.classify.Rules.USER_JUNK &&
        (item.category == Category.REVIEW || item.category == Category.SAFE) &&
        item.risk != io.github.tomerar.keepclear.engine.classify.RiskChip.MIGHT_BE_MEMORY &&
        item.risk != io.github.tomerar.keepclear.engine.classify.RiskChip.YOUR_CHATS &&
        UserChoices.canBeJunk(folder)
    val locale = LocalConfiguration.current.locales[0]
    val text = RuleText.of(item.ruleId)
    val date = Instant.ofEpochMilli(item.item.mtimeMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    Scaffold(topBar = { SubScreenTopBar(stringResource(text.group), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("preview"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(
                        1f,
                    ).background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Thumb(item, 360)
                }
            }
            item {
                Text(
                    Bidi.isolateLtr(item.item.path),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("path"),
                )
                Text(ByteSize.format(item.item.size, locale), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.preview_modified, date), style = MaterialTheme.typography.bodyMedium)
                AssistChip(onClick = {}, enabled = false, label = { Text(riskText(item)) })
                if (ClonedApps.isCloned(item.item.path)) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.cloned_label)) },
                        modifier = Modifier.testTag("clonedChip"),
                    )
                }
                if (item.keepSignal) {
                    Text(
                        stringResource(R.string.keep_signal_note),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Text(stringResource(R.string.label_why), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(text.why), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.label_if_deleted), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(text.ifDeleted), style = MaterialTheme.typography.bodyMedium)
            }
            if (canKeep) {
                item {
                    KcButton(
                        stringResource(R.string.choice_keep),
                        onClick = {
                            actions.onKeep(item.item.path)
                            onBack()
                        },
                        style = KcButtonStyle.Tonal,
                        modifier = Modifier.fillMaxWidth().testTag("keepItem"),
                    )
                }
            }
            if (canJunk) {
                item {
                    KcButton(
                        stringResource(R.string.choice_always_junk),
                        onClick = { confirmJunk = true },
                        style = KcButtonStyle.Text,
                        modifier = Modifier.testTag("alwaysJunk"),
                    )
                }
            }
        }
    }
    if (confirmJunk) {
        // Not a deletion: it changes what the next scan offers, and Settings can undo it (CLS-7).
        ConfirmSheet(
            title = stringResource(R.string.choice_always_junk),
            dismissText = stringResource(R.string.move_cancel),
            onDismiss = { confirmJunk = false },
            confirmButton = {
                KcButton(
                    stringResource(R.string.choice_always_junk_confirm),
                    onClick = {
                        confirmJunk = false
                        actions.onAlwaysJunk(folder)
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("confirmAlwaysJunk"),
                )
            },
        ) {
            Text(
                stringResource(R.string.choice_always_junk_body, Bidi.isolateLtr(folder)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun riskText(item: ClassifiedItem): String = stringResource(
    when (item.risk) {
        io.github.tomerar.keepclear.engine.classify.RiskChip.REGENERATES -> R.string.risk_REGENERATES
        io.github.tomerar.keepclear.engine.classify.RiskChip.COPY_EXISTS -> R.string.risk_COPY_EXISTS
        io.github.tomerar.keepclear.engine.classify.RiskChip.ALREADY_DELETED -> R.string.risk_ALREADY_DELETED
        io.github.tomerar.keepclear.engine.classify.RiskChip.MIGHT_BE_MEMORY -> R.string.risk_MIGHT_BE_MEMORY
        io.github.tomerar.keepclear.engine.classify.RiskChip.YOUR_CHATS -> R.string.risk_YOUR_CHATS
        io.github.tomerar.keepclear.engine.classify.RiskChip.UNKNOWN -> R.string.risk_UNKNOWN
    },
)
