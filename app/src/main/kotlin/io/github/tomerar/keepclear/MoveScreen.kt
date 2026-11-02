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
import androidx.compose.ui.semantics.heading
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
    /** Whether a USB drive or memory card is mounted now (checked before Android's folder picker). */
    val hasRemovableDrive: () -> Boolean = { true },
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
        hasRemovableDrive = hasRemovableDrive,
        onOpenTrash = onOpenTrash,
        restore = restore,
    )
}

/** Items of [ruleIds] old enough for [age], never favourites (MOVE-1). */
fun moveSelection(
    summary: ClassificationSummary,
    ruleIds: Set<String>,
    age: MoveAge,
    now: Long,
): List<ClassifiedItem> {
    val cutoff = age.months?.let { now - it * 30L * 86_400_000L }
    return summary.groups(Category.MOVE).filter { it.ruleId in ruleIds }.flatMap { it.items }
        .filter { !it.item.isFavorite && (cutoff == null || it.item.mtimeMillis < cutoff) }
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
    var age by rememberSaveable { mutableStateOf(MoveAge.OLDER_12) }
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

    Scaffold(topBar = { if (onBack != null) SubScreenTopBar(stringResource(R.string.move_title), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("moveScreen"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onBack == null) item { SectionHeading(stringResource(R.string.move_title)) }
            if (notificationsOff && busy) {
                item { Text(stringResource(R.string.move_notif_warning), modifier = Modifier.testTag("notifWarning")) }
            }
            state.problem?.let { problem -> item { ProblemCard(problem) } }
            state.task?.let { task -> item { TaskProgressText(task) } }
            state.freeResult?.let { r -> item { FreeResultCard(r, actions.onOpenTrash) } }
            state.check?.let { check ->
                val label = state.jobs.firstOrNull { it.job.id == check.jobId }?.job?.targetLabel.orEmpty()
                item { CheckCard(check, label, actions) }
            }
            state.jobs.firstOrNull()?.let { latest ->
                item {
                    JobCard(
                        latest,
                        running = state.running == latest.job.id,
                        canResume = !busy && !taskRunning,
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
            } else {
                moveSources(summary, groups, selected, { selected = it }, age, { age = it }, now, actions)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.move_selected, totalText(total)),
                            style = KeepClearTheme.type.number,
                            modifier = Modifier.testTag("moveSelected"),
                        )
                        KcButton(
                            stringResource(R.string.move_pick_target),
                            onClick = { guide = true },
                            enabled = items.isNotEmpty() && !busy && !state.busy && !taskRunning,
                            icon = KcIcons.Move,
                            modifier = Modifier.fillMaxWidth().testTag("pickTarget"),
                        )
                        // F2: a pre-flight being prepared shows progress, not only a disabled button.
                        if (state.busy) BusyLine()
                    }
                }
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
                        canResume = !busy && !taskRunning,
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
            hasDrive = actions.hasRemovableDrive,
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
        val freeing = Total(view.receipt.confirmed, view.receipt.confirmedBytes)
        ConfirmSheet(
            title = stringResource(R.string.move_free_confirm_title),
            dismissText = stringResource(R.string.move_cancel),
            onDismiss = { confirmFree = null },
            facts = confirmFacts(freeing, stringResource(R.string.confirm_where_trash)),
            note = stringResource(R.string.move_free_confirm_body, totalText(freeing)),
            confirmButton = {
                // Recoverable (30-day trash): not styled as destructive (review PR C).
                SafeConfirmButton(
                    stringResource(R.string.move_free_confirm_yes),
                    onClick = {
                        confirmFree = null
                        actions.onFree(view.job.id, userConfirmed(view.freeablePaths))
                    },
                    modifier = Modifier.fillMaxWidth().testTag("confirmFree"),
                    permanent = false,
                )
            },
        )
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
private fun FreeResultCard(r: FreeResult, onOpenTrash: () -> Unit) {
    val total = Total(r.done, r.bytes)
    val detail = buildList {
        // Zero counts are not shown (review PR C).
        if (!r.simulated && r.done > 0) add(stringResource(R.string.clean_done_space, trashDate()))
        if (r.skipped > 0) add(countSentence(R.plurals.move_free_skipped, r.skipped))
        if (r.failed > 0) add(countSentence(R.plurals.move_free_failed, r.failed))
    }.joinToString("\n").ifEmpty { null }
    Box(Modifier.fillMaxWidth().testTag("freeResult")) {
        ResultBanner(
            message = if (r.simulated) {
                stringResource(R.string.test_mode_would_free, totalText(total))
            } else {
                stringResource(R.string.move_free_done, totalText(total))
            },
            detail = detail,
            actionText = stringResource(R.string.clean_empty_now).takeIf { !r.simulated && r.done > 0 },
            onAction = onOpenTrash.takeIf { !r.simulated && r.done > 0 },
            actionModifier = Modifier.testTag("freeEmptyNow"),
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
                    val locale = LocalConfiguration.current.locales[0]
                    val nf = NumberFormat.getIntegerInstance(locale)
                    Text(
                        stringResource(
                            R.string.move_running,
                            Bidi.isolate(view.job.targetLabel),
                            Bidi.isolate(nf.format(r.verified)),
                            Bidi.isolate(nf.format(r.total)),
                        ),
                    )
                    // Review PR C: phase, bytes and time left, not just a file count.
                    val phase = stringResource(
                        if (view.phase ==
                            MovePhase.CHECKING
                        ) {
                            R.string.move_phase_checking
                        } else {
                            R.string.move_phase_copying
                        },
                    )
                    val bytes = stringResource(
                        R.string.move_progress_bytes,
                        sizeText(view.doneBytes),
                        sizeText(view.totalBytes),
                    )
                    Text(
                        "$phase · $bytes",
                        style = KeepClearTheme.type.number,
                        modifier = Modifier.testTag("moveProgress"),
                    )
                    view.etaMillis?.let { eta ->
                        Text(
                            stringResource(R.string.move_time_left, durationText(eta)),
                            modifier = Modifier.testTag("moveEta"),
                        )
                    }
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
                    Text(pauseText(view.job.pauseReason, view.job.targetLabel))
                    Text(totalText(Total(r.verified, r.verifiedBytes)), style = KeepClearTheme.type.number)
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

@Composable
private fun pauseText(reason: PauseReason?, label: String): String = when (reason) {
    PauseReason.TARGET_DISCONNECTED -> stringResource(R.string.move_paused_disconnected)
    PauseReason.TARGET_FULL -> stringResource(R.string.move_paused_full)
    PauseReason.ACCESS_LOST -> stringResource(R.string.move_paused_access)
    PauseReason.PHONE_LOW_SPACE -> stringResource(R.string.move_paused_phone_space)
    PauseReason.CANCELLED -> stringResource(R.string.move_paused_stopped)
    PauseReason.SYSTEM_TIME_LIMIT -> stringResource(R.string.move_paused_time)
    PauseReason.INTERRUPTED -> stringResource(R.string.move_paused_interrupted)
    PauseReason.WRONG_TARGET -> stringResource(R.string.move_paused_wrong, Bidi.isolate(label))
    PauseReason.UNEXPECTED_ERROR -> stringResource(R.string.move_paused_unexpected)
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
            if (r.verified > 0) {
                VerifiedStamp(
                    stringResource(R.string.receipt_stamp),
                    Modifier.padding(vertical = 8.dp).testTag("verifiedStamp"),
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
            r.failed.forEach { (reason, count) ->
                Text(countSentence(failText(reason), count), modifier = Modifier.testTag("failed_${reason.name}"))
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
            if (r.changed > 0) Text(countSentence(R.plurals.move_receipt_changed, r.changed))
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
            }
            Text(
                stringResource(R.string.move_receipt_unencrypted),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("unencrypted"),
            )
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

/** H1 / MOVE-15: the steps of "Check backup". */
@Composable
private fun CheckCard(check: BackupCheck, label: String, actions: MoveActions) {
    val drive = Bidi.isolate(label)
    KcCard(Modifier.testTag("checkCard")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (check) {
                is BackupCheck.Unplug -> {
                    Text(
                        stringResource(R.string.move_check_unplug, drive),
                        modifier = Modifier.testTag("checkUnplug"),
                    )
                }

                is BackupCheck.PlugBack -> {
                    Text(
                        stringResource(R.string.move_check_plug_back, drive),
                        modifier = Modifier.testTag("checkPlugBack"),
                    )
                }

                is BackupCheck.Checking -> {
                    Text(stringResource(R.string.move_check_checking))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is BackupCheck.Done -> {
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
    hasDrive: () -> Boolean,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    networkHost: String? = null,
    onNetwork: () -> Unit = {},
    nextcloudHost: String? = null,
    onNextcloud: () -> Unit = {},
) {
    var noDrive by remember { mutableStateOf(false) }
    InfoSheet(
        title = stringResource(R.string.move_guide_title),
        dismissText = stringResource(R.string.move_cancel),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("pickGuide"),
        primaryButton = {
            KcButton(
                stringResource(R.string.move_guide_continue),
                onClick = { if (hasDrive()) onContinue() else noDrive = true },
                modifier = Modifier.fillMaxWidth().testTag("guideContinue"),
            )
        },
    ) {
        Text(stringResource(R.string.move_guide_1), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.move_guide_2), style = MaterialTheme.typography.bodyMedium)
        if (noDrive) {
            Text(
                stringResource(R.string.move_guide_no_drive),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("noDrive"),
            )
            // Advisory only: other places (a cloud folder: copy-only, MOVE-14) stay reachable.
            KcButton(
                stringResource(R.string.move_guide_anyway),
                onClick = onContinue,
                style = KcButtonStyle.Text,
                modifier = Modifier.testTag("guideAnyway"),
            )
        }
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
    val line = if (task.total > 0) {
        "$title " + pluralStringResource(R.plurals.task_progress, task.total, task.done, task.total)
    } else {
        title
    }
    Text(line, modifier = Modifier.testTag("taskProgress"))
}
