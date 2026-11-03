// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.backup.smb.SmbLocation
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcSearchField
import io.github.tomerar.keepclear.core.designsystem.component.MEDIA_TILE_MIN
import io.github.tomerar.keepclear.core.designsystem.component.MediaTile
import io.github.tomerar.keepclear.core.designsystem.component.MonthHeader
import io.github.tomerar.keepclear.core.designsystem.component.PanelSheet
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.restore.BackupEntry
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

/**
 * H1c: one file of a backup that restoring would write, for the grid and single-item restore. Files already on the
 * phone and skipped ones are not offered one by one (their months still say so).
 */
data class RestoreFile(
    val rel: String,
    val group: String,
    val month: String,
    val mtime: Long,
    val size: Long,
    val where: Where,
) {
    val name: String get() = rel.substringAfterLast('/')

    /** Its key in a selection; a month's key ("group|month") never starts with "/". */
    val key: String get() = key(rel)

    val monthKey: String get() = "$group|$month"

    companion object {
        fun key(rel: String) = "/$rel"
    }
}

/** H1c: the files restoring would write, from the engine's placements (D2). */
fun restoreFiles(placements: List<Placement>): List<RestoreFile> = placements.filter { it.writes }.map {
    RestoreFile(it.entry.line.rel, it.entry.group, it.entry.month, it.entry.line.mtime, it.entry.line.size, it.where)
}

/** H1c: the backup's entries a selection names: a month key takes the whole month, a file key one file. */
fun selectedEntries(entries: List<BackupEntry>, keys: Set<String>): List<BackupEntry> =
    entries.filter { "${it.group}|${it.month}" in keys || RestoreFile.key(it.line.rel) in keys }

/**
 * H1c: what a selection holds. Month keys (the list) and file keys (the grid) may be mixed; with no [files] (an older
 * catalog) only months count.
 */
