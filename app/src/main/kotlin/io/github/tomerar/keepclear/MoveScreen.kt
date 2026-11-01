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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.backup.saf.TargetKind
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
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
) {
    /** The same actions with [open] for "Show group": nothing else can be lost on the way. */
    fun withOpenGroup(open: (String) -> Unit) = MoveActions(
        onPickTarget = onPickTarget,
        onNetworkDrive = onNetworkDrive,
        savedNetworkDrive = savedNetworkDrive,
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
    summary: ClassificationSummary,
    state: MoveUiState,
    now: Long,
    notificationsOff: Boolean,
    actions: MoveActions,
    onBack: () -> Unit,
) {
    val groups = summary.groups(Category.MOVE)
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var age by rememberSaveable { mutableStateOf(MoveAge.OLDER_12) }
    var confirmFree by remember { mutableStateOf<JobView?>(null) }
    var guide by remember { mutableStateOf(false) }
    var network by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val items = moveSelection(summary, selected, age, now)
    val total = items.fold(Total()) { t, i -> t + i }
    val busy = state.running != null

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("moveScreen"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BackRow(stringResource(R.string.move_title), onBack) }
            if (notificationsOff && busy) {
                item { Text(stringResource(R.string.move_notif_warning), modifier = Modifier.testTag("notifWarning")) }
            }
            state.problem?.let { problem -> item { ProblemCard(problem) } }
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
                        canResume = !busy,
                        actions = actions,
                        onFree = { confirmFree = latest },
                        onStop = { confirmStop = true },
                    )
                }
            }
            item {
                Text(stringResource(R.string.move_what), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoveAge.entries.forEach { a ->
                        FilterChip(
                            selected = age == a,
                            onClick = { age = a },
                            label = { Text(stringResource(ageLabel(a))) },
                            modifier = Modifier.testTag("age_${a.name}"),
                        )
                    }
                }
                Text(stringResource(R.string.move_favourites_note), style = MaterialTheme.typography.bodySmall)
            }
            items(groups, key = { it.ruleId }) { group ->
                val groupTotal = moveSelection(summary, setOf(group.ruleId), age, now).fold(Total()) { t, i -> t + i }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = group.ruleId in selected,
                        onCheckedChange = { on ->
                            selected = if (on) selected + group.ruleId else selected - group.ruleId
                        },
                        modifier = Modifier.testTag("source_${group.ruleId}"),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(RuleText.of(group.ruleId).group))
                        Text(totalText(groupTotal), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { actions.onOpenGroup(group.ruleId) }) {
                        Text(stringResource(R.string.move_view))
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.move_selected, totalText(total)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("moveSelected"),
                )
                Button(
                    onClick = { guide = true },
                    enabled = items.isNotEmpty() && !busy && !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("pickTarget"),
                ) { Text(stringResource(R.string.move_pick_target)) }
            }
            item { PhotosNote(actions.onOpenGooglePhotos) }
            if (state.jobs.size > 1) {
                item { Text(stringResource(R.string.move_history), style = MaterialTheme.typography.titleMedium) }
                items(state.jobs.drop(1), key = { it.job.id }) { view ->
                    // Unfinished older jobs keep their Continue button.
                    JobCard(
                        view,
                        running = state.running == view.job.id,
                        canResume = !busy,
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
            onNetwork = {
                guide = false
                network = true
            },
            onDismiss = { guide = false },
        )
    }
    if (network) {
        NetworkDriveDialog(
            saved = remember { actions.savedNetworkDrive() },
            onConnect = { location ->
                network = false
                actions.onNetworkDrive(items, location)
            },
            onDismiss = { network = false },
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.move_stop_confirm_title)) },
            text = { Text(stringResource(R.string.move_stop_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    actions.onStop()
                }, modifier = Modifier.testTag("confirmStop")) { Text(stringResource(R.string.move_stop_confirm_yes)) }
            },
            dismissButton = {
                Button(onClick = { confirmStop = false }, modifier = Modifier.testTag("keepGoing")) {
                    Text(stringResource(R.string.move_keep_going))
                }
            },
        )
    }
    confirmFree?.let { view ->
        AlertDialog(
            onDismissRequest = { confirmFree = null },
            title = { Text(stringResource(R.string.move_free_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.move_free_confirm_body,
                        totalText(Total(view.receipt.confirmed, view.receipt.confirmedBytes)),
                    ),
                )
            },
            confirmButton = {
                // Recoverable (30-day trash): not styled as destructive (review PR C).
                SafeConfirmButton(
                    stringResource(R.string.move_free_confirm_yes),
                    onClick = {
                        confirmFree = null
                        actions.onFree(view.job.id, userConfirmed(view.freeablePaths))
                    },
                    modifier = Modifier.testTag("confirmFree"),
                    permanent = false,
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmFree = null }) { Text(stringResource(R.string.move_cancel)) }
            },
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
    Card(Modifier.fillMaxWidth().testTag("photosNote")) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.move_photos_note))
            TextButton(onClick = onOpen) { Text(stringResource(R.string.move_open_photos)) }
        }
    }
}

