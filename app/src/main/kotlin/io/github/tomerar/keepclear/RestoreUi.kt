// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.backup.smb.SmbLocation
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.restore.Placement
import io.github.tomerar.keepclear.engine.restore.RestoreReport
import io.github.tomerar.keepclear.engine.restore.Space
import io.github.tomerar.keepclear.engine.restore.Where

/** MOVE-17: one source folder and month of a backup, restored together. */
data class RestoreGroup(
    val group: String,
    val month: String,
    /** Bytes of the files that would be written (skipped and already-there ones not counted, D1). */
    val bytes: Long,
    /** D2: files that go back to where they were. */
    val toOriginal: Int,
    /** D2: files that go to the restore folder. */
    val toRestoreFolder: Int,
    /** D2: hidden files and programs, never written. */
    val skipped: Int = 0,
    /** Already on the phone with the same content: nothing written, no room needed. */
    val alreadyThere: Int = 0,
) {
    val key: String get() = "$group|$month"

    /** Files that would be written. */
    val count: Int get() = toOriginal + toRestoreFolder
}

/** Groups the engine's placements by source folder and month, for the catalog (D2). */
fun restoreGroups(placements: List<Placement>): List<RestoreGroup> =
    placements.groupBy { "${it.entry.group}|${it.entry.month}" }.map { (_, list) ->
        val first = list.first().entry
        RestoreGroup(
            group = first.group,
            month = first.month,
            bytes = list.filter { it.writes }.sumOf { it.entry.line.size },
            toOriginal = list.count { it.where == Where.ORIGINAL },
            toRestoreFolder = list.count { it.where == Where.RESTORE_FOLDER },
            skipped = list.count { it.where == Where.SKIPPED },
            alreadyThere = list.count { it.where == Where.ALREADY_THERE },
        )
    }.sortedWith(compareBy({ it.group }, { it.month }))

/**
 * D1 "Choose only what fits": keeps the chosen months, in the catalog's order, while they fit in [room]. A
 * month that doesn't fit is left out and smaller later ones may still come in.
 */
fun fitByMonth(groups: List<RestoreGroup>, chosen: Set<String>, room: Long): Set<String> {
    var left = room
    return groups.filter { it.key in chosen }.filter { g ->
        (g.bytes <= left).also { fits -> if (fits) left -= g.bytes }
    }.map { it.key }.toSet()
}

/**
 * D1: free space on the phone's shared storage. Android's allocatable bytes (`StorageManager`) count cache it
 * could clear; restoring doesn't ask Android to clear anything, so the smaller of that and what is free right now
 * is used (decided conservatively, progress.md C34). Null when it can't be measured: then nothing is restored.
 */
fun measurePhoneSpace(context: android.content.Context, root: String): Space? = runCatching {
    val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
    val stat = android.os.StatFs(root)
    val allocatable = sm.getAllocatableBytes(sm.getUuidForPath(java.io.File(root)))
    Space(allocatable = minOf(allocatable, stat.availableBytes), total = stat.totalBytes)
}.getOrNull()

/** Where the "Restore from a backup" flow is. */
sealed interface RestoreStep {
    /** Choose the drive, folder or network drive that holds the backup. */
    data object Pick : RestoreStep

    /** F2: the backup is being opened and read (a network drive can take a while); Cancel closes the flow. */
    data object Loading : RestoreStep

    /** This build can't put files back where they were without All files access. */
    data object NeedsAllFiles : RestoreStep

    /** Nothing KeepClear can restore was found there. */
    data object NoBackup : RestoreStep

    /** The drive holds backups of several phones. */
    data class Backups(val roots: List<String>) : RestoreStep

    /** [space] is the phone's free space when the catalog was read; null when it couldn't be measured (D1). */
    data class Catalog(val rootRel: String, val groups: List<RestoreGroup>, val space: Space?) : RestoreStep

    data class Running(val done: Int, val total: Int) : RestoreStep

    data class Done(val report: RestoreReport) : RestoreStep
}

