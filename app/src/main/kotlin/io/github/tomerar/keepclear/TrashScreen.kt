// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmSheet
import io.github.tomerar.keepclear.core.designsystem.component.DaysLeftChip
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.designsystem.component.KcSearchField
import io.github.tomerar.keepclear.core.designsystem.component.MEDIA_TILE_MIN
import io.github.tomerar.keepclear.core.designsystem.component.MediaTile
import io.github.tomerar.keepclear.core.designsystem.component.MonthHeader
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.safety.Confirmation
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import java.text.NumberFormat
import androidx.compose.foundation.lazy.grid.items as gridItems

/** What the Trash screen shows (TRASH-1…5). [restoreTo] holds non-original destinations (SEC-1). */
data class TrashUi(
    val entries: List<TrashEntry> = emptyList(),
    val restoreTo: Map<String, String> = emptyMap(),
    /** Entry ids whose backup copy passed the second, uncached check (H1); only these offer "Release now". */
    val releasable: Set<String> = emptySet(),
    /** Entry ids whose file changed in the trash since it was deleted (owner decision 1.2). */
    val changed: Set<String> = emptySet(),
    /** M12: photos and videos in Android's own trash (any app's "Recently deleted"). */
    val android: List<io.github.tomerar.keepclear.engine.scan.ScanItem> = emptyList(),
    /** D6: items the last "Release" kept, because their backup copy couldn't be found intact right then. */
    val releaseHeldBack: Int = 0,
) {
    val bytes: Long get() = entries.sumOf { it.size }
}

private sealed interface Pending {
    data class Empty(val entries: List<TrashEntry>) : Pending

    data class Release(val entry: TrashEntry) : Pending

    data class RestoreChanged(val entry: TrashEntry) : Pending

    data class RestoreAll(val entries: List<TrashEntry>) : Pending

    data class ReleaseAll(val entries: List<TrashEntry>) : Pending

    data class AndroidEmpty(val items: List<io.github.tomerar.keepclear.engine.scan.ScanItem>) : Pending
}

private const val GROUP_OPEN_MAX = 5

/** Review PR C: what sent an item to the trash. Sorted in this order. */
enum class TrashGroup(val title: Int) {
    CLEAN(R.string.trash_group_clean),
    REVIEW(R.string.trash_group_review),
    MOVE(R.string.trash_group_move),
    OTHER(R.string.trash_group_other),
}

fun trashGroupOf(entry: TrashEntry): TrashGroup = when {
    entry.reason == io.github.tomerar.keepclear.core.safety.OpKind.MOVE_DELETE_ORIGINAL.name -> TrashGroup.MOVE

    else -> when (
        io.github.tomerar.keepclear.engine.classify.Rules.all.firstOrNull {
            it.id == entry.ruleId
        }?.category
    ) {
        io.github.tomerar.keepclear.core.model.Category.SAFE -> TrashGroup.CLEAN
        io.github.tomerar.keepclear.core.model.Category.REVIEW -> TrashGroup.REVIEW
        io.github.tomerar.keepclear.core.model.Category.MOVE -> TrashGroup.REVIEW
        else -> TrashGroup.OTHER
    }
}

