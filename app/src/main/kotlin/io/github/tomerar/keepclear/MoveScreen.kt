// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.backup.saf.TargetKind
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmSheet
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.designsystem.component.ResultBanner
import io.github.tomerar.keepclear.core.designsystem.component.ThumbStrip
import io.github.tomerar.keepclear.core.designsystem.component.VerifiedStamp
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.move.FailReason
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MovePlanner
import io.github.tomerar.keepclear.engine.move.PauseReason
import java.text.NumberFormat

/** How old files must be to be offered for MOVE (MOVE-1; default 12 months, owner decision Q5). */
enum class MoveAge(val months: Int?) { OLDER_12(12), OLDER_6(6), ALL(null) }

class MoveActions(
    val onPickTarget: (List<ClassifiedItem>) -> Unit = {},
    /** M15: back up to the network drive at this address instead of a picked drive or folder. */
    val onNetworkDrive: (
        List<ClassifiedItem>,
        io.github.tomerar.keepclear.backup.smb.SmbLocation,
    ) -> Unit = { _, _ -> },
    /** M15: the saved network drive, to fill the form again. */
    val savedNetworkDrive: () -> io.github.tomerar.keepclear.backup.smb.SmbLocation? = { null },
    /** M17b: the Nextcloud set up under Settings › Advanced (its host), or null: then it isn't offered at all. */
    val nextcloudHost: String? = null,
    val onNextcloud: (List<ClassifiedItem>) -> Unit = {},
    /** F2 (C76): the computer at home set up under Settings › Advanced (its host), or null: then it isn't offered. */
    val networkHost: String? = null,
    val onStart: () -> Unit = {},
    val onCancelPick: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onResume: (String) -> Unit = {},
    val onFree: (String, io.github.tomerar.keepclear.core.safety.Confirmation) -> Unit = { _, _ -> },
    /** H1 / MOVE-15: the second, uncached check before freeing. */
    val onCheck: (String) -> Unit = {},
    val onCancelCheck: () -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onOpenGooglePhotos: () -> Unit = {},
    val onOpenGroup: (String) -> Unit = {},
    val onOpenTrash: () -> Unit = {},
    /** MOVE-17: "Restore from a backup". */
    val restore: RestoreActions = RestoreActions(),
) {
    /** The same actions with [open] for "Show group": nothing else can be lost on the way. */
    fun withOpenGroup(open: (String) -> Unit) = MoveActions(
        onPickTarget = onPickTarget,
        onNetworkDrive = onNetworkDrive,
        savedNetworkDrive = savedNetworkDrive,
        nextcloudHost = nextcloudHost,
        onNextcloud = onNextcloud,
        networkHost = networkHost,
        onStart = onStart,
        onCancelPick = onCancelPick,
        onPause = onPause,
        onStop = onStop,
        onResume = onResume,
        onFree = onFree,
        onCheck = onCheck,
        onCancelCheck = onCancelCheck,
        onShare = onShare,
        onOpenGooglePhotos = onOpenGooglePhotos,
        onOpenGroup = open,
        onOpenTrash = onOpenTrash,
        restore = restore,
    )
}

/**
 * Items of [ruleIds] old enough for [age] (MOVE-1). Owner decision P2: favourites are backed up too (a backup only
 * copies); the free step keeps them on the phone while its option is ticked.
 */
fun moveSelection(
    summary: ClassificationSummary,
    ruleIds: Set<String>,
    age: MoveAge,
    now: Long,
): List<ClassifiedItem> {
    val cutoff = age.months?.let { now - it * 30L * 86_400_000L }
    return summary.groups(Category.MOVE).filter { it.ruleId in ruleIds }.flatMap { it.items }
        .filter { cutoff == null || it.item.mtimeMillis < cutoff }
}

/**
 * MOVE (MOVE-1…18): pick sources (nothing pre-selected, SAFETY-10) and age, pick a drive, pre-flight, copy and
 * verify in a foreground service, then a receipt. Freeing originals is a separate, confirmed step.
 */
