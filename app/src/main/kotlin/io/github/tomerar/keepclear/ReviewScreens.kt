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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.liveRegion
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
import io.github.tomerar.keepclear.core.designsystem.component.BalancedChipRow
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmSheet
import io.github.tomerar.keepclear.core.designsystem.component.InfoChip
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.designsystem.component.KcSearchField
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

/** "2,324 files · 2.9GB": a file count with its size, always count first (owner brief H2 item 10). */
@Composable
fun totalText(total: Total): String {
    val locale = LocalConfiguration.current.locales[0]
    val count =
        pluralStringResource(
            R.plurals.file_count,
            total.files,
            NumberFormat.getIntegerInstance(locale).format(total.files),
        )
    return countAndSize(count, ByteSize.format(total.bytes, locale))
}

/**
 * The one way a count and a size are shown together (owner brief H2 item 10): "[count] · [size]", count first, each
 * part isolated so a line still takes the UI's direction (review PR C). [count] is already a sentence part
 * ("2,324 files", "3 apps").
 */
@Composable
fun countAndSize(count: String, size: String): String =
    stringResource(R.string.total_value, Bidi.isolate(count), Bidi.isolate(size))

/**
 * "Selected: 2,324 files · 2.9GB", or „לא נבחר כלום” / "Nothing selected" when nothing is: an empty selection never
 * reads "0 files · 0B" (owner brief H2 item 10). [label] takes the total as its one argument.
 */
