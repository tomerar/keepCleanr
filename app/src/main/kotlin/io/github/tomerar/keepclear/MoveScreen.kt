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
    val onStart: () -> Unit = {},
    val onCancelPick: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onResume: (String) -> Unit = {},
    val onFree: (String) -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onOpenGooglePhotos: () -> Unit = {},
    val onOpenGroup: (String) -> Unit = {},
)

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
            item { PhotosNote(actions.onOpenGooglePhotos) }
            if (notificationsOff && busy) {
                item { Text(stringResource(R.string.move_notif_warning), modifier = Modifier.testTag("notifWarning")) }
            }
            state.problem?.let { problem -> item { ProblemCard(problem) } }
            state.freeResult?.let { r -> item { FreeResultCard(r) } }
            state.jobs.firstOrNull()?.let { latest ->
                item {
                    JobCard(
                        latest,
                        running = state.running == latest.job.id,
                        canResume = !busy,
                        actions = actions,
                        onFree = { confirmFree = latest },
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
                    onClick = { actions.onPickTarget(items) },
                    enabled = items.isNotEmpty() && !busy && !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("pickTarget"),
                ) { Text(stringResource(R.string.move_pick_target)) }
            }
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
                    )
                }
            }
        }
    }

    state.picked?.let { picked ->
        PickedDialog(picked, actions)
    }
    confirmFree?.let { view ->
        AlertDialog(
            onDismissRequest = { confirmFree = null },
            title = { Text(stringResource(R.string.move_free_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.move_free_confirm_body,
                        totalText(Total(view.receipt.verified, view.receipt.verifiedBytes)),
                    ),
                )
            },
            confirmButton = {
                SafeConfirmButton(
                    stringResource(R.string.move_free_confirm_yes),
                    onClick = {
                        confirmFree = null
                        actions.onFree(view.job.id)
                    },
                    modifier = Modifier.testTag("confirmFree"),
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
                },
            ),
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun FreeResultCard(r: FreeResult) {
    val locale = LocalConfiguration.current.locales[0]
    val nf = NumberFormat.getIntegerInstance(locale)
    Card(Modifier.fillMaxWidth().testTag("freeResult")) {
        if (r.simulated) {
            Text(
                stringResource(R.string.test_mode_would_free, totalText(Total(r.done, r.bytes))),
                modifier = Modifier.padding(12.dp).testTag("simulatedResult"),
            )
            return@Card
        }
        Text(
            stringResource(
                R.string.move_free_result,
                totalText(Total(r.done, r.bytes)),
                Bidi.isolate(nf.format(r.skipped)),
                Bidi.isolate(nf.format(r.failed)),
            ),
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun JobCard(view: JobView, running: Boolean, canResume: Boolean, actions: MoveActions, onFree: () -> Unit) {
    val r = view.receipt
    when {
        running -> {
            Card(Modifier.fillMaxWidth().testTag("jobRunning")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.move_running,
                            Bidi.isolate(view.job.targetLabel),
                            Bidi.isolate(r.verified.toString()),
                            Bidi.isolate(r.total.toString()),
                        ),
                    )
                    LinearProgressIndicator(
                        progress = { if (r.total == 0) 0f else r.verified.toFloat() / r.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = actions.onPause, modifier = Modifier.testTag("pause")) {
                            Text(stringResource(R.string.move_pause))
                        }
                        OutlinedButton(onClick = actions.onStop, modifier = Modifier.testTag("stop")) {
                            Text(stringResource(R.string.move_stop))
                        }
                    }
                }
            }
        }

        view.job.state == JobState.DONE -> {
            ReceiptCard(view, onFree = onFree, onShare = { actions.onShare(view.job.id) })
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
    PauseReason.PAUSED_BY_USER, null -> stringResource(R.string.move_paused_user)
}

/** MOVE-9 / MOVE-18 / Q12: what was copied and checked, what wasn't and why, and how to get files back. */
@Composable
fun ReceiptCard(view: JobView, onFree: () -> Unit, onShare: () -> Unit) {
    val r = view.receipt
    Card(Modifier.fillMaxWidth().testTag("receipt")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.move_receipt_title, Bidi.isolate(r.targetLabel)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.move_receipt_verified, totalText(Total(r.verified, r.verifiedBytes))),
                modifier = Modifier.testTag("receiptVerified"),
            )
            r.failed.forEach { (reason, count) ->
                Text(
                    stringResource(failText(reason), countText(count)),
                    modifier = Modifier.testTag("failed_${reason.name}"),
                )
            }
            if (r.unverified > 0) {
                Text(
                    stringResource(R.string.move_receipt_unverified, countText(r.unverified)),
                    modifier = Modifier.testTag("receiptUnverified"),
                )
            }
            if (r.changed > 0) Text(stringResource(R.string.move_receipt_changed, countText(r.changed)))
            if (r.pending > 0) Text(stringResource(R.string.move_receipt_pending, countText(r.pending)))
            if (r.namesChanged > 0) Text(stringResource(R.string.move_receipt_names, countText(r.namesChanged)))
            Text(
                stringResource(R.string.move_receipt_how_back, Bidi.isolate(r.targetLabel), Bidi.isolateLtr(r.rootRel)),
                modifier = Modifier.testTag("howBack"),
            )
            Text(stringResource(R.string.move_receipt_check_pc), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.move_receipt_unencrypted),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("unencrypted"),
            )
            when {
                view.canFree -> Button(onClick = onFree, modifier = Modifier.fillMaxWidth().testTag("freeOriginals")) {
                    Text(stringResource(R.string.move_free_button, totalText(Total(r.verified, r.verifiedBytes))))
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

@Composable
private fun countText(count: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return Bidi.isolate(
        pluralStringResource(R.plurals.file_count, count, NumberFormat.getIntegerInstance(locale).format(count)),
    )
}

private fun failText(reason: FailReason) = when (reason) {
    FailReason.SOURCE_MISSING -> R.string.move_receipt_failed_missing
    FailReason.SOURCE_UNREADABLE -> R.string.move_receipt_failed_unreadable
    FailReason.TOO_LARGE_FOR_TARGET -> R.string.move_receipt_failed_too_large
    FailReason.HASH_MISMATCH -> R.string.move_receipt_failed_hash
    FailReason.TARGET_ERROR -> R.string.move_receipt_failed_target
}

@Composable
private fun PickedDialog(picked: PickedTarget, actions: MoveActions) {
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(
        onDismissRequest = actions.onCancelPick,
        title = { Text(stringResource(R.string.move_target_title, Bidi.isolate(picked.label))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("preflight")) {
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

                    TargetKind.REMOVABLE -> {}
                }
                picked.issues.forEach { issue ->
                    val text = when (issue) {
                        is MovePlanner.Issue.NotEnoughSpace -> stringResource(
                            R.string.move_issue_space,
                            Bidi.isolate(ByteSize.format(issue.needed, locale)),
                            Bidi.isolate(ByteSize.format(issue.free, locale)),
                        )

                        is MovePlanner.Issue.TooLargeForTarget -> stringResource(
                            if (picked.format == io.github.tomerar.keepclear.backup.saf.DriveFormat.UNKNOWN) {
                                R.string.move_issue_too_large_unknown
                            } else {
                                R.string.move_issue_too_large
                            },
                            countText(issue.files.size),
                        )

                        MovePlanner.Issue.BatteryLow -> stringResource(R.string.move_issue_battery)

                        is MovePlanner.Issue.NamesChanged -> stringResource(
                            R.string.move_issue_names,
                            countText(issue.count),
                        )

                        MovePlanner.Issue.CannotVerify -> stringResource(R.string.move_issue_cannot_verify)

                        MovePlanner.Issue.TargetIsSource -> stringResource(R.string.move_problem_phone)
                    }
                    val colors = MaterialTheme.colorScheme
                    Text(text, color = if (issue.blocking) colors.error else colors.onSurface)
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