@Composable
fun MoveScreen(
    summary: ClassificationSummary?,
    state: MoveUiState,
    now: Long,
    notificationsOff: Boolean,
    actions: MoveActions,
    /** null on the Backup tab (its root: no back arrow). */
    onBack: (() -> Unit)?,
    /** F1b: before a scan, why there is nothing to move yet ("Counting…", "Needs access"); restore still works. */
    pending: String? = null,
    /** F1b: the backup places set up under Settings › Advanced. */
    places: BackupPlaces? = null,
) {
    val groups = summary?.groups(Category.MOVE).orEmpty()
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    // Owner decision P2: the first backup defaults to "Everything"; after that, older than 12 months. The user's own
    // choice sticks. Sources are never pre-selected (SAFETY-10).
    var chosenAge by rememberSaveable { mutableStateOf<MoveAge?>(null) }
    val age = chosenAge ?: defaultAge(state.jobs)
    var confirmFree by remember { mutableStateOf<JobView?>(null) }
    var guide by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    // E13: worked out when the choice changes, not on every recomposition (thousands of files).
    val items = remember(summary, selected, age) {
        summary?.let { moveSelection(it, selected, age, now) }.orEmpty()
    }
    val total = remember(items) { items.fold(Total()) { t, i -> t + i } }
    val busy = state.running != null
    // E3: a free, check or restore runs in the background; nothing else starts meanwhile.
    val taskRunning = state.task != null
    // Owner brief H1 item 2: while a job runs, the selection is hidden (nothing to change mid-copy).
    val jobActive = busy || taskRunning

    Scaffold(
        topBar = { if (onBack != null) SubScreenTopBar(stringResource(R.string.move_title), onBack) },
        // Owner brief H1 item 2: the primary button sits above the tabs, never below the fold.
        bottomBar = {
            if (summary != null && !jobActive) {
                BackupBar(
                    total = total,
                    enabled = items.isNotEmpty() && !state.busy,
                    preparing = state.busy,
                    onClick = { guide = true },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("moveScreen"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onBack == null) SectionHeading(stringResource(R.string.move_title))
                    // Owner brief H1 item 1: it copies; nothing is deleted at this step.
                    Text(
                        stringResource(R.string.backup_only_copies),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("onlyCopies"),
                    )
                }
            }
            if (notificationsOff && busy) {
                item { Text(stringResource(R.string.move_notif_warning), modifier = Modifier.testTag("notifWarning")) }
            }
            state.problem?.let { problem -> item { ProblemCard(problem) } }
            state.task?.let { task -> item { TaskProgressText(task) } }
            state.freeResult?.let { r ->
                item { FreeResultCard(r, actions.onOpenTrash, onRestore = actions.restore.onStart) }
            }
            state.check?.let { check ->
                val label = state.jobs.firstOrNull { it.job.id == check.jobId }?.job?.targetLabel.orEmpty()
                item { CheckCard(check, label, actions) }
            }
            // Owner brief H1 item 11: the reminder was tapped.
            state.recheck?.takeIf { state.check == null }?.let { jobId ->
                item { RecheckCard(enabled = !jobActive, onCheck = { actions.onCheck(jobId) }) }
            }
            state.jobs.firstOrNull()?.let { latest ->
                item {
                    JobCard(
                        latest,
                        running = state.running == latest.job.id,
                        canResume = !jobActive,
                        actions = actions,
                        onFree = { confirmFree = latest },
                        onStop = { confirmStop = true },
                    )
                }
            }
            if (summary == null) {
                item {
                    Text(
                        pending ?: stringResource(R.string.home_card_counting),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("movePending"),
                    )
                }
            } else if (!jobActive) {
                moveSources(summary, groups, selected, { selected = it }, age, { chosenAge = it }, now, actions)
                item { PhotosNote(actions.onOpenGooglePhotos) }
            }
            // MOVE-17: bring files back from a backup (a drive, folder or network drive), also before any scan.
            item {
                KcButton(
                    stringResource(R.string.restore_start),
                    onClick = actions.restore.onStart,
                    enabled = !state.busy && !taskRunning,
                    style = KcButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth().testTag("restoreStart"),
                )
            }
            places?.let { p -> item { BackupPlacesSection(p) } }
            if (state.jobs.size > 1) {
                item { SectionHeading(stringResource(R.string.move_history)) }
                items(state.jobs.drop(1), key = { it.job.id }) { view ->
                    // Unfinished older jobs keep their Continue button.
                    JobCard(
                        view,
                        running = state.running == view.job.id,
                        canResume = !jobActive,
                        actions = actions,
                        onFree = { confirmFree = view },
                        onStop = { confirmStop = true },
                    )
                }
            }
        }
    }

    state.picked?.let { picked ->
        PickedDialog(picked, actions)
    }
    if (guide) {
        PickGuideDialog(
            drive = state.drive,
            onContinue = {
                guide = false
                actions.onPickTarget(items)
            },
            networkHost = actions.networkHost,
            onNetwork = {
                guide = false
                // F2 (C76): only the place set up under Settings › Advanced; no address form here.
                actions.savedNetworkDrive()?.let { actions.onNetworkDrive(items, it) }
            },
            nextcloudHost = actions.nextcloudHost,
            onNextcloud = {
                guide = false
                actions.onNextcloud(items)
            },
            onDismiss = { guide = false },
        )
    }
    RestoreDialogs(state.restore, actions.restore)
    if (confirmStop) {
        // Stopping keeps everything copied so far; "keep going" is the main button.
        ConfirmSheet(
            title = stringResource(R.string.move_stop_confirm_title),
            dismissText = stringResource(R.string.move_stop_confirm_yes),
            // Back or a tap outside keeps going; only the Stop button stops (PR #48 review: one Stop, and it stops).
            onDismiss = { confirmStop = false },
            dismissAction = {
                confirmStop = false
                actions.onStop()
            },
            dismissModifier = Modifier.testTag("confirmStop"),
            note = stringResource(R.string.move_stop_confirm_body),
            confirmButton = {
                KcButton(
                    stringResource(R.string.move_keep_going),
                    onClick = { confirmStop = false },
                    modifier = Modifier.fillMaxWidth().testTag("keepGoing"),
                )
            },
        )
    }
    confirmFree?.let { view ->
        FreeConfirmSheet(
            view,
            keepInTrash = state.keepInTrash,
            now = now,
            onDismiss = { confirmFree = null },
            onConfirm = { paths ->
                confirmFree = null
                actions.onFree(view.job.id, userConfirmed(paths))
            },
        )
    }
}

/**
 * Owner brief H1 item 2: the sticky bar above the tabs: what is selected and the primary button with its size
 * ("Back up 31.0 GB"). Always on screen, whatever the font size and however long the list.
 */
@Composable
private fun BackupBar(total: Total, enabled: Boolean, preparing: Boolean, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().testTag("backupBar"),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.move_selected, totalText(total)),
                style = KeepClearTheme.type.number,
                modifier = Modifier.testTag("moveSelected"),
            )
            KcButton(
                if (total.files == 0) {
                    stringResource(R.string.backup_button_empty)
                } else {
                    stringResource(R.string.backup_button, Bidi.isolate(ByteSize.format(total.bytes, locale)))
                },
                onClick = onClick,
                enabled = enabled,
                icon = KcIcons.Move,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("pickTarget"),
            )
            // F2: a pre-flight being prepared shows progress, not only a disabled button.
            if (preparing) BusyLine()
        }
    }
}

/** Owner brief H1 item 11: the reminder was tapped: connect the drive, then check. */
@Composable
private fun RecheckCard(enabled: Boolean, onCheck: () -> Unit) {
    KcCard(Modifier.testTag("recheckCard")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.recheck_card), style = MaterialTheme.typography.bodyMedium)
            KcButton(
                stringResource(R.string.move_check_button),
                onClick = onCheck,
                enabled = enabled,
                icon = KcIcons.Verified,
                modifier = Modifier.fillMaxWidth().testTag("recheckButton"),
            )
        }
    }
}

/** Owner decision P2: the first backup ("no backup yet") defaults to everything, later ones to older than 12 months. */
fun defaultAge(jobs: List<JobView>): MoveAge = if (jobs.isEmpty()) MoveAge.ALL else MoveAge.OLDER_12

/**
 * Owner decisions P1, P2, P4: the free step. Two options, ticked by default, **keep** favourites and the last 30 days
 * on the phone (they protect files; nothing here selects anything for deletion, SAFETY-10). One honest sentence says
 * what happens: deleted from the phone, the checked copy on the drive is the undo; or, with the user's setting, to the
 * 30-day trash. WhatsApp media gets one more sentence. The confirm tap names exactly the files the options leave in.
 */