@Composable
private fun ProblemCard(problem: TargetProblem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().testTag("targetProblem"),
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
                },
            ),
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun FreeResultCard(r: FreeResult, onOpenTrash: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("freeResult")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (r.simulated) {
                Text(
                    stringResource(R.string.test_mode_would_free, totalText(Total(r.done, r.bytes))),
                    modifier = Modifier.testTag("simulatedResult"),
                )
                return@Column
            }
            Text(stringResource(R.string.move_free_done, totalText(Total(r.done, r.bytes))))
            // Zero counts are not shown (review PR C).
            if (r.skipped > 0) {
                Text(countSentence(R.plurals.move_free_skipped, r.skipped), Modifier.testTag("freeSkipped"))
            }
            if (r.failed > 0) Text(countSentence(R.plurals.move_free_failed, r.failed))
            if (r.done > 0) {
                Text(stringResource(R.string.clean_done_space, trashDate()))
                TextButton(onClick = onOpenTrash, modifier = Modifier.testTag("freeEmptyNow")) {
                    Text(stringResource(R.string.clean_empty_now))
                }
            }
        }
    }
}

/** When something trashed now is purged (TRASH-2), for "the space comes back on …". */
@Composable
fun trashDate(): String {
    val locale = LocalConfiguration.current.locales[0]
    val date = java.time.LocalDate.now().plusDays(30)
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
            Card(Modifier.fillMaxWidth().testTag("jobRunning")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text("$phase · $bytes", modifier = Modifier.testTag("moveProgress"))
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = actions.onPause, modifier = Modifier.testTag("pause")) {
                            Text(stringResource(R.string.move_pause))
                        }
                        OutlinedButton(onClick = onStop, modifier = Modifier.testTag("stop")) {
                            Text(stringResource(R.string.move_stop))
                        }
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
            Card(Modifier.fillMaxWidth().testTag("jobPaused")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pauseText(view.job.pauseReason, view.job.targetLabel))
                    Text(totalText(Total(r.verified, r.verifiedBytes)))
                    Button(
                        onClick = { actions.onResume(view.job.id) },
                        enabled = canResume,
                        modifier = Modifier.testTag("resume"),
                    ) { Text(stringResource(R.string.move_continue)) }
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
    Card(Modifier.fillMaxWidth().testTag("receipt")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.move_receipt_title, Bidi.isolate(r.targetLabel)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                countSentence(R.plurals.move_receipt_verified, r.verified, sizeText(r.verifiedBytes)),
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
            if (r.changed > 0) Text(countSentence(R.plurals.move_receipt_changed, r.changed))
            if (r.pending > 0) Text(countSentence(R.plurals.move_receipt_pending, r.pending))
            if (r.namesChanged > 0) Text(countSentence(R.plurals.move_receipt_names, r.namesChanged))
            Text(
                stringResource(R.string.move_receipt_how_back, Bidi.isolate(r.targetLabel), Bidi.isolateLtr(r.rootRel)),
                modifier = Modifier.testTag("howBack"),
            )
            // P-2: developer checks live under Details, not on the default path.
            var details by remember { mutableStateOf(false) }
            TextButton(onClick = { details = !details }, modifier = Modifier.testTag("receiptDetails")) {
                Text(stringResource(R.string.details))
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
                Button(onClick = onCheck, modifier = Modifier.fillMaxWidth().testTag("checkBackup")) {
                    Text(stringResource(R.string.move_check_button))
                }
            }
            when {
                view.canFree -> Button(onClick = onFree, modifier = Modifier.fillMaxWidth().testTag("freeOriginals")) {
                    Text(stringResource(R.string.move_free_button, totalText(Total(r.confirmed, r.confirmedBytes))))
                }

                !view.canReadBack -> Text(
                    stringResource(R.string.move_free_unavailable),
                    modifier = Modifier.testTag("freeUnavailable"),
                )
            }
            TextButton(onClick = onShare, modifier = Modifier.testTag("shareReceipt")) {
                Text(stringResource(R.string.move_share))
            }
        }
    }
}