@Composable
fun selectionText(total: Total, label: Int): String =
    if (total.files == 0) stringResource(R.string.nothing_selected) else stringResource(label, totalText(total))

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
    /** G4a: "Find similar photos" in the review lane; null where it is not offered. */
    onFindSimilar: (() -> Unit)? = null,
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
            if (onFindSimilar != null) item { GalleryEntryCard(onFindSimilar) }
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
    // PR H5: sort, year and search (kept while the screen is in the stack); the default shows the group as before.
    var sortName by rememberSaveable { mutableStateOf(GroupSort.NEWEST.name) }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var askLargest by remember { mutableStateOf(false) }
    val view = GroupView(GroupSort.valueOf(sortName), year, query)
    val zone = remember { ZoneId.systemDefault() }
    val sections = remember(group, view) { GroupTools.sections(group, view, zone) }
    val visible = remember(sections) { GroupTools.visible(sections) }
    // Owner brief H4 item 2: with something selected, Back (predictive, it can be cancelled half-way) first leaves
    // the selection; the next Back leaves the group. Enabled only while there is a selection (never always on).
    androidx.activity.compose.PredictiveBackHandler(
        enabled = selectable && selected.isNotEmpty() && swipe == null,
    ) { progress ->
        // A cancelled gesture ends the collection with a CancellationException: the selection stays.
        progress.collect { }
        setSelected(emptySet())
    }
    Scaffold(
        topBar = { SubScreenTopBar(stringResource(text.group), onBack) },
        bottomBar = {
            // Review PR C: no action bar until something is selected; padded above the navigation bar.
            if (selectable && selectedItems.isNotEmpty()) {
                val selectedTotal = selectedItems.fold(Total()) { t, i -> t + i }
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        // PR H5: the selection count bar: count · size, how many of them a filter hides, and a way
                        // out of the selection.
                        SelectionBar(
                            total = selectedTotal,
                            hidden = selectedItems.size - visible.count { it.item.path in selected },
                            onClear = { setSelected(emptySet()) },
                        )
                        KcButton(
                            stringResource(R.string.review_delete, totalText(selectedTotal)),
                            onClick = { confirming = selectedItems },
                            modifier = Modifier.fillMaxWidth().testTag("deleteSelected"),
                        )
                    }
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
                    // PR H5: a non-intrusive way in; the tools stay folded until asked for (product principle 2).
                    // Review of PR #61: while a year or a search is in use the tools stay, even with one file left,
                    // so a filter that hides that file can always be cleared.
                    if (group.items.size > 1 || view.filtered) {
                        GroupToolsPanel(
                            group = group,
                            view = view,
                            open = toolsOpen || view != GroupView(),
                            onToggle = { toolsOpen = !toolsOpen },
                            onSort = { sortName = it.name },
                            onYear = { year = it },
                            onQuery = { query = it },
                            onClear = {
                                year = null
                                query = ""
                            },
                            shown = visible.size,
                            zone = zone,
                            onLargest = if (selectable && GroupTools.choices(visible.size).isNotEmpty()) {
                                { askLargest = true }
                            } else {
                                null
                            },
                        )
                    }
                    if (selectable) {
                        // PR H5: with a year or a search, "select all" means what is shown (never hidden files).
                        val all = remember(visible) { visible.mapTo(HashSet()) { it.item.path } }
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
                                    when {
                                        allSelected -> R.string.review_unselect_all
                                        view.filtered -> R.string.group_select_shown
                                        else -> R.string.review_select_all
                                    },
                                ),
                                onClick = { setSelected(if (allSelected) selected - all else selected + all) },
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
            for (section in sections) {
                val bucket = section.bucket
                val items = section.items
                item(span = { GridItemSpan(maxLineSpan) }, key = "h_$bucket") {
                    // Owner brief H2 item 12: the month's name keeps its whole line when the button doesn't fit beside
                    // it (at 200% it went "אוקטוב/ר 2026"); the button then wraps below it.
                    FlowRow(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    ) {
                        Text(
                            if (section.byMonth) bucketTitle(bucket) else stringResource(R.string.group_sort_largest),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.align(Alignment.CenterVertically).semantics { heading() }
                                .testTag("bucket_$bucket"),
                        )
                        if (selectable && section.byMonth) {
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

    if (askLargest) {
        LargestSheet(
            visible = visible,
            onPick = { n ->
                askLargest = false
                // Only on this explicit tap, and only from what is on screen (SAFETY rule 7).
                setSelected(GroupTools.largest(visible, n))
            },
            onDismiss = { askLargest = false },
        )
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

/**
 * PR H5: "Sort and filter", folded by default: search (the Trash's [GridSearch]), newest/oldest/largest, a year,
 * "how many are shown" with a way back to all, and "select the largest" ([onLargest], null when not offered).
 */
@Composable
private fun GroupToolsPanel(
    group: GroupSummary,
    view: GroupView,
    open: Boolean,
    onToggle: () -> Unit,
    onSort: (GroupSort) -> Unit,
    onYear: (Int?) -> Unit,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    shown: Int,
    zone: ZoneId,
    onLargest: (() -> Unit)?,
) {
    val label = stringResource(R.string.group_tools_open)
    KcButton(
        label,
        onClick = onToggle,
        style = KcButtonStyle.Text,
        icon = KcIcons.Search,
        modifier = Modifier.testTag("groupTools"),
    )
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("groupToolsPanel")) {
        KcSearchField(
            view.query,
            onQuery,
            placeholder = stringResource(R.string.grid_search_hint),
            clearLabel = stringResource(R.string.grid_search_clear),
            modifier = Modifier.testTag("groupSearch"),
        )
        // Three orders in a wrapping row (two and one at a phone's width); a column of three looked like a list.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag("groupSort"),
        ) {
            listOf(
                GroupSort.NEWEST to R.string.group_sort_newest,
                GroupSort.OLDEST to R.string.group_sort_oldest,
                GroupSort.LARGEST to R.string.group_sort_largest,
            ).forEach { (sort, text) ->
                FilterChip(
                    selected = view.sort == sort,
                    onClick = { onSort(sort) },
                    label = { Text(stringResource(text)) },
                    modifier = Modifier.testTag("sort_${sort.name}"),
                )
            }
        }
        val years = remember(group.items) { GroupTools.years(group.items, zone) }
        if (years.size > 1) {
            BalancedChipRow(Modifier.testTag("groupYears")) {
                FilterChip(
                    selected = view.year == null,
                    onClick = { onYear(null) },
                    label = { Text(stringResource(R.string.group_year_all)) },
                    modifier = Modifier.testTag("year_all"),
                )
                years.forEach { y ->
                    FilterChip(
                        selected = view.year == y,
                        onClick = { onYear(if (view.year == y) null else y) },
                        // A year is a number: isolated, never mirrored into the sentence around it.
                        label = { Text(Bidi.isolate(y.toString())) },
                        modifier = Modifier.testTag("year_$y"),
                    )
                }
            }
        }
        if (view.filtered) {
            Text(
                stringResource(R.string.group_shown, countText(shown), countText(group.items.size)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("groupShown"),
            )
            KcButton(
                stringResource(R.string.group_clear_filter),
                onClick = onClear,
                style = KcButtonStyle.Text,
                modifier = Modifier.testTag("groupClearFilter"),
            )
        }
        onLargest?.let { pick ->
            KcButton(
                stringResource(R.string.group_largest_button),
                onClick = pick,
                style = KcButtonStyle.Text,
                modifier = Modifier.testTag("groupLargest"),
            )
        }
    }
}

/** PR H5: the selection count bar: "Selected: 12 files · 3.1GB", the part a filter hides, and "Unselect". */
@Composable
private fun SelectionBar(total: Total, hidden: Int, onClear: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("selectionBar"),
    ) {
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            },
        ) {
            Text(
                selectionText(total, R.string.clean_selected),
                style = KeepClearTheme.type.number,
                modifier = Modifier.testTag("selectionCount"),
            )
            if (hidden > 0) {
                Text(
                    countSentence(R.plurals.group_selected_hidden, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("selectionHidden"),
                )
            }
        }
        KcButton(
            stringResource(R.string.review_unselect_all),
            onClick = onClear,
            style = KcButtonStyle.Text,
            modifier = Modifier.testTag("selectionClear"),
        )
    }
}

/** PR H5: "How many of the largest?": 10, 25, 50 or 100 of what is shown, each with its size. */
@Composable
private fun LargestSheet(visible: List<ClassifiedItem>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    InfoSheet(
        title = stringResource(R.string.group_largest_title),
        dismissText = stringResource(R.string.move_cancel),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("largestSheet"),
    ) {
        Text(stringResource(R.string.group_largest_body), style = MaterialTheme.typography.bodyMedium)
        val locale = LocalConfiguration.current.locales[0]
        for (n in GroupTools.choices(visible.size)) {
            val bytes = GroupTools.total(visible, GroupTools.largest(visible, n)).bytes
            KcButton(
                stringResource(
                    R.string.group_largest_option,
                    Bidi.isolate(NumberFormat.getIntegerInstance(locale).format(n)),
                    Bidi.isolate(ByteSize.format(bytes, locale)),
                ),
                onClick = { onPick(n) },
                style = KcButtonStyle.Tonal,
                modifier = Modifier.fillMaxWidth().testTag("largest_$n"),
            )
        }
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

/** REV-3: the user sees and can trim exactly what will move to the trash (also the gallery's choices, G4b). */
@Composable
internal fun EditableConfirm(
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
            modifier = Modifier.fillMaxSize().testTag("thumbPicture"),
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
    /** PR H5: „חזרה” on the notice after „לא להציע את זה שוב” (cancels that rule only). */
    val onUndoKeep: (String) -> Unit = {},
    /** PR H5: the notice timed out. */
    val onKeepNoticeDone: () -> Unit = {},
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