@Composable
internal fun FreeConfirmSheet(
    view: JobView,
    keepInTrash: Boolean,
    now: Long,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var keepFavourites by rememberSaveable { mutableStateOf(true) }
    var keepRecent by rememberSaveable { mutableStateOf(true) }
    val options = io.github.tomerar.keepclear.engine.move.FreeOptions(keepFavourites, keepRecent)
    val plan = remember(view.freeCandidates, options, now) {
        io.github.tomerar.keepclear.engine.move.FreePlan.of(view.freeCandidates, options, now)
    }
    val freeing = Total(plan.count, plan.bytes)
    val drive = Bidi.isolate(view.job.targetLabel)
    val locale = LocalConfiguration.current.locales[0]
    ConfirmSheet(
        title = stringResource(R.string.move_free_confirm_title),
        dismissText = stringResource(R.string.move_cancel),
        onDismiss = onDismiss,
        facts = confirmFacts(
            freeing,
            if (keepInTrash) {
                stringResource(R.string.confirm_where_trash)
            } else {
                stringResource(R.string.free_where_for_good, drive)
            },
        ),
        note = if (keepInTrash) {
            stringResource(R.string.move_free_confirm_body, totalText(freeing))
        } else {
            stringResource(R.string.move_free_confirm_for_good, drive)
        },
        modifier = Modifier.testTag("freeConfirm"),
        confirmButton = {
            // C118: recoverable from the checked copy (or the trash), so ink, never red (red = unrecoverable).
            SafeConfirmButton(
                if (keepInTrash) {
                    stringResource(R.string.move_free_confirm_yes)
                } else {
                    stringResource(R.string.move_free_button, Bidi.isolate(ByteSize.format(plan.bytes, locale)))
                },
                onClick = { if (plan.count > 0) onConfirm(plan.paths) },
                modifier = Modifier.fillMaxWidth().testTag("confirmFree"),
                permanent = false,
            )
        },
    ) {
        if (plan.hasWhatsApp) {
            Text(
                stringResource(R.string.free_whatsapp),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("freeWhatsApp"),
            )
        }
        KeepOption(
            stringResource(R.string.free_keep_favourites),
            checked = keepFavourites,
            kept = plan.keptFavourites,
            tag = "keepFavourites",
        ) { keepFavourites = it }
        KeepOption(
            stringResource(R.string.free_keep_recent),
            checked = keepRecent,
            kept = plan.keptRecent,
            tag = "keepRecent",
        ) { keepRecent = it }
        if (plan.count == 0) {
            Text(
                stringResource(R.string.free_nothing),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("freeNothing"),
            )
        }
    }
}

/** One "keep on the phone" option of the free step: a checkbox row and how many files it keeps. */
@Composable
private fun KeepOption(label: String, checked: Boolean, kept: Int, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .testTag(tag),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (checked && kept > 0) {
                Text(
                    countSentence(R.plurals.free_kept_count, kept),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** MOVE-1: the age chips and the MOVE groups to choose from (nothing pre-selected, SAFETY-10). */
private fun androidx.compose.foundation.lazy.LazyListScope.moveSources(
    summary: ClassificationSummary,
    groups: List<io.github.tomerar.keepclear.engine.classify.GroupSummary>,
    selected: Set<String>,
    onSelected: (Set<String>) -> Unit,
    age: MoveAge,
    onAge: (MoveAge) -> Unit,
    now: Long,
    actions: MoveActions,
) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeading(stringResource(R.string.move_what))
            // F2 (a11y): the chips wrap at large font sizes instead of running off the screen.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoveAge.entries.forEach { a ->
                    FilterChip(
                        selected = age == a,
                        onClick = { onAge(a) },
                        label = { Text(stringResource(ageLabel(a))) },
                        modifier = Modifier.testTag("age_${a.name}"),
                    )
                }
            }
            Text(stringResource(R.string.move_favourites_note), style = MaterialTheme.typography.bodySmall)
        }
    }
    items(groups, key = { it.ruleId }) { group ->
        // F3: the count, the pictures and the "+N" all describe the same files: those of the chosen age.
        val chosen = remember(summary, group.ruleId, age) { moveSelection(summary, setOf(group.ruleId), age, now) }
        val groupTotal = remember(chosen) { chosen.fold(Total()) { t, i -> t + i } }
        val checked = group.ruleId in selected
        val thumbs = rememberThumbPainters(chosen)
        KcCard {
            // F2 (a11y): checkbox and label are one toggleable target (TalkBack: one checkbox with its name).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .toggleable(value = checked, role = Role.Checkbox) { on ->
                        onSelected(if (on) selected + group.ruleId else selected - group.ruleId)
                    }
                    .testTag("source_${group.ruleId}"),
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(stringResource(RuleText.of(group.ruleId).group), style = MaterialTheme.typography.titleSmall)
                    Text(totalText(groupTotal), style = KeepClearTheme.type.number)
                }
            }
            if (thumbs.isNotEmpty()) ThumbStrip(thumbs, Modifier.padding(top = 8.dp), total = groupTotal.files)
            KcButton(
                stringResource(R.string.move_view),
                onClick = { actions.onOpenGroup(group.ruleId) },
                style = KcButtonStyle.Text,
                modifier = Modifier.testTag("view_${group.ruleId}"),
            )
        }
    }
}

/** A section title on a tab (heading semantics, F1b). */
@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { heading() },
    )
}

/** F1b: where KeepClear can back up to, set up under Settings › Advanced. */
class BackupPlaces(
    val networkHost: String? = null,
    val nextcloudHost: String? = null,
    val onSetUp: () -> Unit = {},
)