class RestoreChoice(
    private val groups: List<RestoreGroup>,
    private val files: List<RestoreFile>,
    val keys: Set<String>,
) {
    fun isChosen(f: RestoreFile) = f.monthKey in keys || f.key in keys

    private fun filesOf(g: RestoreGroup) = files.filter { it.monthKey == g.key }

    fun monthChecked(g: RestoreGroup) = g.key in keys || filesOf(g).let { it.isNotEmpty() && it.all(::isChosen) }

    val chosenFiles: List<RestoreFile> get() = files.filter(::isChosen)

    val count: Int get() = if (files.isEmpty()) {
        groups.filter {
            it.key in keys
        }.sumOf { it.count }
    } else {
        chosenFiles.size
    }

    val bytes: Long get() =
        if (files.isEmpty()) groups.filter { it.key in keys }.sumOf { it.bytes } else chosenFiles.sumOf { it.size }

    fun toggleFile(f: RestoreFile): Set<String> = when {
        f.key in keys -> keys - f.key

        // Leaving a whole month: keep the rest of it, file by file.
        f.monthKey in keys -> keys - f.monthKey + files.filter { it.monthKey == f.monthKey && it != f }.map { it.key }

        else -> keys + f.key
    }

    fun toggleMonth(g: RestoreGroup, on: Boolean): Set<String> {
        val without = keys - g.key - filesOf(g).map { it.key }.toSet()
        return if (on) without + g.key else without
    }

    /** D1, H1c: what fits when the phone hasn't room for all of it; null when everything fits or nothing does. */
    fun partial(room: Long): PartialPlan? {
        if (bytes <= room) return null
        val plan = if (files.isEmpty()) {
            val months = fitByMonth(groups, keys, room)
            val picked = groups.filter { it.key in months }
            PartialPlan(months, picked.sumOf { it.count }, count, picked.sumOf { it.bytes })
        } else {
            PartialRestore.plan(chosenFiles, room)
        }
        return plan.takeIf { it.fits > 0 }
    }
}

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

    /**
     * [space] is the phone's free space when the catalog was read; null when it couldn't be measured (D1). H1c:
     * [files] are the files one by one (the grid); [thumbnails] is true only for a drive on the phone (USB, SD card),
     * where Android makes the pictures without reading over a network.
     */
    data class Catalog(
        val rootRel: String,
        val groups: List<RestoreGroup>,
        val space: Space?,
        val files: List<RestoreFile> = emptyList(),
        val thumbnails: Boolean = false,
    ) : RestoreStep

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
    /** Month keys ("group|month") and/or file keys ([RestoreFile.key]). */
    val onRestore: (Set<String>) -> Unit = {},
    val onClose: () -> Unit = {},
    /** H1c: opens Android's "All files access" screen for KeepClear; null where the build can't have it (`play`). */
    val onAllFilesAccess: (() -> Unit)? = null,
    /** H1c: after "not enough room", read the catalog again with the free space measured now. */
    val onChooseAgain: () -> Unit = {},
    /** H1c: a backed-up file's picture (a drive on the phone only), by its path in the backup; null elsewhere. */
    val thumbnail: (suspend (ThumbnailLoader, String, Int) -> android.graphics.Bitmap?)? = null,
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
            // H1c: the one next step is the settings screen that grants it (the `foss` build only).
            Info(
                stringResource(R.string.restore_needs_all_files),
                actions.onClose,
                primary = actions.onAllFilesAccess?.let { open ->
                    {
                        KcButton(
                            stringResource(R.string.restore_open_settings),
                            onClick = {
                                actions.onClose()
                                open()
                            },
                            modifier = Modifier.fillMaxWidth().testTag("restoreAllFilesSettings"),
                        )
                    }
                },
            )
        }

        RestoreStep.NoBackup -> {
            // H1c: an empty state with one next step: look in another place.
            Info(
                stringResource(R.string.restore_no_backup),
                actions.onClose,
                primary = {
                    KcButton(
                        stringResource(R.string.restore_pick_other),
                        onClick = actions.onStart,
                        modifier = Modifier.fillMaxWidth().testTag("restorePickAgain"),
                    )
                },
            )
        }

        is RestoreStep.Backups -> {
            InfoSheet(
                title = stringResource(R.string.restore_which_phone),
                dismissText = stringResource(R.string.move_cancel),
                onDismiss = actions.onClose,
                modifier = Modifier.testTag("restoreBackups"),
            ) {
                step.roots.forEach { root ->
                    KcButton(
                        Bidi.isolate(root.substringAfterLast('/')),
                        onClick = { actions.onChooseBackup(root) },
                        style = KcButtonStyle.Tonal,
                        modifier = Modifier.fillMaxWidth().testTag("backup_$root"),
                    )
                }
            }
        }

        is RestoreStep.Catalog -> {
            CatalogSheet(step, actions)
        }

        is RestoreStep.Running -> {
            // F2: a restore can be stopped; what is already back stays back. Back alone doesn't stop it.
            InfoSheet(
                title = stringResource(R.string.restore_running),
                dismissText = stringResource(R.string.restore_stop),
                onDismiss = {},
                dismissAction = actions.onCancel,
                dismissModifier = Modifier.testTag("restoreStop"),
            ) {
                Column(modifier = Modifier.testTag("restoreRunning")) {
                    LinearProgressIndicator(
                        progress = { if (step.total == 0) 0f else step.done.toFloat() / step.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(countSentence(R.plurals.restore_progress, step.done))
                }
            }
        }

        is RestoreStep.Done -> {
            // H1c: "not enough room" is never a dead end: choose again with the space there is now.
            val noRoom = step.report.notEnoughRoom != null
            Info(
                doneLines(step.report).joinToString("\n"),
                actions.onClose,
                tag = "restoreDone",
                primary = if (noRoom) {
                    {
                        KcButton(
                            stringResource(R.string.restore_choose_again),
                            onClick = actions.onChooseAgain,
                            modifier = Modifier.fillMaxWidth().testTag("restoreChooseAgain"),
                        )
                    }
                } else {
                    null
                },
            )
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

/** D2: where a month's files go. */
@Composable
private fun whereText(g: RestoreGroup): String? = when {
    g.count == 0 -> null
    g.toRestoreFolder == 0 -> stringResource(R.string.restore_to_original)
    g.toOriginal == 0 -> stringResource(R.string.restore_to_folder)
    else -> stringResource(R.string.restore_to_mixed, countText(g.toOriginal), countText(g.toRestoreFolder))
}

@Composable
private fun Info(
    text: String,
    onClose: () -> Unit,
    tag: String = "restoreInfo",
    primary: (@Composable () -> Unit)? = null,
) = InfoSheet(
    title = stringResource(R.string.restore_title),
    dismissText = stringResource(R.string.restore_ok),
    onDismiss = onClose,
    primaryButton = primary,
    dismissModifier = Modifier.testTag("restoreClose"),
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(tag))
}

/** H1c: a backed-up file's picture: only from a drive on the phone, and only for photos and videos. */
private fun restorePicture(
    step: RestoreStep.Catalog,
    actions: RestoreActions,
    f: RestoreFile,
): (suspend (ThumbnailLoader, Int) -> android.graphics.Bitmap?)? {
    val load = actions.thumbnail ?: return null
    if (!step.thumbnails || !FileKinds.hasPicture(f.name)) return null
    return { loader, px -> load(loader, "${step.rootRel}/${f.rel}", px) }
}

/**
 * MOVE-17, H1c: choosing what comes back, in a sheet. The grid shows the files by month (newest first) with search;
 * the list shows whole months (D2: where each goes). Nothing is pre-selected. Where the files go is said in plain
 * words; when the phone hasn't room for all of it, the newest files that fit can be restored now (D1, C136).
 */
@Composable
private fun CatalogSheet(step: RestoreStep.Catalog, actions: RestoreActions) {
    var chosen by rememberSaveable(step.rootRel) { mutableStateOf(setOf<String>()) }
    var grid by rememberSaveable(step.rootRel) { mutableStateOf(step.files.isNotEmpty()) }
    var query by rememberSaveable(step.rootRel) { mutableStateOf("") }
    var preview by remember { mutableStateOf<RestoreFile?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { java.time.ZoneId.systemDefault() }
    val choice = RestoreChoice(step.groups, step.files, chosen)
    val neededBytes = choice.bytes
    val space = step.space
    // D1: the chosen files must fit with the reserve left free.
    val fits = space != null && neededBytes <= space.room
    PanelSheet(
        title = stringResource(R.string.restore_choose),
        dismissText = stringResource(R.string.move_cancel),
        onDismiss = actions.onClose,
        modifier = Modifier.testTag("restoreSheet"),
        primaryButton = {
            KcButton(
                stringResource(R.string.restore_run),
                onClick = { actions.onRestore(chosen) },
                enabled = choice.count > 0 && fits,
                modifier = Modifier.fillMaxWidth().testTag("restoreRun"),
            )
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(MEDIA_TILE_MIN),
            modifier = Modifier.weight(1f, fill = false).testTag("restoreCatalog"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "where", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // H1c: where the files go, in plain words (the restore folder by its real name).
                    Text(
                        stringResource(R.string.restore_destination),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("restoreDestination"),
                    )
                    if (step.files.isNotEmpty()) {
                        KcButton(
                            stringResource(if (grid) R.string.grid_show_months else R.string.grid_show_grid),
                            onClick = { grid = !grid },
                            style = KcButtonStyle.Text,
                            icon = if (grid) KcIcons.List else KcIcons.Grid,
                            modifier = Modifier.testTag("restoreViewToggle"),
                        )
                    }
                    if (grid) {
                        KcSearchField(
                            query,
                            { query = it },
                            placeholder = stringResource(R.string.grid_search_hint),
                            clearLabel = stringResource(R.string.grid_search_clear),
                            modifier = Modifier.testTag("restoreSearch"),
                        )
                    }
                }
            }
            if (grid) {
                val shown = step.files.filter { GridSearch.matches(it.name, it.mtime, query, zone) }
                if (shown.isEmpty()) {
                    item(key = "noMatch", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.grid_no_match), modifier = Modifier.testTag("restoreNoMatch"))
                    }
                }
                MediaGrid.monthSections(shown, zone, tie = { it.rel }) { it.mtime }.forEach { section ->
                    item(key = "month_${section.month}", span = { GridItemSpan(maxLineSpan) }) {
                        MonthHeader(
                            MediaGrid.monthTitle(section.month, locale),
                            Modifier.testTag("monthHeader_${section.month}"),
                        )
                    }
                    items(section.items, key = { it.key }) { f ->
                        RestoreTile(
                            f,
                            selected = choice.isChosen(f),
                            picture = restorePicture(step, actions, f),
                            zone = zone,
                            onToggle = { chosen = choice.toggleFile(f) },
                            onPreview = { preview = f },
                            onRestoreOne = { actions.onRestore(setOf(f.key)) },
                        )
                    }
                }
            } else {
                items(step.groups, key = { it.key }, span = { GridItemSpan(maxLineSpan) }) { g ->
                    MonthRow(g, checked = choice.monthChecked(g)) { on -> chosen = choice.toggleMonth(g, on) }
                }
            }
        }
        // D1: what is chosen against the free space, pinned under the grid so it is always in view.
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            // At 200% font it scrolls on its own, so the grid above always keeps room.
            modifier = Modifier.padding(top = 8.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                .testTag("restoreFooter"),
        ) {
            if (choice.count > 0) {
                Text(
                    totalText(Total(choice.count, neededBytes)),
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
                // H1c: never a dead end: what fits can come back now (newest first, C136).
                choice.partial(space.room)?.let { plan ->
                    Text(
                        countSentence(R.plurals.restore_partial, plan.of, countText(plan.fits)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("restorePartial"),
                    )
                    KcButton(
                        stringResource(R.string.restore_partial_run),
                        onClick = { actions.onRestore(plan.keys) },
                        style = KcButtonStyle.Tonal,
                        modifier = Modifier.fillMaxWidth().testTag("restoreFit"),
                    )
                }
            }
        }
    }
    preview?.let { f ->
        RestoreFileSheet(
            f,
            picture = restorePicture(step, actions, f),
            zone = zone,
            onDismiss = { preview = null },
            onRestore = {
                preview = null
                actions.onRestore(setOf(f.key))
            },
        )
    }
}

/** One month of the list (D2): a checkbox row with where its files go. */
@Composable
private fun MonthRow(g: RestoreGroup, checked: Boolean, onToggle: (Boolean) -> Unit) {
    // F2 (a11y): the checkbox and its label are one toggleable target.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
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

/**
 * H1c: one backed-up file in the grid: a tap chooses it. TalkBack reads its name, date and size and offers Restore
 * (this file alone) and Preview; a long press opens the preview too.
 */
@Composable
private fun RestoreTile(
    f: RestoreFile,
    selected: Boolean,
    picture: (suspend (ThumbnailLoader, Int) -> android.graphics.Bitmap?)?,
    zone: java.time.ZoneId,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
    onRestoreOne: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val date = Bidi.isolate(MediaGrid.dayText(f.mtime, zone, locale))
    val size = Bidi.isolate(ByteSize.format(f.size, locale))
    val restoreLabel = stringResource(R.string.restore_this_file)
    val previewLabel = stringResource(R.string.grid_preview)
    MediaTile(
        thumbnail = rememberTilePainter(f.key, load = picture),
        typeIcon = kindIcon(FileKinds.of(f.name)),
        date = date,
        size = size,
        description = stringResource(R.string.grid_tile_description, Bidi.fileName(f.name), date, size),
        onClick = onToggle,
        onLongClick = onPreview,
        selected = selected,
        modifier = Modifier.testTag("file_${f.rel}"),
        actions = listOf(
            CustomAccessibilityAction(restoreLabel) {
                onRestoreOne()
                true
            },
            CustomAccessibilityAction(previewLabel) {
                onPreview()
                true
            },
        ),
    )
}

/** H1c: one backed-up file's preview: its picture (or type), date, size, where it goes; restore it alone. */
@Composable
private fun RestoreFileSheet(
    f: RestoreFile,
    picture: (suspend (ThumbnailLoader, Int) -> android.graphics.Bitmap?)?,
    zone: java.time.ZoneId,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    InfoSheet(
        title = Bidi.fileName(f.name),
        dismissText = stringResource(R.string.restore_ok),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("restoreFileSheet"),
        primaryButton = {
            KcButton(
                stringResource(R.string.restore_this_file),
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth().testTag("restoreOne"),
            )
        },
    ) {
        val painter = rememberTilePainter("details_${f.key}", sizeDp = 320, load = picture)
        Box(Modifier.fillMaxWidth().heightIn(max = 240.dp), contentAlignment = Alignment.Center) {
            if (painter != null) {
                Image(
                    painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().clip(KcShapes.thumbnail),
                )
            } else {
                Icon(kindIcon(FileKinds.of(f.name)), contentDescription = null, modifier = Modifier.padding(16.dp))
            }
        }
        Text(
            stringResource(
                R.string.grid_details_line,
                Bidi.isolate(MediaGrid.dayText(f.mtime, zone, locale)),
                Bidi.isolate(ByteSize.format(f.size, locale)),
            ),
            style = KeepClearTheme.type.number,
        )
        Text(
            stringResource(
                if (f.where == Where.ORIGINAL) R.string.restore_to_original else R.string.restore_to_folder,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("restoreFileWhere"),
        )
    }
}