/** One trashed file inside its group. */
@Composable
private fun TrashRow(
    entry: TrashEntry,
    trash: TrashUi,
    locale: java.util.Locale,
    busy: Boolean,
    onRestore: (List<TrashEntry>, Confirmation) -> Unit,
    onPending: (Pending) -> Unit,
    now: Long,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("entry_${entry.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val stored = entry.originalPath.substringAfterLast('/')
        // Design QA item 3: a file that was in the gallery's bin (`.trashed-<time>-<name>`) shows its own name.
        val fromGalleryBin = io.github.tomerar.keepclear.core.safety.PermanentKinds.trashedOriginalName(stored)
        Text(Bidi.fileName(fromGalleryBin ?: stored), style = MaterialTheme.typography.titleSmall)
        if (fromGalleryBin != null) {
            Text(
                stringResource(R.string.trash_from_gallery_bin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fromGalleryBin_${entry.id}"),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                Bidi.isolate(ByteSize.format(entry.size, locale)),
                style = KeepClearTheme.type.number,
                modifier = Modifier.weight(1f, fill = false),
            )
            DaysLeft(entry.expiresAt, now, Modifier.testTag("daysLeft_${entry.id}"))
        }
        trash.restoreTo[entry.id]?.let {
            Text(
                stringResource(R.string.trash_restore_to, Bidi.isolateLtr(it)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("restoreTo_${entry.id}"),
            )
        }
        entry.verifiedCopy?.let {
            Text(
                stringResource(R.string.trash_verified_copy, Bidi.isolate(it)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val changed = entry.id in trash.changed
        if (changed) {
            Text(
                stringResource(R.string.trash_changed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("changed_${entry.id}"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (changed) {
                // Never put a changed file back in place of anything: separate folder, after a confirm.
                KcButton(
                    stringResource(R.string.trash_restore_changed),
                    onClick = { onPending(Pending.RestoreChanged(entry)) },
                    enabled = !busy,
                    style = KcButtonStyle.Text,
                    modifier = Modifier.testTag("restoreChanged_${entry.id}"),
                )
            } else {
                KcButton(
                    stringResource(R.string.trash_restore),
                    onClick = { onRestore(listOf(entry), trashConfirmed(listOf(entry))) },
                    enabled = !busy,
                    style = KcButtonStyle.Text,
                    modifier = Modifier.testTag("restore_${entry.id}"),
                )
            }
            if (entry.verifiedCopy != null && entry.id in trash.releasable) {
                // Permanent: the red second confirmation says so (design QA item 3: no filled red in the list).
                KcButton(
                    stringResource(R.string.trash_release_now),
                    onClick = { onPending(Pending.Release(entry)) },
                    enabled = !busy,
                    style = KcButtonStyle.Tonal,
                    modifier = Modifier.testTag("release_${entry.id}"),
                )
            }
        }
    }
}

/** H1c: the Trash as a grid of pictures by month (default) or as the grouped list. */
enum class TrashView { GRID, LIST }

@Composable
fun TrashScreen(
    trash: TrashUi,
    onRestore: (List<TrashEntry>, Confirmation) -> Unit,
    onPurge: (List<TrashEntry>, Confirmation) -> Unit,
    onBack: () -> Unit,
    onRestoreChanged: (TrashEntry, Confirmation) -> Unit = { _, _ -> },
    measured: Measured? = null,
    busy: Boolean = false,
    onAndroidTrash: (
        List<io.github.tomerar.keepclear.engine.scan.ScanItem>,
        Boolean,
        Confirmation,
    ) -> Unit = { _, _, _ -> },
    /** D6: "Release now/all"; the backup copies are re-checked first. Defaults to a plain purge. */
    onRelease: (List<TrashEntry>, Confirmation) -> Unit = onPurge,
    /** F2 / TRASH-7: the uninstall note shows on the first visit only (Settings always has it). */
    showUninstallNote: Boolean = true,
    onUninstallNoteShown: () -> Unit = {},
    now: Long = LocalClock.current.let { clock -> remember { clock() } },
    /** H1c: how the trash opens; the user can switch (TRASH-8). */
    initialView: TrashView = TrashView.GRID,
    /** H1c: the empty trash's one next step, "Restore from a backup"; null hides it. */
    onRestoreFromBackup: (() -> Unit)? = null,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    val locale = LocalConfiguration.current.locales[0]
    var pending by remember { mutableStateOf<Pending?>(null) }
    var view by rememberSaveable { mutableStateOf(initialView) }
    var query by rememberSaveable { mutableStateOf("") }
    var details by remember { mutableStateOf<TrashEntry?>(null) }

    @Composable
    fun count(n: Int) = pluralStringResource(R.plurals.file_count, n, NumberFormat.getIntegerInstance(locale).format(n))

    val groups = trash.entries.groupBy { trashGroupOf(it) }.toSortedMap()
    // Groups the user opened or closed (small groups start open, large ones closed).
    var toggled by rememberSaveable { mutableStateOf(setOf<TrashGroup>()) }
    val empty = trash.entries.isEmpty() && trash.android.isEmpty()

    @Composable
    fun Header() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // F3: a running restore, purge or release shows progress here too, like Home and Backup.
            if (busy) BusyLine()
            measured?.let { MeasuredBanner(it) }
            if (trash.releaseHeldBack > 0) {
                Text(
                    countSentence(R.plurals.trash_release_held_back, trash.releaseHeldBack),
                    modifier = Modifier.testTag("releaseHeldBack"),
                )
            }
            if (empty) {
                // H1c: an empty state with one next step.
                Text(stringResource(R.string.trash_empty_state), modifier = Modifier.testTag("trashEmpty"))
                onRestoreFromBackup?.let {
                    KcButton(
                        stringResource(R.string.restore_start),
                        onClick = it,
                        style = KcButtonStyle.Tonal,
                        modifier = Modifier.fillMaxWidth().testTag("trashEmptyRestore"),
                    )
                }
            } else {
                val total = stringResource(
                    R.string.total_value,
                    Bidi.isolate(ByteSize.format(trash.bytes, locale)),
                    Bidi.isolate(count(trash.entries.size)),
                )
                Text(stringResource(R.string.trash_summary, total), style = MaterialTheme.typography.bodyLarge)
                // TRASH-7 (F2): an uninstall can't be intercepted, so the note shows on the first visit; Settings
                // keeps it for later. No hidden folder name on the default path.
                if (showUninstallNote) {
                    LaunchedEffect(Unit) { onUninstallNoteShown() }
                    Text(
                        stringResource(
                            R.string.trash_uninstall_note,
                            Bidi.isolate(ByteSize.format(trash.bytes, locale)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("uninstallNote"),
                    )
                }
            }
            if (trash.entries.isNotEmpty()) {
                // H1c: the grouped list stays one tap away.
                KcButton(
                    stringResource(if (view == TrashView.GRID) R.string.grid_show_list else R.string.grid_show_grid),
                    onClick = { view = if (view == TrashView.GRID) TrashView.LIST else TrashView.GRID },
                    style = KcButtonStyle.Text,
                    icon = if (view == TrashView.GRID) KcIcons.List else KcIcons.Grid,
                    modifier = Modifier.testTag("trashViewToggle"),
                )
                if (view == TrashView.GRID) {
                    KcSearchField(
                        query,
                        { query = it },
                        placeholder = stringResource(R.string.grid_search_hint),
                        clearLabel = stringResource(R.string.grid_search_clear),
                        modifier = Modifier.testTag("trashSearch"),
                    )
                }
            }
        }
    }

    @Composable
    fun AndroidTrashCard() {
        KcCard(modifier = Modifier.testTag("androidTrash")) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.trash_group_android),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        R.string.total_value,
                        Bidi.isolate(ByteSize.format(trash.android.sumOf { it.size }, locale)),
                        Bidi.isolate(count(trash.android.size)),
                    ),
                )
                Text(
                    stringResource(R.string.trash_android_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KcButton(
                        stringResource(R.string.trash_restore_group),
                        onClick = { onAndroidTrash(trash.android, true, userConfirmed(trash.android.map { it.path })) },
                        enabled = !busy,
                        style = KcButtonStyle.Tonal,
                        modifier = Modifier.testTag("androidRestore"),
                    )
                    KcButton(
                        stringResource(R.string.trash_android_empty),
                        onClick = { pending = Pending.AndroidEmpty(trash.android) },
                        enabled = !busy,
                        style = KcButtonStyle.OutlinedDestructive,
                        modifier = Modifier.testTag("androidEmpty"),
                    )
                }
            }
        }
    }

    @Composable
    fun TrashActions() {
        // Design QA item 3: the list leads; the actions on the whole trash come after it, none of them a filled
        // red button. Permanent ones say so in their (red) confirmation.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KcButton(
                stringResource(R.string.trash_restore_all),
                onClick = { pending = Pending.RestoreAll(trash.entries) },
                enabled = !busy,
                style = KcButtonStyle.Tonal,
                modifier = Modifier.fillMaxWidth().testTag("restoreAll"),
            )
            val releasable = trash.entries.filter { it.verifiedCopy != null && it.id in trash.releasable }
            if (releasable.isNotEmpty()) {
                // Review PR C: free everything that has a checked copy, as one action; permanent, so its
                // confirmation is red and says so.
                KcButton(
                    stringResource(R.string.trash_release_all),
                    onClick = { pending = Pending.ReleaseAll(releasable) },
                    enabled = !busy,
                    style = KcButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth().testTag("releaseAll"),
                )
            }
            KcButton(
                stringResource(R.string.trash_empty_now),
                onClick = { pending = Pending.Empty(trash.entries) },
                enabled = !busy,
                style = KcButtonStyle.OutlinedDestructive,
                modifier = Modifier.fillMaxWidth().testTag("emptyNow"),
            )
        }
    }

    Scaffold(topBar = { SubScreenTopBar(stringResource(R.string.trash_title), onBack) }) { padding ->
        if (view == TrashView.GRID || empty) {
            // H1c (owner brief H1 item 9): pictures by month, newest first; search by name or date.
            val shown = trash.entries.filter { GridSearch.matches(trashName(it), it.mtimeMillis, query, zone) }
            val sections = MediaGrid.monthSections(shown, zone, tie = { it.id }) { it.mtimeMillis }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(MEDIA_TILE_MIN),
                modifier = Modifier.padding(padding).fillMaxSize().testTag("trash"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Header() }
                if (query.isNotBlank() && shown.isEmpty()) {
                    item(key = "noMatch", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.grid_no_match),
                            modifier = Modifier.padding(top = 8.dp).testTag("trashNoMatch"),
                        )
                    }
                }
                sections.forEach { section ->
                    item(key = "month_${section.month}", span = { GridItemSpan(maxLineSpan) }) {
                        MonthHeader(
                            MediaGrid.monthTitle(section.month, locale),
                            Modifier.testTag("monthHeader_${section.month}"),
                        )
                    }
                    gridItems(section.items, key = { it.id }) { entry ->
                        TrashTile(
                            entry,
                            trash,
                            zone,
                            onOpen = { details = entry },
                            onRestore = {
                                if (entry.id in trash.changed) {
                                    pending = Pending.RestoreChanged(entry)
                                } else {
                                    onRestore(listOf(entry), trashConfirmed(listOf(entry)))
                                }
                            },
                            onRelease = { pending = Pending.Release(entry) },
                            busy = busy,
                        )
                    }
                }
                if (trash.android.isNotEmpty()) {
                    item(key = "android", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.padding(top = 12.dp)) { AndroidTrashCard() }
                    }
                }
                if (trash.entries.isNotEmpty()) {
                    item(key = "trashActions", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.padding(top = 12.dp)) { TrashActions() }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize().testTag("trash"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") { Header() }
                // Review PR C: rows grouped by the action that sent them here, not one card per file. Each row stays
                // its own lazy item, so opening a group of thousands composes only what is on screen.
                groups.forEach { (group, entries) ->
                    val open = (entries.size <= GROUP_OPEN_MAX) != (group in toggled)
                    item(key = "group_$group") {
                        KcCard(modifier = Modifier.testTag("trashGroup_$group")) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    stringResource(group.title),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    stringResource(
                                        R.string.total_value,
                                        Bidi.isolate(ByteSize.format(entries.sumOf { it.size }, locale)),
                                        Bidi.isolate(count(entries.size)),
                                    ),
                                    style = KeepClearTheme.type.number,
                                )
                                // F2: the group's soonest permanent deletion, so nothing expires unseen.
                                DaysLeft(entries.minOf { it.expiresAt }, now, Modifier.testTag("groupDaysLeft_$group"))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val restorable = entries.filter { it.id !in trash.changed }
                                    if (restorable.isNotEmpty()) {
                                        KcButton(
                                            stringResource(R.string.trash_restore_group),
                                            onClick = { onRestore(restorable, trashConfirmed(restorable)) },
                                            enabled = !busy,
                                            style = KcButtonStyle.Tonal,
                                            modifier = Modifier.testTag("restoreGroup_$group"),
                                        )
                                    }
                                    KcButton(
                                        stringResource(
                                            if (open) R.string.trash_hide_files else R.string.trash_show_files,
                                        ),
                                        onClick = {
                                            toggled = if (group in toggled) toggled - group else toggled + group
                                        },
                                        style = KcButtonStyle.Text,
                                        modifier = Modifier.testTag("toggleGroup_$group"),
                                    )
                                }
                            }
                        }
                    }
                    if (open) {
                        items(entries, key = { it.id }) { entry ->
                            KcCard {
                                TrashRow(entry, trash, locale, busy, onRestore, onPending = { pending = it }, now = now)
                            }
                        }
                    }
                }
                if (trash.android.isNotEmpty()) item(key = "android") { AndroidTrashCard() }
                if (trash.entries.isNotEmpty()) item(key = "trashActions") { TrashActions() }
            }
        }
    }

    details?.let { entry ->
        // The entry may have left the trash (restored) while its details were open.
        val current = trash.entries.firstOrNull { it.id == entry.id }
        if (current == null) {
            details = null
        } else {
            TrashDetailsSheet(
                current,
                trash,
                now,
                zone,
                busy,
                onDismiss = { details = null },
                onRestore = {
                    details = null
                    onRestore(listOf(current), trashConfirmed(listOf(current)))
                },
                onPending = {
                    details = null
                    pending = it
                },
            )
        }
    }

    val close = { pending = null }
    when (val p = pending) {
        is Pending.Empty -> {
            val withCopy = p.entries.count { it.verifiedCopy != null && it.id in trash.releasable }
            PermanentSheet(
                title = stringResource(
                    R.string.trash_confirm_empty_title,
                    Bidi.isolate(ByteSize.format(p.entries.sumOf { it.size }, locale)),
                ),
                total = Total(p.entries.size, p.entries.sumOf { it.size }),
                // Review PR C: what has a checked copy elsewhere vs. what will be gone for good.
                body = if (withCopy > 0) {
                    stringResource(
                        R.string.trash_confirm_empty_body_split,
                        Bidi.isolate(count(withCopy)),
                        Bidi.isolate(count(p.entries.size - withCopy)),
                    )
                } else {
                    stringResource(R.string.trash_confirm_empty_body, Bidi.isolate(count(p.entries.size)))
                },
                bodyTag = "emptyBody",
                confirm = stringResource(R.string.trash_confirm_delete),
                confirmTag = "confirmPurge",
                onDismiss = close,
            ) {
                pending = null
                onPurge(p.entries, trashConfirmed(p.entries))
            }
        }

        is Pending.Release -> {
            PermanentSheet(
                title = stringResource(
                    R.string.trash_confirm_release_title,
                    Bidi.isolate(ByteSize.format(p.entry.size, locale)),
                ),
                total = Total(1, p.entry.size),
                body = stringResource(
                    R.string.trash_confirm_release_body,
                    Bidi.isolate(p.entry.verifiedCopy.orEmpty()),
                ),
                bodyTag = "releaseBody",
                confirm = stringResource(R.string.trash_confirm_release_yes),
                confirmTag = "confirmPurge",
                onDismiss = close,
            ) {
                pending = null
                onRelease(listOf(p.entry), trashConfirmed(listOf(p.entry)))
            }
        }

        is Pending.AndroidEmpty -> {
            // Second confirmation (TRASH-4); Android then shows its own dialog too.
            PermanentSheet(
                title = stringResource(
                    R.string.trash_confirm_empty_title,
                    Bidi.isolate(ByteSize.format(p.items.sumOf { it.size }, locale)),
                ),
                total = Total(p.items.size, p.items.sumOf { it.size }),
                body = stringResource(R.string.trash_confirm_empty_body, Bidi.isolate(count(p.items.size))),
                bodyTag = "androidEmptyBody",
                confirm = stringResource(R.string.trash_confirm_delete),
                confirmTag = "confirmAndroidEmpty",
                onDismiss = close,
            ) {
                pending = null
                onAndroidTrash(p.items, false, userConfirmed(p.items.map { it.path }))
            }
        }

        is Pending.RestoreAll -> {
            ConfirmSheet(
                title = stringResource(R.string.trash_restore_all_title, Bidi.isolate(count(p.entries.size))),
                dismissText = stringResource(R.string.trash_cancel),
                onDismiss = close,
                facts = confirmFacts(
                    Total(p.entries.size, p.entries.sumOf { it.size }),
                    stringResource(R.string.confirm_where_back),
                ),
                note = stringResource(R.string.trash_restore_all_body),
                confirmButton = {
                    KcButton(
                        stringResource(R.string.trash_restore_all),
                        onClick = {
                            pending = null
                            onRestore(p.entries, trashConfirmed(p.entries))
                        },
                        modifier = Modifier.fillMaxWidth().testTag("confirmRestoreAll"),
                    )
                },
            )
        }

        is Pending.ReleaseAll -> {
            // F2 (verified bug): the second count is the trash's items WITHOUT a checked copy, which stay in the trash;
            // it used to be a hard-coded 0.
            val withoutCopy = trash.entries.size - p.entries.size
            PermanentSheet(
                title = stringResource(
                    R.string.trash_confirm_release_title,
                    Bidi.isolate(ByteSize.format(p.entries.sumOf { it.size }, locale)),
                ),
                total = Total(p.entries.size, p.entries.sumOf { it.size }),
                body = stringResource(
                    R.string.trash_confirm_release_all_body,
                    Bidi.isolate(count(p.entries.size)),
                    Bidi.isolate(count(withoutCopy)),
                ),
                bodyTag = "releaseAllBody",
                confirm = stringResource(R.string.trash_confirm_release_yes),
                confirmTag = "confirmReleaseAll",
                onDismiss = close,
            ) {
                pending = null
                onRelease(p.entries, trashConfirmed(p.entries))
            }
        }

        is Pending.RestoreChanged -> {
            // Not permanent: the changed file goes to a separate folder and nothing is overwritten (brief F2).
            ConfirmSheet(
                title = stringResource(R.string.trash_restore_changed_title),
                dismissText = stringResource(R.string.trash_cancel),
                onDismiss = close,
                note = stringResource(R.string.trash_restore_changed_body),
                confirmButton = {
                    SafeConfirmButton(
                        stringResource(R.string.trash_restore_changed),
                        onClick = {
                            pending = null
                            onRestoreChanged(p.entry, trashConfirmed(listOf(p.entry)))
                        },
                        modifier = Modifier.fillMaxWidth().testTag("confirmRestoreChanged"),
                        permanent = false,
                    )
                },
            )
        }

        null -> {
            // No confirmation open.
        }
    }
}