/** F1b: the backup places on the Backup tab: a drive is chosen when a backup starts; the others are set up. */
@Composable
private fun BackupPlacesSection(places: BackupPlaces) {
    Column(Modifier.fillMaxWidth().testTag("backupPlaces"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeading(stringResource(R.string.backup_places_title))
        Text(stringResource(R.string.backup_places_drive), style = MaterialTheme.typography.bodyMedium)
        places.networkHost?.let { host ->
            Text(
                stringResource(R.string.backup_place_network, Bidi.isolate(host)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("placeNetwork"),
            )
        }
        places.nextcloudHost?.let { host ->
            Text(
                stringResource(R.string.backup_place_nextcloud, Bidi.isolate(host)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("placeNextcloud"),
            )
        }
        KcButton(
            stringResource(R.string.backup_places_setup),
            onClick = places.onSetUp,
            style = KcButtonStyle.Text,
            modifier = Modifier.testTag("setUpPlaces"),
        )
    }
}

private fun ageLabel(age: MoveAge) = when (age) {
    MoveAge.OLDER_12 -> R.string.move_age_12
    MoveAge.OLDER_6 -> R.string.move_age_6
    MoveAge.ALL -> R.string.move_age_all
}

@Composable
private fun PhotosNote(onOpen: () -> Unit) {
    KcCard(Modifier.testTag("photosNote")) {
        Text(stringResource(R.string.move_photos_note), style = MaterialTheme.typography.bodyMedium)
        KcButton(stringResource(R.string.move_open_photos), onClick = onOpen, style = KcButtonStyle.Text)
    }
}

@Composable
private fun ProblemCard(problem: TargetProblem) {
    // Not red: nothing was lost; it says what to do next (red is only for permanent actions).
    KcCard(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.testTag("targetProblem"),
    ) {
        Text(
            stringResource(
                when (problem) {
                    TargetProblem.ON_THIS_PHONE -> R.string.move_problem_phone
                    TargetProblem.DAMAGED_MARKER -> R.string.move_problem_marker
                    TargetProblem.UNAVAILABLE -> R.string.move_problem_unavailable
                    TargetProblem.SELECTION_LOST -> R.string.move_problem_selection_lost
                    TargetProblem.REFUSED -> R.string.move_problem_refused
                    TargetProblem.UNREACHABLE -> R.string.move_problem_unreachable
                    TargetProblem.CERTIFICATE -> R.string.nextcloud_problem_certificate
                    TargetProblem.NEXTCLOUD_SIGN_IN -> R.string.move_problem_nextcloud_sign_in
                    TargetProblem.NEXTCLOUD_UNREACHABLE -> R.string.move_problem_nextcloud_unreachable
                    TargetProblem.NETWORK_ON_THIS_PHONE -> R.string.move_problem_network_phone
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FreeResultCard(r: FreeResult, onOpenTrash: () -> Unit, onRestore: () -> Unit) {
    val total = Total(r.done, r.bytes)
    val trashed = !r.simulated && !r.forGood && r.done > 0
    val detail = buildList {
        // Zero counts are not shown (review PR C).
        if (trashed) add(stringResource(R.string.clean_done_space, trashDate()))
        if (r.skipped > 0) add(countSentence(R.plurals.move_free_skipped, r.skipped))
        if (r.failed > 0) add(countSentence(R.plurals.move_free_failed, r.failed))
        if (r.interrupted) add(stringResource(R.string.move_free_interrupted, Bidi.isolate(r.targetLabel)))
        // With the measured headline, where the copy is goes to the detail line.
        if (r.measuredFreed != null && r.forGood) {
            add(stringResource(R.string.move_free_done_for_good, totalText(total), Bidi.isolate(r.targetLabel)))
        }
    }.joinToString("\n").ifEmpty { null }
    // Owner decision P1: deleted for good, the undo is the checked copy ("Restore from a backup");
    // in the trash, Empty now.
    val restorable = r.forGood && !r.simulated && r.done > 0
    Box(Modifier.fillMaxWidth().testTag("freeResult")) {
        ResultBanner(
            message = when {
                r.simulated -> {
                    stringResource(R.string.test_mode_would_free, totalText(total))
                }

                // Owner brief H1 item 7: measured, like the clean result ("Freed 31.0 GB · now 47.8 GB free").
                r.measuredFreed != null && r.nowFree != null -> {
                    val locale = LocalConfiguration.current.locales[0]
                    stringResource(
                        R.string.result_freed_now,
                        Bidi.isolate(ByteSize.format(r.measuredFreed, locale)),
                        Bidi.isolate(ByteSize.format(r.nowFree, locale)),
                    )
                }

                r.forGood -> {
                    stringResource(
                        R.string.move_free_done_for_good,
                        totalText(total),
                        Bidi.isolate(r.targetLabel),
                    )
                }

                else -> {
                    stringResource(R.string.move_free_done, totalText(total))
                }
            },
            detail = detail,
            actionText = when {
                restorable -> stringResource(R.string.restore_start)
                trashed -> stringResource(R.string.clean_empty_now)
                else -> null
            },
            onAction = when {
                restorable -> onRestore
                trashed -> onOpenTrash
                else -> null
            },
            actionModifier = Modifier.testTag(if (restorable) "freeRestore" else "freeEmptyNow"),
            modifier = Modifier.testTag(if (r.simulated) "simulatedResult" else "freeBanner"),
        )
    }
}

/** The clock for times and dates worked out on screen; the screenshot tests pin it so the images never drift (F3). */
val LocalClock = androidx.compose.runtime.staticCompositionLocalOf<() -> Long> { { System.currentTimeMillis() } }

/** When something trashed now is purged (TRASH-2), for "the space comes back on …". */
@Composable
fun trashDate(): String {
    val locale = LocalConfiguration.current.locales[0]
    val date = java.time.Instant.ofEpochMilli(LocalClock.current()).atZone(java.time.ZoneId.systemDefault())
        .toLocalDate().plusDays(30)
    val format = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
    return Bidi.isolate(date.format(format.withLocale(locale)))
}

@Composable
private fun JobCard(
    view: JobView,
    running: Boolean,
    canResume: Boolean,
    actions: MoveActions,
    onFree: () -> Unit,
    onStop: () -> Unit = actions.onStop,
) {
    val r = view.receipt
    when {
        running -> {
            KcCard(Modifier.testTag("jobRunning")) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.backup_running_to, Bidi.isolate(view.job.targetLabel)),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    // Owner brief H1 item 4: backup mode (Ente): what the user may and shouldn't do meanwhile.
                    Text(
                        stringResource(if (view.network) R.string.backup_mode_network else R.string.backup_mode_drive),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("backupMode"),
                    )
                    // The phase as a small label; ONE progress line with the time left.
                    Text(
                        stringResource(
                            if (view.phase ==
                                MovePhase.CHECKING
                            ) {
                                R.string.move_phase_checking
                            } else {
                                R.string.move_phase_copying
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("movePhase"),
                    )
                    Text(
                        progressLine(view.doneBytes, view.totalBytes, view.etaMillis),
                        style = KeepClearTheme.type.number,
                        modifier = Modifier.testTag("moveProgress"),
                    )
                    // Owner decision P3: waiting for the charger (the service keeps the job and goes on by itself).
                    if (view.job.state == JobState.PAUSED && view.job.pauseReason == PauseReason.BATTERY_LOW) {
                        Text(
                            stringResource(R.string.move_paused_battery),
                            modifier = Modifier.testTag("moveBatteryWait"),
                        )
                    }
                    ProgressAnnouncer(view.doneBytes, view.totalBytes)
                    LinearProgressIndicator(
                        progress = { if (view.totalBytes == 0L) 0f else view.doneBytes.toFloat() / view.totalBytes },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KcButton(
                            stringResource(R.string.move_pause),
                            onClick = actions.onPause,
                            style = KcButtonStyle.Tonal,
                            modifier = Modifier.testTag("pause"),
                        )
                        KcButton(
                            stringResource(R.string.move_stop),
                            onClick = onStop,
                            style = KcButtonStyle.Text,
                            modifier = Modifier.testTag("stop"),
                        )
                    }
                    Text(stringResource(R.string.move_pause_stop_help), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        view.job.state == JobState.DONE -> {
            ReceiptCard(
                view,
                onFree = onFree,
                onShare = { actions.onShare(view.job.id) },
                onCheck = { actions.onCheck(view.job.id) },
            )
        }

        else -> {
            KcCard(Modifier.testTag("jobPaused")) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Owner brief H1 item 8: a specific message and one button (Signal's pattern).
                    Text(pauseText(view), modifier = Modifier.testTag("pauseText"))
                    Text(totalText(Total(r.verified, r.verifiedBytes)), style = KeepClearTheme.type.number)
                    if (pauseHasButton(view.job.pauseReason)) {
                        KcButton(
                            stringResource(R.string.move_continue),
                            onClick = { actions.onResume(view.job.id) },
                            enabled = canResume,
                            modifier = Modifier.testTag("resume"),
                        )
                    }
                }
            }
        }
    }
}

/** Owner brief H1 item 8: a pause for the battery goes on by itself (no button); every other pause has one. */
internal fun pauseHasButton(reason: PauseReason?): Boolean = reason != PauseReason.BATTERY_LOW

@Composable
private fun pauseText(view: JobView): String {
    val locale = LocalConfiguration.current.locales[0]

    fun size(b: Long) = Bidi.isolate(ByteSize.format(b, locale))
    return when (view.job.pauseReason) {
        // "Not enough space on the USB drive: 4.1 GB needed, 2.3 GB free".
        PauseReason.TARGET_FULL -> view.targetFreeBytes?.let { free ->
            stringResource(R.string.pause_full_sizes, size(view.remainingBytes), size(free))
        } ?: stringResource(R.string.pause_full_needed, size(view.remainingBytes))

        else -> pauseText(view.job.pauseReason, view.job.targetLabel, view.network)
    }
}

@Composable
private fun pauseText(reason: PauseReason?, label: String, network: Boolean): String = when (reason) {
    PauseReason.TARGET_DISCONNECTED -> stringResource(
        if (network) R.string.move_paused_disconnected else R.string.pause_unplugged,
    )

    PauseReason.STOPPED_BY_PHONE -> stringResource(R.string.pause_phone_stopped)

    PauseReason.TARGET_FULL -> stringResource(R.string.move_paused_full)

    PauseReason.ACCESS_LOST -> stringResource(R.string.move_paused_access)

    PauseReason.PHONE_LOW_SPACE -> stringResource(R.string.move_paused_phone_space)

    PauseReason.CANCELLED -> stringResource(R.string.move_paused_stopped)

    PauseReason.SYSTEM_TIME_LIMIT -> stringResource(R.string.move_paused_time)

    PauseReason.INTERRUPTED -> stringResource(R.string.move_paused_interrupted)

    PauseReason.WRONG_TARGET -> stringResource(R.string.move_paused_wrong, Bidi.isolate(label))

    PauseReason.UNEXPECTED_ERROR -> stringResource(R.string.move_paused_unexpected)

    PauseReason.BATTERY_LOW -> stringResource(R.string.move_paused_battery)

    PauseReason.PAUSED_BY_USER, null -> stringResource(R.string.move_paused_user)
}

/** MOVE-9 / MOVE-18 / Q12: what was copied and checked, what wasn't and why, and how to get files back. */
@Composable
fun ReceiptCard(view: JobView, onFree: () -> Unit, onShare: () -> Unit, onCheck: () -> Unit = {}) {
    val r = view.receipt
    // F2: the backup summary as a "paper slip" (design-system.md §8.5) with the VerifiedStamp as its proof.
    PaperSlip(Modifier.testTag("receipt")) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.move_receipt_title, Bidi.isolate(r.targetLabel)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                totalText(Total(r.verified, r.verifiedBytes)),
                style = KeepClearTheme.type.number,
                modifier = Modifier.testTag("receiptTotal"),
            )
            // Owner brief H1 item 6: the stamp only once the second check has passed for every copy; before it,
            // "Copied ✓ · Last step: check".
            if (receiptStamped(view)) {
                VerifiedStamp(
                    stringResource(R.string.receipt_stamp),
                    Modifier.padding(vertical = 8.dp).testTag("verifiedStamp"),
                )
            } else if (view.needsCheck) {
                Text(
                    stringResource(R.string.receipt_copied_next_check),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("copiedNextCheck"),
                )
            }
            TearLine()
            // M17b: the server itself checked these copies (ADR 0011); plain words here, how under Details.
            val byNextcloud = OpenedTarget.isNextcloud(view.job.targetRef) && r.checkedByServer > 0
            val allByNextcloud = byNextcloud && r.checkedByServer == r.verified
            Text(
                countSentence(
                    if (allByNextcloud) R.plurals.move_receipt_verified_nextcloud else R.plurals.move_receipt_verified,
                    r.verified,
                    sizeText(r.verifiedBytes),
                ),
                modifier = Modifier.testTag("receiptVerified"),
            )
            // Owner brief H1 item 8: a partial result is one line and "Show", which opens the list.
            val errors = r.failed.values.sum() + r.changed
            var showErrors by rememberSaveable(view.job.id) { mutableStateOf(false) }
            if (errors > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("errorsLine")) {
                    Text(
                        countSentence(R.plurals.backup_errors, errors),
                        modifier = Modifier.weight(1f),
                    )
                    KcButton(
                        stringResource(if (showErrors) R.string.backup_errors_hide else R.string.backup_errors_show),
                        onClick = { showErrors = !showErrors },
                        style = KcButtonStyle.Text,
                        modifier = Modifier.testTag("errorsShow"),
                    )
                }
            }
            if (showErrors) {
                r.failed.forEach { (reason, count) ->
                    Text(countSentence(failText(reason), count), modifier = Modifier.testTag("failed_${reason.name}"))
                }
                if (r.changed > 0) Text(countSentence(R.plurals.move_receipt_changed, r.changed))
                view.failedFiles.forEach { f ->
                    Text(
                        Bidi.isolate(f.path.substringAfterLast('/')),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("failedFile"),
                    )
                }
            }
            if (r.unverified > 0) {
                Text(
                    countSentence(R.plurals.move_receipt_unverified, r.unverified),
                    modifier = Modifier.testTag("receiptUnverified"),
                )
            }
            if (byNextcloud && !allByNextcloud) {
                Text(
                    countSentence(R.plurals.move_receipt_nextcloud, r.checkedByServer),
                    modifier = Modifier.testTag("receiptNextcloud"),
                )
            }
            if (r.pending > 0) Text(countSentence(R.plurals.move_receipt_pending, r.pending))
            if (r.namesChanged > 0) Text(countSentence(R.plurals.move_receipt_names, r.namesChanged))
            Text(
                stringResource(R.string.move_receipt_how_back, Bidi.isolate(r.targetLabel), Bidi.isolateLtr(r.rootRel)),
                modifier = Modifier.testTag("howBack"),
            )
            // P-2: developer checks live under Details, not on the default path.
            var details by remember { mutableStateOf(false) }
            KcButton(
                stringResource(R.string.details),
                onClick = { details = !details },
                style = KcButtonStyle.Text,
                modifier = Modifier.testTag("receiptDetails"),
            )
            if (details && byNextcloud) {
                Text(
                    stringResource(R.string.details_nextcloud_check),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("detailsNextcloud"),
                )
            }
            if (details) {
                Text(
                    stringResource(R.string.details_check_pc),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("detailsCheckPc"),
                )
                // Owner brief H1 item 6: under Details, in neutral words.
                Text(
                    stringResource(R.string.details_unencrypted),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("unencrypted"),
                )
            }
            if (view.needsCheck) {
                // H1: a read right after copying may come from the phone's memory; one more uncached check first.
                Text(stringResource(R.string.move_check_needed), modifier = Modifier.testTag("checkNeeded"))
                KcButton(
                    stringResource(R.string.move_check_button),
                    onClick = onCheck,
                    icon = KcIcons.Verified,
                    modifier = Modifier.fillMaxWidth().testTag("checkBackup"),
                )
            }
            when {
                // Design QA item 4: the size in the button („לפנות 31.0GB מהטלפון”), the count as a line under it.
                view.canFree -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val locale = LocalConfiguration.current.locales[0]
                    KcButton(
                        stringResource(
                            R.string.move_free_button,
                            Bidi.isolate(
                                io.github.tomerar.keepclear.core.common.ByteSize.format(r.confirmedBytes, locale),
                            ),
                        ),
                        onClick = onFree,
                        modifier = Modifier.fillMaxWidth().testTag("freeOriginals"),
                    )
                    Text(
                        Bidi.isolate(
                            pluralStringResource(
                                R.plurals.file_count,
                                r.confirmed,
                                java.text.NumberFormat.getIntegerInstance(locale).format(r.confirmed),
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp).testTag("freeOriginalsCount"),
                    )
                }

                // D3: a guest network drive: copies only, said plainly.
                view.copyOnly -> Text(
                    stringResource(R.string.move_free_copy_only),
                    modifier = Modifier.testTag("freeCopyOnly"),
                )

                !view.canReadBack -> Text(
                    stringResource(R.string.move_free_unavailable),
                    modifier = Modifier.testTag("freeUnavailable"),
                )
            }
            KcButton(
                stringResource(R.string.move_share),
                onClick = onShare,
                style = KcButtonStyle.Text,
                modifier = Modifier.testTag("shareReceipt"),
            )
        }
    }
}

/** Owner brief H1 item 6: the receipt is stamped only after the second check passed for every copy. */
internal fun receiptStamped(view: JobView): Boolean =
    view.receipt.confirmed > 0 && view.receipt.confirmed == view.receipt.verified && !view.needsCheck

/** Owner brief H1 item 4: "12.9 of 31.0GB · about 41 minutes left". */
@Composable
internal fun progressLine(doneBytes: Long, totalBytes: Long, etaMillis: Long?): String {
    val locale = LocalConfiguration.current.locales[0]
    val (done, total) = ByteSize.progress(doneBytes, totalBytes, locale)
    return if (etaMillis == null) {
        stringResource(R.string.move_progress_bytes, done, total)
    } else {
        stringResource(R.string.backup_progress_eta, done, total, durationText(etaMillis))
    }
}

/**
 * Owner brief H1 item 5: TalkBack hears the progress in steps of about 10 % (a polite live region whose text changes
 * only at each step), never on every file.
 */
@Composable
private fun ProgressAnnouncer(doneBytes: Long, totalBytes: Long) {
    val locale = LocalConfiguration.current.locales[0]
    val step = announceStep(doneBytes, totalBytes)
    val text = stringResource(
        R.string.backup_progress_announce,
        java.text.NumberFormat.getPercentInstance(locale).format(step / 10.0),
    )
    Box(
        Modifier.semantics {
            liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            contentDescription = text
        }.testTag("progressAnnounce"),
    )
}

/** H1 / MOVE-15: the steps of "Check backup". */
@Composable
private fun CheckCard(check: BackupCheck, label: String, actions: MoveActions) {
    val drive = Bidi.isolate(label)
    KcCard(Modifier.testTag("checkCard")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (check) {
                // Owner brief H1 item 6: live states from the volume source: unplug → "Unplugged ✓ — plug it back
                // in" → "Checking…" → the stamp.
                is BackupCheck.Unplug -> {
                    Text(
                        stringResource(R.string.replug_unplug),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("checkUnplug"),
                    )
                    Text(stringResource(R.string.move_check_unplug, drive), style = MaterialTheme.typography.bodySmall)
                }

                is BackupCheck.PlugBack -> {
                    Text(
                        stringResource(R.string.replug_detected),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("checkPlugBack"),
                    )
                }

                is BackupCheck.Checking -> {
                    Text(
                        stringResource(R.string.replug_checking),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("checkChecking"),
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is BackupCheck.Done -> {
                    val clean = check.result.ok > 0 && check.result.damaged + check.result.missing == 0 &&
                        check.result.unproven == 0
                    if (clean) {
                        VerifiedStamp(
                            stringResource(R.string.receipt_stamp),
                            Modifier.padding(vertical = 4.dp).testTag("checkStamp"),
                        )
                    }
                    Text(
                        countSentence(R.plurals.move_check_ok, check.result.ok),
                        modifier = Modifier.testTag("checkOk"),
                    )
                    val bad = check.result.damaged + check.result.missing
                    if (bad > 0) {
                        Text(countSentence(R.plurals.move_check_bad, bad), modifier = Modifier.testTag("checkBad"))
                    }
                    if (check.result.unproven > 0) {
                        Text(
                            countSentence(R.plurals.move_check_unproven, check.result.unproven),
                            modifier = Modifier.testTag("checkUnproven"),
                        )
                    }
                }

                is BackupCheck.TimedOut -> {
                    Text(
                        stringResource(R.string.move_check_timeout),
                        modifier = Modifier.testTag("checkTimeout"),
                    )
                }

                is BackupCheck.Interrupted -> {
                    Text(
                        stringResource(R.string.move_check_interrupted),
                        modifier = Modifier.testTag("checkInterrupted"),
                    )
                }
            }
            if (check is BackupCheck.Unplug || check is BackupCheck.PlugBack) {
                Text(stringResource(R.string.move_check_why), style = MaterialTheme.typography.bodySmall)
            }
            KcButton(
                stringResource(R.string.move_check_close),
                onClick = actions.onCancelCheck,
                style = KcButtonStyle.Text,
                modifier = Modifier.testTag("checkClose"),
            )
        }
    }
}

private fun failText(reason: FailReason) = when (reason) {
    FailReason.SOURCE_MISSING -> R.plurals.move_receipt_failed_missing
    FailReason.SOURCE_UNREADABLE -> R.plurals.move_receipt_failed_unreadable
    FailReason.TOO_LARGE_FOR_TARGET -> R.plurals.move_receipt_failed_too_large
    FailReason.HASH_MISMATCH -> R.plurals.move_receipt_failed_hash
    FailReason.TARGET_ERROR -> R.plurals.move_receipt_failed_target
}

/** "2 hours", "15 minutes" in the user's language (review PR C). */
@Composable
fun durationText(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(1)
    return if (minutes >= 90) {
        countSentence(R.plurals.duration_hours, ((minutes + 30) / 60).toInt())
    } else {
        countSentence(R.plurals.duration_minutes, minutes.toInt())
    }
}

/**
 * Review PR C: two plain steps before Android's folder picker, and a check that a drive is attached. F2 (C76): the
 * picker offers the USB drive or memory card and the places already set up under Settings › Advanced, nothing else;
 * setting up a computer at home or a Nextcloud happens there.
 */
@Composable
private fun PickGuideDialog(
    drive: RemovableDrive?,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    networkHost: String? = null,
    onNetwork: () -> Unit = {},
    nextcloudHost: String? = null,
    onNextcloud: () -> Unit = {},
) {
    InfoSheet(
        title = stringResource(R.string.move_guide_title),
        dismissText = stringResource(R.string.move_cancel),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("pickGuide"),
        primaryButton = {
            // Owner brief H1 item 3: Android's picker opens right at the drive found (MainActivity).
            KcButton(
                stringResource(R.string.move_guide_continue),
                onClick = onContinue,
                enabled = drive != null,
                modifier = Modifier.fillMaxWidth().testTag("guideContinue"),
            )
        },
    ) {
        // Live: the sheet changes the moment a drive is plugged in or pulled out.
        if (drive != null) {
            Text(
                stringResource(R.string.drive_found, Bidi.isolate(drive.label)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("driveFound"),
            )
            Text(stringResource(R.string.drive_found_hint), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                stringResource(R.string.drive_connect),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("noDrive"),
            )
            Text(stringResource(R.string.drive_connect_live), style = MaterialTheme.typography.bodyMedium)
        }
        // Advisory only: other places (a cloud folder: copy-only, MOVE-14) stay reachable.
        KcButton(
            stringResource(R.string.move_guide_anyway),
            onClick = onContinue,
            style = KcButtonStyle.Text,
            modifier = Modifier.testTag("guideAnyway"),
        )
        // M15 / M17b: only places set up under Settings › Advanced.
        networkHost?.let { host ->
            KcButton(
                stringResource(R.string.move_network_saved_option, Bidi.isolate(host)),
                onClick = onNetwork,
                style = KcButtonStyle.Tonal,
                modifier = Modifier.fillMaxWidth().testTag("useNetwork"),
            )
        }
        nextcloudHost?.let { host ->
            KcButton(
                stringResource(R.string.move_nextcloud_option, Bidi.isolate(host)),
                onClick = onNextcloud,
                style = KcButtonStyle.Tonal,
                modifier = Modifier.fillMaxWidth().testTag("useNextcloud"),
            )
        }
    }
}

/** F2: the backup summary's paper: the lightest surface, the card's corners (design-system.md §8.5). */
@Composable
internal fun PaperSlip(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    KcCard(modifier, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, content = content)
}

/** A dashed "tear" line across the slip (decorative). */
@Composable
private fun TearLine() {
    val color = MaterialTheme.colorScheme.outlineVariant
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)) {
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
            strokeWidth = 2f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
        )
    }
}

@Composable
private fun PickedDialog(picked: PickedTarget, actions: MoveActions) {
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(
        onDismissRequest = actions.onCancelPick,
        title = { Text(stringResource(R.string.move_target_title, Bidi.isolate(picked.label))) },
        text = {
            // Review PR C: scrollable checklist; blocking vs notes by symbol and words, not colour only.
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("preflight").verticalScroll(rememberScrollState()),
            ) {
                val files = picked.files.size
                val bytes = picked.files.sumOf { it.sourceSize }
                Text(totalText(Total(files, bytes)))
                when (picked.kind) {
                    TargetKind.CLOUD_COPY_ONLY -> {
                        Text(stringResource(R.string.move_target_cloud))
                    }

                    TargetKind.UNKNOWN_PROVIDER -> {
                        Text(stringResource(R.string.move_target_unknown))
                    }

                    TargetKind.REMOVABLE, TargetKind.NETWORK -> {}
                }
                picked.issues.forEach { issue ->
                    val text = when (issue) {
                        is MovePlanner.Issue.NotEnoughSpace -> stringResource(
                            R.string.move_issue_space,
                            Bidi.isolate(ByteSize.format(issue.needed, locale)),
                            Bidi.isolate(ByteSize.format(issue.free, locale)),
                        )

                        is MovePlanner.Issue.TooLargeForTarget -> countSentence(
                            if (picked.format == io.github.tomerar.keepclear.backup.saf.DriveFormat.UNKNOWN) {
                                R.plurals.move_issue_too_large_unknown
                            } else {
                                R.plurals.move_issue_too_large
                            },
                            issue.files.size,
                        )

                        MovePlanner.Issue.BatteryLow -> stringResource(R.string.move_issue_battery)

                        MovePlanner.Issue.BatteryWarning -> stringResource(R.string.move_issue_battery_warning)

                        is MovePlanner.Issue.NamesChanged -> countSentence(R.plurals.move_issue_names, issue.count)

                        MovePlanner.Issue.CannotVerify -> stringResource(R.string.move_issue_cannot_verify)

                        MovePlanner.Issue.CopyOnly -> stringResource(R.string.move_issue_copy_only)

                        MovePlanner.Issue.TargetIsSource -> stringResource(R.string.move_problem_phone)
                    }
                    val label = stringResource(
                        if (issue.blocking) R.string.move_preflight_blocks else R.string.move_preflight_note,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val colors = MaterialTheme.colorScheme
                        Text(
                            if (issue.blocking) "✕" else "!",
                            color = if (issue.blocking) colors.error else colors.onSurface,
                        )
                        Text(
                            "$label: $text",
                            modifier = Modifier.testTag(if (issue.blocking) "preflightBlock" else "preflightNote"),
                        )
                    }
                }
                if (!picked.blocked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✓", color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.move_preflight_ok), modifier = Modifier.testTag("preflightOk"))
                    }
                }
            }
        },
        confirmButton = {
            KcButton(
                stringResource(R.string.move_start),
                onClick = actions.onStart,
                enabled = !picked.blocked,
                modifier = Modifier.testTag("startCopy"),
            )
        },
        dismissButton = {
            KcButton(stringResource(R.string.move_cancel), actions.onCancelPick, style = KcButtonStyle.Text)
        },
    )
}

/** M15: where the network drive is. Only a plain address is accepted (ADR 0011: only to the address typed). */
@Composable
internal fun NetworkDriveDialog(
    saved: io.github.tomerar.keepclear.backup.smb.SmbLocation?,
    onConnect: (io.github.tomerar.keepclear.backup.smb.SmbLocation) -> Unit,
    onDismiss: () -> Unit,
    testAccountNotice: Boolean = TestKey.showNotice,
) {
    var host by rememberSaveable { mutableStateOf(saved?.host.orEmpty()) }
    var share by rememberSaveable { mutableStateOf(saved?.share.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(saved?.user.orEmpty()) }
    // The password is never kept in saved instance state.
    var password by remember { mutableStateOf(saved?.password.orEmpty()) }
    val plainHost = io.github.tomerar.keepclear.backup.smb.SmbHosts.isPlainHost(host.trim())
    // D3: never the phone itself (its own addresses are refused when connecting, with the same plain line).
    val thisPhone = plainHost && io.github.tomerar.keepclear.backup.smb.SmbHosts.isThisPhone(host.trim())
    val hostOk = plainHost && !thisPhone
    // D3 / PR #44 review: the same rule the drive applies (no `..`, `:`, separators or control characters).
    val shareOk = io.github.tomerar.keepclear.backup.smb.SmbPaths.isSafeSegment(share.trim())
    val shareBad = share.isNotBlank() && !shareOk
    // Anything else the drive refuses is shown as a plain line, never a crash.
    var refused by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_network_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("networkDrive").verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.move_network_intro), style = MaterialTheme.typography.bodyMedium)
                // L6: test builds signed with the public test key: anyone can make an update that reads this.
                if (testAccountNotice) TestAccountNotice()
                if (user.isBlank()) {
                    // D3: without a name and password the session can't be signed: copies only.
                    Text(
                        stringResource(R.string.move_network_guest_copy_only),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("networkGuestNote"),
                    )
                }
                androidx.compose.material3.OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.move_network_host)) },
                    singleLine = true,
                    isError = host.isNotBlank() && !hostOk,
                    supportingText = when {
                        thisPhone -> {
                            { Text(stringResource(R.string.move_network_this_phone)) }
                        }

                        host.isNotBlank() && !hostOk -> {
                            { Text(stringResource(R.string.move_network_bad_host)) }
                        }

                        else -> {
                            null
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("networkHost"),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = share,
                    onValueChange = {
                        share = it
                        refused = false
                    },
                    label = { Text(stringResource(R.string.move_network_share)) },
                    singleLine = true,
                    isError = shareBad,
                    supportingText = if (shareBad) {
                        { Text(stringResource(R.string.move_network_bad_share)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("networkShare"),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.move_network_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("networkUser"),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.move_network_password)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("networkPassword"),
                )
                if (refused) {
                    Text(
                        stringResource(R.string.move_network_not_accepted),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("networkRefused"),
                    )
                }
            }
        },
        confirmButton = {
            KcButton(
                stringResource(R.string.move_network_connect),
                onClick = {
                    val location = try {
                        io.github.tomerar.keepclear.backup.smb.SmbLocation(
                            host = host.trim(),
                            share = share.trim(),
                            user = user.trim(),
                            password = password,
                        )
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    if (location == null) refused = true else onConnect(location)
                },
                enabled = hostOk && shareOk,
                modifier = Modifier.testTag("networkConnect"),
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.move_cancel)) } },
    )
}