/** H1 / MOVE-15: the steps of "Check backup". */
@Composable
private fun CheckCard(check: BackupCheck, label: String, actions: MoveActions) {
    val drive = Bidi.isolate(label)
    Card(Modifier.fillMaxWidth().testTag("checkCard")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            TextButton(onClick = actions.onCancelCheck, modifier = Modifier.testTag("checkClose")) {
                Text(stringResource(R.string.move_check_close))
            }
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

/** Review PR C: two plain steps before Android's folder picker, and a check that a drive is attached. */
@Composable
private fun PickGuideDialog(
    hasDrive: () -> Boolean,
    onContinue: () -> Unit,
    onNetwork: () -> Unit,
    onDismiss: () -> Unit,
) {
    var noDrive by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_guide_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("pickGuide")) {
                Text(stringResource(R.string.move_guide_1))
                Text(stringResource(R.string.move_guide_2))
                if (noDrive) {
                    Text(
                        stringResource(R.string.move_guide_no_drive),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("noDrive"),
                    )
                    // Advisory only: other places (a cloud folder: copy-only, MOVE-14) stay reachable.
                    TextButton(onClick = onContinue, modifier = Modifier.testTag("guideAnyway")) {
                        Text(stringResource(R.string.move_guide_anyway))
                    }
                }
                // M15: a computer or NAS at home instead of a drive.
                TextButton(onClick = onNetwork, modifier = Modifier.testTag("useNetwork")) {
                    Text(stringResource(R.string.move_network_option))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (hasDrive()) onContinue() else noDrive = true
            }, modifier = Modifier.testTag("guideContinue")) { Text(stringResource(R.string.move_guide_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.move_cancel)) } },
    )
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
            Button(onClick = actions.onStart, enabled = !picked.blocked, modifier = Modifier.testTag("startCopy")) {
                Text(stringResource(R.string.move_start))
            }
        },
        dismissButton = { TextButton(onClick = actions.onCancelPick) { Text(stringResource(R.string.move_cancel)) } },
    )
}

/** M15: where the network drive is. Only a plain address is accepted (ADR 0011: only to the address typed). */
@Composable
private fun NetworkDriveDialog(
    saved: io.github.tomerar.keepclear.backup.smb.SmbLocation?,
    onConnect: (io.github.tomerar.keepclear.backup.smb.SmbLocation) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(saved?.host.orEmpty()) }
    var share by rememberSaveable { mutableStateOf(saved?.share.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(saved?.user.orEmpty()) }
    // The password is never kept in saved instance state.
    var password by remember { mutableStateOf(saved?.password.orEmpty()) }
    val hostOk = io.github.tomerar.keepclear.backup.smb.SmbHosts.isPlainHost(host.trim())
    val shareOk = share.isNotBlank() && '/' !in share && '\\' !in share
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_network_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("networkDrive").verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.move_network_intro), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.move_network_host)) },
                    singleLine = true,
                    isError = host.isNotBlank() && !hostOk,
                    supportingText = if (host.isNotBlank() && !hostOk) {
                        { Text(stringResource(R.string.move_network_bad_host)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("networkHost"),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = share,
                    onValueChange = { share = it },
                    label = { Text(stringResource(R.string.move_network_share)) },
                    singleLine = true,
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConnect(
                        io.github.tomerar.keepclear.backup.smb.SmbLocation(
                            host = host.trim(),
                            share = share.trim(),
                            user = user.trim(),
                            password = password,
                        ),
                    )
                },
                enabled = hostOk && shareOk,
                modifier = Modifier.testTag("networkConnect"),
            ) { Text(stringResource(R.string.move_network_connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.move_cancel)) } },
    )
}
