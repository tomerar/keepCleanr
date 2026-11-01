// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.safety.Confirmation
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the Trash screen shows (TRASH-1…5). [restoreTo] holds non-original destinations (SEC-1). */
data class TrashUi(
    val entries: List<TrashEntry> = emptyList(),
    val restoreTo: Map<String, String> = emptyMap(),
    /** Entry ids whose backup copy passed the second, uncached check (H1); only these offer "Release now". */
    val releasable: Set<String> = emptySet(),
    /** Entry ids whose file changed in the trash since it was deleted (owner decision 1.2). */
    val changed: Set<String> = emptySet(),
) {
    val bytes: Long get() = entries.sumOf { it.size }
}

private sealed interface Pending {
    data class Empty(val entries: List<TrashEntry>) : Pending

    data class Release(val entry: TrashEntry) : Pending

    data class RestoreChanged(val entry: TrashEntry) : Pending

    data class RestoreAll(val entries: List<TrashEntry>) : Pending

    data class ReleaseAll(val entries: List<TrashEntry>) : Pending
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
) {
    val date = Instant.ofEpochMilli(entry.expiresAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    Column(
        modifier = Modifier.fillMaxWidth().testTag("entry_${entry.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(Bidi.fileName(entry.originalPath.substringAfterLast('/')), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(
                R.string.total_value,
                Bidi.isolate(ByteSize.format(entry.size, locale)),
                stringResource(R.string.trash_frees_on, Bidi.isolate(date)),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
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
                TextButton(onClick = {
                    onPending(Pending.RestoreChanged(entry))
                }, enabled = !busy, modifier = Modifier.testTag("restoreChanged_${entry.id}")) {
                    Text(stringResource(R.string.trash_restore_changed))
                }
            } else {
                TextButton(onClick = {
                    onRestore(listOf(entry), trashConfirmed(listOf(entry)))
                }, enabled = !busy, modifier = Modifier.testTag("restore_${entry.id}")) {
                    Text(stringResource(R.string.trash_restore))
                }
            }
            if (entry.verifiedCopy != null && entry.id in trash.releasable) {
                // Permanent: never a green primary button (review PR C).
                OutlinedButton(
                    onClick = { onPending(Pending.Release(entry)) },
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("release_${entry.id}"),
                ) { Text(stringResource(R.string.trash_release_now)) }
            }
        }
    }
}

