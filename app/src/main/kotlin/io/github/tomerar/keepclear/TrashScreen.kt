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
}

@Composable
fun TrashScreen(
    trash: TrashUi,
    onRestore: (List<TrashEntry>, Confirmation) -> Unit,
    onPurge: (List<TrashEntry>, Confirmation) -> Unit,
    onBack: () -> Unit,
    onRestoreChanged: (TrashEntry, Confirmation) -> Unit = { _, _ -> },
) {
    val locale = LocalConfiguration.current.locales[0]
    var pending by remember { mutableStateOf<Pending?>(null) }

    fun date(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    @Composable
    fun count(n: Int) = pluralStringResource(R.plurals.file_count, n, NumberFormat.getIntegerInstance(locale).format(n))

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
                if (trash.entries.isEmpty()) {
                    Text(stringResource(R.string.trash_empty_state), modifier = Modifier.testTag("trashEmpty"))
                } else {
                    val total =
                        stringResource(
                            R.string.total_value,
                            ByteSize.format(trash.bytes, locale),
                            Bidi.isolate(count(trash.entries.size)),
                        )
                    Text(stringResource(R.string.trash_summary, total), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            onRestore(trash.entries, trashConfirmed(trash.entries))
                        }, modifier = Modifier.testTag("restoreAll")) {
                            Text(stringResource(R.string.trash_restore_all))
                        }
                        OutlinedButton(onClick = {
                            pending = Pending.Empty(trash.entries)
                        }, modifier = Modifier.testTag("emptyNow")) {
                            Text(stringResource(R.string.trash_empty_now))
                        }
                    }
                }
            }
            items(trash.entries, key = { it.id }) { entry ->
                Card(modifier = Modifier.fillMaxWidth().testTag("entry_${entry.id}")) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            Bidi.isolate(entry.originalPath.substringAfterLast('/')),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(Bidi.isolateLtr(entry.originalPath), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${ByteSize.format(
                                entry.size,
                                locale,
                            )} · ${stringResource(R.string.trash_frees_on, date(entry.expiresAt))}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        trash.restoreTo[entry.id]?.let {
                            Text(
                                stringResource(R.string.trash_restore_to, Bidi.isolateLtr(it)),
                                modifier = Modifier.testTag("restoreTo_${entry.id}"),
                            )
                        }
                        entry.verifiedCopy?.let { Text(stringResource(R.string.trash_verified_copy, Bidi.isolate(it))) }
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
                                    pending = Pending.RestoreChanged(entry)
                                }, modifier = Modifier.testTag("restoreChanged_${entry.id}")) {
                                    Text(stringResource(R.string.trash_restore_changed))
                                }
                            } else {
                                TextButton(onClick = {
                                    onRestore(listOf(entry), trashConfirmed(listOf(entry)))
                                }, modifier = Modifier.testTag("restore_${entry.id}")) {
                                    Text(stringResource(R.string.trash_restore))
                                }
                            }
                            if (entry.verifiedCopy != null && entry.id in trash.releasable) {
                                Button(onClick = {
                                    pending = Pending.Release(entry)
                                }, modifier = Modifier.testTag("release_${entry.id}")) {
                                    Text(stringResource(R.string.trash_release_now))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    when (val p = pending) {
        is Pending.Empty -> {
            val bytes = ByteSize.format(p.entries.sumOf { it.size }, locale)
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text(stringResource(R.string.trash_confirm_empty_title, bytes)) },
                text = { Text(stringResource(R.string.trash_confirm_empty_body, count(p.entries.size))) },
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
                        stringResource(R.string.trash_confirm_delete),
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