/** E3: what runs in the background right now, and how far it got. */
@Composable
internal fun TaskProgressText(task: TaskProgress) {
    val title = stringResource(
        when (task.kind) {
            TaskKind.FREE -> R.string.task_notification_free
            TaskKind.CHECK -> R.string.task_notification_check
            TaskKind.RESTORE -> R.string.task_notification_restore
        },
    )
    val line = when {
        // Owner decision P1: "Deleted 400 of 6,830" while freeing.
        task.freeMode != null && task.total > 0 -> freeProgressLine(task)

        task.total > 0 -> "$title " + pluralStringResource(R.plurals.task_progress, task.total, task.done, task.total)

        else -> title
    }
    Text(line, modifier = Modifier.testTag("taskProgress"))
}

/** Owner decision P1: the free's progress in plain words ("Deleted 400 of 6,830"). */
@Composable
private fun freeProgressLine(task: TaskProgress): String = when (task.freeMode) {
    FreeMode.FOR_GOOD -> stringResource(R.string.free_progress_deleted, countText(task.freed), countText(task.total))
    FreeMode.TRASH -> stringResource(R.string.free_progress_trash, countText(task.freed), countText(task.total))
    FreeMode.TEST, null -> stringResource(R.string.free_progress_test, countText(task.done), countText(task.total))
}