/** The name the user knows: a file from the gallery's bin (`.trashed-<time>-<name>`) shows its own name. */
internal fun trashName(entry: TrashEntry): String {
    val stored = entry.originalPath.substringAfterLast('/')
    return io.github.tomerar.keepclear.core.safety.PermanentKinds.trashedOriginalName(stored) ?: stored
}

/** H1c: a file without a picture shows its type. */
@Composable
internal fun kindIcon(kind: FileKind): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    FileKind.IMAGE -> KcIcons.Image
    FileKind.VIDEO -> KcIcons.Video
    FileKind.AUDIO -> KcIcons.Audio
    FileKind.DOCUMENT -> KcIcons.Document
    FileKind.OTHER -> KcIcons.File
}

/** H1c: what a trash tile or its details show as a picture: the trashed file itself (it is on the phone). */
private fun trashPicture(entry: TrashEntry): (suspend (ThumbnailLoader, Int) -> android.graphics.Bitmap?)? =
    if (FileKinds.hasPicture(trashName(entry))) {
        { loader, px -> loader.loadFile(entry.trashedPath, entry.mtimeMillis, px) }
    } else {
        null
    }

/**
 * H1c: one trashed file in the grid. A tap opens its details; TalkBack reads its name, date and size and offers
 * Restore, Preview and, only where the list offers it today (a checked backup copy), Delete for good.
 */