@Composable
fun TrashScreen(
    trash: TrashUi,
    onRestore: (List<TrashEntry>, Confirmation) -> Unit,
    onPurge: (List<TrashEntry>, Confirmation) -> Unit,
    onBack: () -> Unit,
    onRestoreChanged: (TrashEntry, Confirmation) -> Unit = { _, _ -> },
    measured: Measured? = null,
    busy: Boolean = false,
) {
    val locale = LocalConfiguration.current.locales[0]
    var pending by remember { mutableStateOf<Pending?>(null) }

    fun date(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    @Composable
    fun count(n: Int) = pluralStringResource(R.plurals.file_count, n, NumberFormat.getIntegerInstance(locale).format(n))

    val groups = trash.entries.groupBy { trashGroupOf(it) }.toSortedMap()
    // Groups the user opened or closed (small groups start open, large ones closed).
    var toggled by rememberSaveable { mutableStateOf(setOf<TrashGroup>()) }
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("trash"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                        Text(stringResource(R.string.back))
                    }
                    Text(stringResource(R.string.trash_title), style = MaterialTheme.typography.titleLarge)
                }
                measured?.let { MeasuredBanner(it) }
                if (trash.entries.isEmpty()) {
                    Text(stringResource(R.string.trash_empty_state), modifier = Modifier.testTag("trashEmpty"))
                } else {
                    val total = stringResource(
                        R.string.total_value,
                        Bidi.isolate(ByteSize.format(trash.bytes, locale)),
                        Bidi.isolate(count(trash.entries.size)),
                    )
                    Text(stringResource(R.string.trash_summary, total), style = MaterialTheme.typography.bodyLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pending = Pending.RestoreAll(trash.entries) },
                            enabled = !busy,
                            modifier = Modifier.testTag("restoreAll"),
                        ) { Text(stringResource(R.string.trash_restore_all)) }
                        val releasable = trash.entries.filter { it.verifiedCopy != null && it.id in trash.releasable }
                        if (releasable.isNotEmpty()) {
                            // Review PR C: free everything that has a checked copy, as one action.
                            OutlinedButton(
                                onClick = { pending = Pending.ReleaseAll(releasable) },
                                enabled = !busy,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier.testTag("releaseAll"),
                            ) {
                                Text(
                                    stringResource(
                                        R.string.trash_release_all,
                                        Bidi.isolate(ByteSize.format(releasable.sumOf { it.size }, locale)),
                                    ),
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { pending = Pending.Empty(trash.entries) },
                            enabled = !busy,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.testTag("emptyNow"),
                        ) { Text(stringResource(R.string.trash_empty_now)) }
                    }
                }
            }
            // Review PR C: rows grouped by the action that sent them here, not one card per file. Each row stays
            // its own lazy item, so opening a group of thousands composes only what is on screen.
            groups.forEach { (group, entries) ->
                val open = (entries.size <= GROUP_OPEN_MAX) != (group in toggled)
                item(key = "group_$group") {
                    Card(modifier = Modifier.fillMaxWidth().testTag("trashGroup_$group")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(group.title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    R.string.total_value,
                                    Bidi.isolate(ByteSize.format(entries.sumOf { it.size }, locale)),
                                    Bidi.isolate(count(entries.size)),
                                ),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val restorable = entries.filter { it.id !in trash.changed }
                                if (restorable.isNotEmpty()) {
                                    TextButton(
                                        onClick = { onRestore(restorable, trashConfirmed(restorable)) },
                                        enabled = !busy,
                                        modifier = Modifier.testTag("restoreGroup_$group"),
                                    ) { Text(stringResource(R.string.trash_restore_group)) }
                                }
                                TextButton(
                                    onClick = { toggled = if (group in toggled) toggled - group else toggled + group },
                                    modifier = Modifier.testTag("toggleGroup_$group"),
                                ) {
                                    Text(
                                        stringResource(
                                            if (open) R.string.trash_hide_files else R.string.trash_show_files,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                if (open) {
                    items(entries, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                TrashRow(entry, trash, locale, busy, onRestore, onPending = { pending = it })
                            }
                        }
                    }
                }
            }
        }
    }

    when (val p = pending) {
        is Pending.Empty -> {
            val bytes = Bidi.isolate(ByteSize.format(p.entries.sumOf { it.size }, locale))
            val withCopy = p.entries.count { it.verifiedCopy != null && it.id in trash.releasable }
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text(stringResource(R.string.trash_confirm_empty_title, bytes)) },
                text = {
                    // Review PR C: what has a checked copy elsewhere vs. what will be gone for good.
                    Text(
                        if (withCopy > 0) {
                            stringResource(
                                R.string.trash_confirm_empty_body_split,
                                Bidi.isolate(count(withCopy)),
                                Bidi.isolate(count(p.entries.size - withCopy)),
                            )
                        } else {
                            stringResource(R.string.trash_confirm_empty_body, Bidi.isolate(count(p.entries.size)))
                        },
                        modifier = Modifier.testTag("emptyBody"),
                    )
                },
                confirmButton = {
                    SafeConfirmButton(
                        stringResource(R.string.trash_confirm_delete),
                        onClick = {
                            pending = null
                            onPurge(p.entries, trashConfirmed(p.entries))
                        },
                        modifier = Modifier.testTag("confirmPurge"),
                    )
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) { Text(stringResource(R.string.trash_cancel)) }
                },
            )
        }

        is Pending.Release -> {
            AlertDialog(
                onDismissRequest = { pending = null },
                title = {
                    Text(stringResource(R.string.trash_confirm_release_title, ByteSize.format(p.entry.size, locale)))
                },
                text = {
                    Text(
                        stringResource(
                            R.string.trash_confirm_release_body,
                            Bidi.isolate(p.entry.verifiedCopy.orEmpty()),
                        ),
                    )
                },
                confirmButton = {
                    SafeConfirmButton(
                        stringResource(R.string.trash_confirm_release_yes),
                        onClick = {
                            pending = null
                            onPurge(listOf(p.entry), trashConfirmed(listOf(p.entry)))
                        },
                        modifier = Modifier.testTag("confirmPurge"),
                    )
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) { Text(stringResource(R.string.trash_cancel)) }
                },
            )
        }

        is Pending.RestoreAll -> {
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text(stringResource(R.string.trash_restore_all_title, Bidi.isolate(count(p.entries.size)))) },
                text = { Text(stringResource(R.string.trash_restore_all_body)) },
                confirmButton = {
                    Button(onClick = {
                        pending = null
                        onRestore(p.entries, trashConfirmed(p.entries))
                    }, modifier = Modifier.testTag("confirmRestoreAll")) {
                        Text(stringResource(R.string.trash_restore_all))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) { Text(stringResource(R.string.trash_cancel)) }
                },
            )
        }

        is Pending.ReleaseAll -> {
            val bytes = Bidi.isolate(ByteSize.format(p.entries.sumOf { it.size }, locale))
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text(stringResource(R.string.trash_confirm_release_title, bytes)) },
                text = {
                    Text(
                        stringResource(
                            R.string.trash_confirm_empty_body_split,
                            Bidi.isolate(count(p.entries.size)),
                            Bidi.isolate(count(0)),
                        ),
                    )
                },
                confirmButton = {
                    SafeConfirmButton(
                        stringResource(R.string.trash_confirm_release_yes),
                        onClick = {
                            pending = null
                            onPurge(p.entries, trashConfirmed(p.entries))
                        },
                        modifier = Modifier.testTag("confirmReleaseAll"),
                    )
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) { Text(stringResource(R.string.trash_cancel)) }
                },
            )
        }

        is Pending.RestoreChanged -> {
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text(stringResource(R.string.trash_restore_changed_title)) },
                text = { Text(stringResource(R.string.trash_restore_changed_body)) },
                confirmButton = {
                    SafeConfirmButton(
                        stringResource(R.string.trash_restore_changed),
                        onClick = {
                            pending = null
                            onRestoreChanged(p.entry, trashConfirmed(listOf(p.entry)))
                        },
                        modifier = Modifier.testTag("confirmRestoreChanged"),
                    )
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) { Text(stringResource(R.string.trash_cancel)) }
                },
            )
        }

        null -> {
            // No confirmation open.
        }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.last_copy_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pluralStringResource(R.plurals.last_copy_body, ops.size, n))
                ops.take(5).forEach { Text(Bidi.isolate(it.displayPath.substringAfterLast('/'))) }
            }
        },
        confirmButton = {
            SafeConfirmButton(
                stringResource(R.string.last_copy_confirm),
                onClick = { onConfirm(userConfirmed(ops.map { it.displayPath })) },
                modifier = Modifier.testTag("confirmLastCopy"),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("keepLastCopy")) {
                Text(stringResource(R.string.last_copy_keep))
            }
        },
        modifier = Modifier.testTag("lastCopyDialog"),
    )
}

/** The Trash shows items by their original path, so that is what the user confirms (review PR B). */
fun trashConfirmed(entries: List<TrashEntry>): Confirmation = userConfirmed(entries.map { it.originalPath })
