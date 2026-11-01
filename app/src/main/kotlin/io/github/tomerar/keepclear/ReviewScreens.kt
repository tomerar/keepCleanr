// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
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

@Composable
internal fun BackRow(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text(stringResource(R.string.back)) }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** All groups of one category, largest first (REV-1). Nothing is selected or selectable yet. */
@Composable
fun CategoryListScreen(
    summary: ClassificationSummary,
    category: Category,
    onOpenGroup: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("categoryList"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BackRow(categoryTitle(category), onBack)
                Text(totalText(summary.total(category)), style = MaterialTheme.typography.bodyLarge)
            }
            items(summary.groups(category), key = { it.ruleId }) { group ->
                val text = RuleText.of(group.ruleId)
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onOpenGroup(group.ruleId)
                    }.testTag("group_${group.ruleId}"),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(text.group), style = MaterialTheme.typography.titleMedium)
                        Text(totalText(group.total), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(text.why), style = MaterialTheme.typography.bodySmall)
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
) {
    val text = RuleText.of(group.ruleId)
    val selectable = group.category == Category.SAFE || group.category == Category.REVIEW
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirming by remember { mutableStateOf<List<ClassifiedItem>?>(null) }
    val selectedItems = group.items.filter { it.item.path in selected }
    Scaffold(
        bottomBar = {
            // Review PR C: no action bar until something is selected; padded above the navigation bar.
            if (selectable && selectedItems.isNotEmpty()) {
                Button(
                    onClick = { confirming = selectedItems },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("deleteSelected"),
                ) {
                    Text(
                        stringResource(
                            R.string.review_delete,
                            totalText(
                                selectedItems.fold(Total()) { t, i ->
                                    t + i
                                },
                            ),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.padding(padding).fillMaxSize().testTag("grid"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BackRow(stringResource(text.group), onBack)
                    Text(totalText(group.total), style = MaterialTheme.typography.bodyLarge)
                    if (selectable) {
                        val all = group.items.map { it.item.path }.toSet()
                        val allSelected = all.isNotEmpty() && selected.containsAll(all)
                        TextButton(
                            onClick = { selected = if (allSelected) emptySet() else all },
                            modifier = Modifier.testTag("selectAll"),
                        ) {
                            Text(
                                stringResource(
                                    if (allSelected) R.string.review_unselect_all else R.string.review_select_all,
                                ),
                            )
                        }
                        Text(
                            stringResource(R.string.review_long_press_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    result?.let { r ->
                        Card(modifier = Modifier.fillMaxWidth().testTag("deleteResult")) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(
                                        if (r.simulated) R.string.test_mode_would_free else R.string.clean_done,
                                        totalText(r.done),
                                    ),
                                    modifier = Modifier.weight(1f),
                                )
                                if (!r.simulated) {
                                    TextButton(onClick = {
                                        onUndo(r, userConfirmed(r.trashedPaths))
                                    }, modifier = Modifier.testTag("undo")) {
                                        Text(stringResource(R.string.clean_undo))
                                    }
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.label_why), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(text.why), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.label_if_deleted), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(text.ifDeleted), style = MaterialTheme.typography.bodyMedium)
                }
            }
            for ((bucket, items) in group.buckets) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "h_$bucket") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    ) {
                        Text(
                            bucketTitle(bucket),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).testTag("bucket_$bucket"),
                        )
                        if (selectable) {
                            val paths = items.map { it.item.path }.toSet()
                            val all = selected.containsAll(paths)
                            TextButton(
                                onClick = { selected = if (all) selected - paths else selected + paths },
                                modifier = Modifier.testTag("selectMonth_$bucket"),
                            ) {
                                Text(
                                    stringResource(
                                        if (all) R.string.review_unselect_month else R.string.review_select_month,
                                    ),
                                )
                            }
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
                            selected = if (isSelected) selected - item.item.path else selected + item.item.path
                        },
                        onPreview = { onOpenItem(item) },
                    )
                }
            }
        }
    }

    confirming?.let { list ->
        EditableConfirm(
            items = list,
            onRemove = { removed ->
                val rest = list - removed
                confirming = rest.ifEmpty { null }
                selected = selected - removed.item.path
            },
            onConfirm = {
                confirming = null
                selected = emptySet()
                onDelete(list, userConfirmed(list.map { it.item.path }))
            },
            onDismiss = { confirming = null },
        )
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
    val total = items.fold(Total()) { t, i -> t + i }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_confirm_title, totalText(total))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.review_confirm_body))
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp).testTag("confirmList")) {
                    items(items, key = { it.item.path }) { item ->
                        // REV-3: a thumbnail and the folder, so files with the same name can be told apart.
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("confirmRow")) {
                            Box(
                                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant),
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
                            val keep = stringResource(R.string.review_remove_from_list)
                            TextButton(
                                onClick = {
                                    onRemove(item)
                                },
                                modifier = Modifier.semantics { contentDescription = keep }
                                    .testTag("remove_${item.item.path.substringAfterLast('/')}"),
                            ) {
                                Text("✕")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            SafeConfirmButton(
                stringResource(R.string.clean_confirm_yes),
                onClick = onConfirm,
                modifier = Modifier.testTag("confirmDelete"),
                permanent = false,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.trash_cancel)) } },
    )
}

/**
 * One picture (REV-2). Review PR C: for TalkBack it is a checkbox with the file name and its state; a tap
 * toggles it, a long press (or ⤢) shows it large.
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
    Box(
        modifier = modifier
            .aspectRatio(1f)
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
            }
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            )
            .testTag(if (selected) "tileSelected" else "tile"),
        contentAlignment = Alignment.Center,
    ) {
        Thumb(item, 96)
        if (selected) {
            Text(
                "✓",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clearAndSetSemantics { },
            )
        }
        // A small corner target, so a tap anywhere else on the tile selects it; long press also previews.
        Box(
            modifier = Modifier.align(Alignment.BottomEnd)
                .size(32.dp)
                .clickable(onClickLabel = previewLabel, role = Role.Button, onClick = onPreview)
                .semantics { contentDescription = previewLabel }
                .testTag("preview_${item.item.path.substringAfterLast('/')}"),
            contentAlignment = Alignment.Center,
        ) {
            Text("⤢", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The thumbnail, or the file name when there is none (no loader, unsupported type, or loading failed). */
@Composable
private fun Thumb(item: ClassifiedItem, sizeDp: Int, showName: Boolean = true) {
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

/** One item: preview, exact size and date, and the plain-language explanation (CLS-2). */
@Composable
fun PreviewScreen(item: ClassifiedItem, onBack: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val text = RuleText.of(item.ruleId)
    val date = Instant.ofEpochMilli(item.item.mtimeMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("preview"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { BackRow(stringResource(text.group), onBack) }
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