@Composable
private fun TrashTile(
    entry: TrashEntry,
    trash: TrashUi,
    zone: java.time.ZoneId,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onRelease: () -> Unit,
    busy: Boolean,
) {
    val locale = LocalConfiguration.current.locales[0]
    val name = trashName(entry)
    val date = Bidi.isolate(MediaGrid.dayText(entry.mtimeMillis, zone, locale))
    val size = Bidi.isolate(ByteSize.format(entry.size, locale))
    val changed = entry.id in trash.changed
    val restoreLabel = stringResource(if (changed) R.string.trash_restore_changed else R.string.trash_restore)
    val previewLabel = stringResource(R.string.grid_preview)
    val releaseLabel = stringResource(R.string.trash_release_now)
    val releasable = entry.verifiedCopy != null && entry.id in trash.releasable
    val actions = buildList {
        if (!busy) {
            add(
                CustomAccessibilityAction(restoreLabel) {
                    onRestore()
                    true
                },
            )
        }
        add(
            CustomAccessibilityAction(previewLabel) {
                onOpen()
                true
            },
        )
        if (releasable && !busy) {
            add(
                CustomAccessibilityAction(releaseLabel) {
                    onRelease()
                    true
                },
            )
        }
    }
    MediaTile(
        thumbnail = rememberTilePainter(entry.id, load = trashPicture(entry)),
        typeIcon = kindIcon(FileKinds.of(name)),
        date = date,
        size = size,
        description = stringResource(R.string.grid_tile_description, Bidi.fileName(name), date, size),
        onClick = onOpen,
        modifier = Modifier.testTag("tile_${entry.id}"),
        actions = actions,
        note = if (changed) stringResource(R.string.trash_changed_short) else null,
    )
}

