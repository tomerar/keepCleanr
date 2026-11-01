// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.backup.smb.SmbLocation
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.restore.RestoreReport

/** MOVE-17: one source folder and month of a backup, restored together. */
data class RestoreGroup(val group: String, val month: String, val count: Int, val bytes: Long) {
    val key: String get() = "$group|$month"
}

/** Where the "Restore from a backup" flow is. */
sealed interface RestoreStep {
    /** Choose the drive, folder or network drive that holds the backup. */
    data object Pick : RestoreStep

    /** This build can't put files back where they were without All files access. */
    data object NeedsAllFiles : RestoreStep

    /** Nothing KeepClear can restore was found there. */
    data object NoBackup : RestoreStep

    /** The drive holds backups of several phones. */
    data class Backups(val roots: List<String>) : RestoreStep

    data class Catalog(val rootRel: String, val groups: List<RestoreGroup>) : RestoreStep

    data class Running(val done: Int, val total: Int) : RestoreStep

    data class Done(val report: RestoreReport) : RestoreStep
}

class RestoreActions(
    val onStart: () -> Unit = {},
    val onPickFolder: () -> Unit = {},
    val onNetworkDrive: (SmbLocation) -> Unit = {},
    val savedNetworkDrive: () -> SmbLocation? = { null },
    val onChooseBackup: (String) -> Unit = {},
    val onRestore: (Set<String>) -> Unit = {},
    val onClose: () -> Unit = {},
)

@Composable
fun RestoreDialogs(step: RestoreStep?, actions: RestoreActions) {
    var network by rememberSaveable { mutableStateOf(false) }
    when (step) {
        // Closed: nothing to show.
        null -> {}

        RestoreStep.Pick -> {
            AlertDialog(
                onDismissRequest = actions.onClose,
                title = { Text(stringResource(R.string.restore_title)) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("restorePick"),
                    ) {
                        Text(stringResource(R.string.restore_pick_body))
                        TextButton(onClick = { network = true }, modifier = Modifier.testTag("restoreNetwork")) {
                            Text(stringResource(R.string.restore_from_network))
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = actions.onPickFolder, modifier = Modifier.testTag("restorePickFolder")) {
                        Text(stringResource(R.string.restore_pick_folder))
                    }
                },
                dismissButton = { CancelButton(actions.onClose) },
            )
        }

        RestoreStep.NeedsAllFiles -> {
            Info(stringResource(R.string.restore_needs_all_files), actions.onClose)
        }

        RestoreStep.NoBackup -> {
            Info(stringResource(R.string.restore_no_backup), actions.onClose)
        }

        is RestoreStep.Backups -> {
            AlertDialog(
                onDismissRequest = actions.onClose,
                title = { Text(stringResource(R.string.restore_which_phone)) },
                text = {
                    Column(modifier = Modifier.testTag("restoreBackups")) {
                        step.roots.forEach { root ->
                            TextButton(
                                onClick = { actions.onChooseBackup(root) },
                                modifier = Modifier.testTag("backup_$root"),
                            ) {
                                Text(Bidi.isolate(root.substringAfterLast('/')))
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { CancelButton(actions.onClose) },
            )
        }

        is RestoreStep.Catalog -> {
            CatalogDialog(step, actions)
        }

        is RestoreStep.Running -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.restore_running)) },
                text = {
                    Column(modifier = Modifier.testTag("restoreRunning")) {
                        LinearProgressIndicator(
                            progress = { if (step.total == 0) 0f else step.done.toFloat() / step.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(countSentence(R.plurals.restore_progress, step.done))
                    }
                },
                confirmButton = {},
            )
        }

        is RestoreStep.Done -> {
            val r = step.report
            val lines = buildList {
                add(countSentence(R.plurals.restore_done, r.restored.size))
                if (r.redirected > 0) add(countSentence(R.plurals.restore_redirected, r.redirected))
                if (r.alreadyThere > 0) add(countSentence(R.plurals.restore_already_there, r.alreadyThere))
                if (r.damaged > 0) add(countSentence(R.plurals.restore_damaged, r.damaged))
                if (r.failed > 0) add(countSentence(R.plurals.restore_failed, r.failed))
                if (r.interrupted) add(stringResource(R.string.restore_interrupted))
            }
            Info(lines.joinToString("\n"), actions.onClose, tag = "restoreDone")
        }
    }
    if (network) {
        NetworkDriveDialog(
            saved = actions.savedNetworkDrive(),
            onConnect = { location ->
                network = false
                actions.onNetworkDrive(location)
            },
            onDismiss = { network = false },
        )
    }
}

@Composable
private fun CancelButton(onClick: () -> Unit) =
    TextButton(onClick = onClick) { Text(stringResource(R.string.move_cancel)) }

@Composable
private fun Info(text: String, onClose: () -> Unit, tag: String = "restoreInfo") = AlertDialog(
    onDismissRequest = onClose,
    title = { Text(stringResource(R.string.restore_title)) },
    text = { Text(text, modifier = Modifier.testTag(tag)) },
    confirmButton = {
        TextButton(onClick = onClose, modifier = Modifier.testTag("restoreClose")) {
            Text(stringResource(R.string.restore_ok))
        }
    },
)

@Composable
private fun CatalogDialog(step: RestoreStep.Catalog, actions: RestoreActions) {
    // Nothing is pre-selected: the user picks what comes back.
    var chosen by rememberSaveable(step.rootRel) { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = actions.onClose,
        title = { Text(stringResource(R.string.restore_choose)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag("restoreCatalog").verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.restore_where), style = MaterialTheme.typography.bodySmall)
                step.groups.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = g.key in chosen,
                            onCheckedChange = { on -> chosen = if (on) chosen + g.key else chosen - g.key },
                            modifier = Modifier.testTag("restore_${g.key}"),
                        )
                        Column {
                            Text("${Bidi.isolate(g.group)} · ${Bidi.isolate(g.month)}")
                            Text(totalText(Total(g.count, g.bytes)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val total = step.groups.filter { it.key in chosen }
                if (total.isNotEmpty()) {
                    Text(
                        totalText(Total(total.sumOf { it.count }, total.sumOf { it.bytes })),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("restoreTotal"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { actions.onRestore(chosen) },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.testTag("restoreRun"),
            ) { Text(stringResource(R.string.restore_run)) }
        },
        dismissButton = { CancelButton(actions.onClose) },
    )
}