class RestoreActions(
    val onStart: () -> Unit = {},
    val onPickFolder: () -> Unit = {},
    val onNetworkDrive: (SmbLocation) -> Unit = {},
    val savedNetworkDrive: () -> SmbLocation? = { null },
    /** F2 (C76): the computer at home set up under Settings › Advanced (its host), or null: then it isn't offered. */
    val networkHost: String? = null,
    /** F2: stop a restore that is running (what is back stays back). */
    val onCancel: () -> Unit = {},
    /** M17b: the Nextcloud set up under Settings › Advanced (its host), or null. */
    val nextcloudHost: String? = null,
    val onNextcloud: () -> Unit = {},
    val onChooseBackup: (String) -> Unit = {},
    val onRestore: (Set<String>) -> Unit = {},
    val onClose: () -> Unit = {},
)

@Composable
fun RestoreDialogs(step: RestoreStep?, actions: RestoreActions) {
    when (step) {
        // Closed: nothing to show.
        null -> {}

        RestoreStep.Pick -> {
            // F2 (C76): the drive or folder, and only the places set up under Settings › Advanced.
            InfoSheet(
                title = stringResource(R.string.restore_title),
                dismissText = stringResource(R.string.move_cancel),
                onDismiss = actions.onClose,
                modifier = Modifier.testTag("restorePick"),
                primaryButton = {
                    KcButton(
                        stringResource(R.string.restore_pick_folder),
                        onClick = actions.onPickFolder,
                        modifier = Modifier.fillMaxWidth().testTag("restorePickFolder"),
                    )
                },
            ) {
                Text(stringResource(R.string.restore_pick_body), style = MaterialTheme.typography.bodyMedium)
                actions.networkHost?.let { host ->
                    KcButton(
                        stringResource(R.string.move_network_saved_option, Bidi.isolate(host)),
                        onClick = { actions.savedNetworkDrive()?.let(actions.onNetworkDrive) },
                        style = KcButtonStyle.Tonal,
                        modifier = Modifier.fillMaxWidth().testTag("restoreNetwork"),
                    )
                }
                actions.nextcloudHost?.let { host ->
                    KcButton(
                        stringResource(R.string.restore_from_nextcloud, Bidi.isolate(host)),
                        onClick = actions.onNextcloud,
                        style = KcButtonStyle.Tonal,
                        modifier = Modifier.fillMaxWidth().testTag("restoreNextcloud"),
                    )
                }
            }
        }

        RestoreStep.Loading -> {
            InfoSheet(
                title = stringResource(R.string.restore_title),
                dismissText = stringResource(R.string.move_cancel),
                onDismiss = actions.onClose,
                modifier = Modifier.testTag("restoreLoading"),
            ) {
                Text(stringResource(R.string.restore_loading), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
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
                // F2: a restore can be stopped; what is already back stays back.
                confirmButton = {
                    KcButton(
                        stringResource(R.string.restore_stop),
                        onClick = actions.onCancel,
                        style = KcButtonStyle.Text,
                        modifier = Modifier.testTag("restoreStop"),
                    )
                },
            )
        }

        is RestoreStep.Done -> {
            Info(doneLines(step.report).joinToString("\n"), actions.onClose, tag = "restoreDone")
        }
    }
}

@Composable
private fun doneLines(r: RestoreReport): List<String> {
    val simulated = r.simulated
    val noRoom = r.notEnoughRoom
    return buildList {
        when {
            // D1, SAFETY-9: trial mode wrote nothing; say what would have happened.
            simulated != null -> {
                add(
                    stringResource(
                        R.string.restore_simulated,
                        totalText(Total(simulated.files, simulated.bytes)),
                        countText(simulated.toOriginal),
                        countText(simulated.toRestoreFolder),
                    ),
                )
                if (simulated.alreadyThere > 0) {
                    add(countSentence(R.plurals.restore_already_on_phone, simulated.alreadyThere))
                }
                if (simulated.skipped > 0) add(countSentence(R.plurals.restore_skipped, simulated.skipped))
                return@buildList
            }

            noRoom != null -> {
                val space = noRoom.space
                add(
                    if (space == null) {
                        stringResource(R.string.restore_space_unknown)
                    } else {
                        stringResource(R.string.restore_not_enough_room, sizeText(space.reserve))
                    },
                )
                return@buildList
            }

            else -> {
                add(countSentence(R.plurals.restore_done, r.restored.size))
            }
        }
        if (r.redirected > 0) add(countSentence(R.plurals.restore_redirected, r.redirected))
        if (r.alreadyThere > 0) add(countSentence(R.plurals.restore_already_there, r.alreadyThere))
        if (r.damaged > 0) add(countSentence(R.plurals.restore_damaged, r.damaged))
        if (r.failed > 0) add(countSentence(R.plurals.restore_failed, r.failed))
        if (r.skipped > 0) add(countSentence(R.plurals.restore_skipped, r.skipped))
        if (r.stoppedLowSpace) add(stringResource(R.string.restore_stopped_low_space))
        if (r.interrupted) add(stringResource(R.string.restore_interrupted))
    }
}

/** F2: the backup's source folder in plain words; a folder KeepClear doesn't know keeps its name (isolated). */
@Composable
internal fun restoreGroupName(group: String): String = when (group) {
    "Camera" -> stringResource(R.string.restore_group_camera)
    "WhatsApp" -> stringResource(R.string.restore_group_whatsapp)
    "Telegram" -> stringResource(R.string.restore_group_telegram)
    "Pictures" -> stringResource(R.string.restore_group_pictures)
    "Other" -> stringResource(R.string.restore_group_other)
    else -> Bidi.isolate(group)
}

/** F2: "2021-07" → "July 2021" in the user's language; anything else unchanged (isolated). */
fun restoreMonthLabel(month: String, locale: java.util.Locale): String = Bidi.isolate(
    runCatching {
        java.time.YearMonth.parse(month).format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", locale))
    }.getOrDefault(month),
)

/** A count on its own, formatted for the locale and isolated. */
@Composable
private fun countText(count: Int): String =
    Bidi.isolate(java.text.NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(count))

/** D2: where a month's files go. */
@Composable
private fun whereText(g: RestoreGroup): String? = when {
    g.count == 0 -> null
    g.toRestoreFolder == 0 -> stringResource(R.string.restore_to_original)
    g.toOriginal == 0 -> stringResource(R.string.restore_to_folder)
    else -> stringResource(R.string.restore_to_mixed, countText(g.toOriginal), countText(g.toRestoreFolder))
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
    val picked = step.groups.filter { it.key in chosen }
    val neededBytes = picked.sumOf { it.bytes }
    val space = step.space
    // D1: the chosen months must fit with the reserve left free.
    val fits = space != null && neededBytes <= space.room
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
                    val checked = g.key in chosen
                    // F2 (a11y): the checkbox and its label are one toggleable target.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox) { on ->
                                chosen = if (on) chosen + g.key else chosen - g.key
                            }
                            .testTag("restore_${g.key}"),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column {
                            // F2: "Camera photos · July 2021", not "Camera · 2021-07".
                            Text(
                                stringResource(
                                    R.string.restore_group_label,
                                    restoreGroupName(g.group),
                                    restoreMonthLabel(g.month, LocalConfiguration.current.locales[0]),
                                ),
                                modifier = Modifier.testTag("restoreLabel_${g.key}"),
                            )
                            Text(totalText(Total(g.count, g.bytes)), style = MaterialTheme.typography.bodySmall)
                            whereText(g)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("restoreWhere_${g.key}"),
                                )
                            }
                            if (g.alreadyThere > 0) {
                                Text(
                                    countSentence(R.plurals.restore_already_on_phone, g.alreadyThere),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("restoreAlready_${g.key}"),
                                )
                            }
                            if (g.skipped > 0) {
                                Text(
                                    countSentence(R.plurals.restore_skipped, g.skipped),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (picked.isNotEmpty()) {
                    Text(
                        totalText(Total(picked.sumOf { it.count }, neededBytes)),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("restoreTotal"),
                    )
                }
                // D1: free space against what is chosen, always visible.
                Text(
                    if (space == null) {
                        stringResource(R.string.restore_space_unknown)
                    } else {
                        stringResource(R.string.restore_space, sizeText(space.allocatable), sizeText(neededBytes))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("restoreSpace"),
                )
                if (space != null && !fits) {
                    Text(
                        stringResource(R.string.restore_too_big, sizeText(space.reserve)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("restoreTooBig"),
                    )
                    TextButton(
                        onClick = { chosen = fitByMonth(step.groups, chosen, space.room) },
                        modifier = Modifier.testTag("restoreFit"),
                    ) { Text(stringResource(R.string.restore_fit)) }
                }
            }
        },
        confirmButton = {
            KcButton(
                stringResource(R.string.restore_run),
                onClick = { actions.onRestore(chosen) },
                enabled = picked.any { it.count > 0 } && fits,
                modifier = Modifier.testTag("restoreRun"),
            )
        },
        dismissButton = { CancelButton(actions.onClose) },
    )
}