/** H1c: one trashed file's details (the "preview"): its picture, date, size, days left and its own actions. */
@Composable
private fun TrashDetailsSheet(
    entry: TrashEntry,
    trash: TrashUi,
    now: Long,
    zone: java.time.ZoneId,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onPending: (Pending) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val name = trashName(entry)
    val changed = entry.id in trash.changed
    InfoSheet(
        title = Bidi.fileName(name),
        dismissText = stringResource(R.string.restore_ok),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("trashDetails"),
        primaryButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (changed) {
                    // Never put a changed file back in place of anything: separate folder, after a confirm.
                    KcButton(
                        stringResource(R.string.trash_restore_changed),
                        onClick = { onPending(Pending.RestoreChanged(entry)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("restoreChanged_${entry.id}"),
                    )
                } else {
                    KcButton(
                        stringResource(R.string.trash_restore),
                        onClick = onRestore,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("restore_${entry.id}"),
                    )
                }
                if (entry.verifiedCopy != null && entry.id in trash.releasable) {
                    KcButton(
                        stringResource(R.string.trash_release_now),
                        onClick = { onPending(Pending.Release(entry)) },
                        enabled = !busy,
                        style = KcButtonStyle.Tonal,
                        modifier = Modifier.fillMaxWidth().testTag("release_${entry.id}"),
                    )
                }
            }
        },
    ) {
        val picture = rememberTilePainter("details_${entry.id}", sizeDp = 320, load = trashPicture(entry))
        Box(
            Modifier.fillMaxWidth().heightIn(max = 240.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) {
                androidx.compose.foundation.Image(
                    picture,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().clip(KcShapes.thumbnail),
                )
            } else {
                androidx.compose.material3.Icon(
                    kindIcon(FileKinds.of(name)),
                    contentDescription = null,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (io.github.tomerar.keepclear.core.safety.PermanentKinds.trashedOriginalName(
                entry.originalPath.substringAfterLast('/'),
            ) != null
        ) {
            Text(
                stringResource(R.string.trash_from_gallery_bin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fromGalleryBin_${entry.id}"),
            )
        }
        Text(
            stringResource(
                R.string.grid_details_line,
                Bidi.isolate(MediaGrid.dayText(entry.mtimeMillis, zone, locale)),
                Bidi.isolate(ByteSize.format(entry.size, locale)),
            ),
            style = KeepClearTheme.type.number,
        )
        DaysLeft(entry.expiresAt, now, Modifier.testTag("daysLeft_${entry.id}"))
        trash.restoreTo[entry.id]?.let {
            Text(
                stringResource(R.string.trash_restore_to, Bidi.isolateLtr(it)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("restoreTo_${entry.id}"),
            )
        }
        entry.verifiedCopy?.let {
            Text(
                stringResource(R.string.trash_verified_copy, Bidi.isolate(it)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (changed) {
            Text(
                stringResource(R.string.trash_changed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("changed_${entry.id}"),
            )
        }
    }
}

/** F2: "N days left" until a trashed item is deleted for good (DaysLeftChip, never red). */
@Composable
private fun DaysLeft(expiresAt: Long, now: Long, modifier: Modifier = Modifier) {
    val days = TrashDays.left(expiresAt, now)
    val locale = LocalConfiguration.current.locales[0]
    DaysLeftChip(
        pluralStringResource(R.plurals.trash_days_left, days, NumberFormat.getIntegerInstance(locale).format(days)),
        days,
        modifier,
    )
}

/** Whole days until [expiresAt], rounded up (an item expiring in 2 hours still has "1 day"); never negative. */
object TrashDays {
    private const val DAY = 86_400_000L

    fun left(expiresAt: Long, now: Long): Int = ((expiresAt - now + DAY - 1) / DAY).coerceAtLeast(0).toInt()
}

/** A permanent action's ConfirmSheet (SAFETY-2 "second confirmation"): red, says it can't be undone. */
@Composable
private fun PermanentSheet(
    title: String,
    total: Total,
    body: String,
    bodyTag: String,
    confirm: String,
    confirmTag: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmSheet(
        title = title,
        dismissText = stringResource(R.string.trash_cancel),
        onDismiss = onDismiss,
        facts = confirmFacts(total, stringResource(R.string.confirm_where_gone)),
        note = stringResource(R.string.confirm_permanent_note),
        permanent = true,
        confirmButton = {
            SafeConfirmButton(
                confirm,
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().testTag(confirmTag),
                permanent = true,
            )
        },
    ) {
        Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(bodyTag))
    }
}

/** Owner decision 1.3: these items are the last copy of something already in the trash. */
@Composable
fun LastCopyDialog(
    ops: List<io.github.tomerar.keepclear.core.safety.PlannedOp>,
    onConfirm: (Confirmation) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val n = NumberFormat.getIntegerInstance(locale).format(ops.size)
    // They go to the 30-day trash like everything else: careful, but not permanent (ink).
    ConfirmSheet(
        title = stringResource(R.string.last_copy_title),
        dismissText = stringResource(R.string.last_copy_keep),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("lastCopyDialog"),
        dismissModifier = Modifier.testTag("keepLastCopy"),
        note = pluralStringResource(R.plurals.last_copy_body, ops.size, n),
        confirmButton = {
            SafeConfirmButton(
                stringResource(R.string.last_copy_confirm),
                onClick = { onConfirm(userConfirmed(ops.map { it.displayPath })) },
                modifier = Modifier.fillMaxWidth().testTag("confirmLastCopy"),
                permanent = false,
            )
        },
    ) {
        ops.take(5).forEach { Text(Bidi.fileName(it.displayPath.substringAfterLast('/'))) }
    }
}

/** The Trash shows items by their original path, so that is what the user confirms (review PR B). */
fun trashConfirmed(entries: List<TrashEntry>): Confirmation = userConfirmed(entries.map { it.originalPath })
