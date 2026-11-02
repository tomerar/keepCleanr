// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmFact
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.designsystem.component.ResultBanner
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.Confirmation
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Total

/** Result of a cleanup, for the result card and Undo (SAFE-1, E-DEL-15). */
data class CleanResult(
    val done: Total,
    val skipped: Int,
    val failed: Int,
    val trashedPaths: List<String>,
    /** D5: items the choke point refused because they are PROTECTED; the rest of the plan ran. */
    val protectedSkipped: Int = 0,
    /** Test mode (SAFETY-9): nothing was changed; [done] is what would have been freed. */
    val simulated: Boolean = false,
    /** The screen the action was started from; the result (and its Undo) shows only there (review PR C). */
    val origin: Screen? = null,
    /** M12: the items went to Android's own trash (restore from KeepClear's Trash or the gallery's bin). */
    val androidTrash: Boolean = false,
    /** M12: the user said no in Android's dialog; nothing changed. */
    val declined: Boolean = false,
    /** E13: the trash entries this action added; Undo restores exactly these. */
    val trashedIds: Set<String> = emptySet(),
)

/**
 * "Clean now" (SAFE-1…6): SAFE groups are pre-selected (SAFETY-10 allows it only for SAFE), the user can
 * untick any; one confirmation moves the selection to the trash. Android cache trimming is separate
 * and labelled as non-restorable (SAFETY-7).
 */
@Composable
fun SafeScreen(
    summary: ClassificationSummary,
    result: CleanResult?,
    trimResult: Long?,
    showExternalCacheClear: Boolean,
    onClean: (List<ClassifiedItem>, Confirmation) -> Unit,
    onUndo: (CleanResult, Confirmation) -> Unit,
    onTrim: () -> Unit,
    onClearExternalCaches: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onBack: () -> Unit,
    /** An action is running: buttons can't be tapped twice (review PR C). */
    busy: Boolean = false,
    onOneByOne: () -> Unit = {},
) {
    val groups = summary.groups(Category.SAFE)
    var unselected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirmClean by remember { mutableStateOf(false) }
    var confirmTrim by remember { mutableStateOf(false) }
    val selectedItems = groups.filter { it.ruleId !in unselected }.flatMap { it.items }
    val selectedTotal = selectedItems.fold(Total()) { t, i -> t + i }
    val locale = LocalConfiguration.current.locales[0]

    Scaffold(
        topBar = { SubScreenTopBar(stringResource(R.string.clean_title), onBack) },
        bottomBar = {
            KcButton(
                stringResource(R.string.clean_button, totalText(selectedTotal)),
                onClick = { confirmClean = true },
                enabled = selectedItems.isNotEmpty() && !busy,
                icon = KcIcons.Safe,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("cleanButton"),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("safeScreen"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            result?.let { r -> item { CleanResultBanner(r, onUndo, onOpenTrash) } }
            item {
                Text(
                    stringResource(R.string.clean_selected, totalText(selectedTotal)),
                    modifier = Modifier.testTag("selectedTotal"),
                )
            }
            items(groups, key = { it.ruleId }) { group ->
                val text = RuleText.of(group.ruleId)
                val checked = group.ruleId !in unselected
                KcCard(modifier = Modifier.testTag("safe_${group.ruleId}")) {
                    // Review PR C: checkbox and text are one touch target (and one TalkBack checkbox).
                    Row(
                        modifier = Modifier
                            .toggleable(value = checked, role = Role.Checkbox) { on ->
                                unselected = if (on) unselected - group.ruleId else unselected + group.ruleId
                            }
                            .testTag("check_${group.ruleId}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(stringResource(text.group), style = MaterialTheme.typography.titleSmall)
                            Text(totalText(group.total), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(text.why), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    KcButton(
                        stringResource(R.string.action_look),
                        onClick = { onOpenGroup(group.ruleId) },
                        style = KcButtonStyle.Text,
                        modifier = Modifier.testTag("open_${group.ruleId}"),
                    )
                }
            }
            item {
                KcCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.trim_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.trim_body), style = MaterialTheme.typography.bodyMedium)
                        trimResult?.let {
                            Text(
                                when {
                                    it == TestMode.TRIM_SKIPPED -> stringResource(R.string.test_mode_caches_not_cleared)

                                    // Review PR C: say why instead of "Android freed 0 B".
                                    it <= 0L -> stringResource(R.string.trim_result_zero)

                                    else -> stringResource(
                                        R.string.trim_result,
                                        io.github.tomerar.keepclear.core.common.Bidi.isolate(
                                            ByteSize.format(it, locale),
                                        ),
                                    )
                                },
                                modifier = Modifier.testTag("trimResult"),
                            )
                        }
                        KcButton(
                            stringResource(R.string.trim_button),
                            onClick = { confirmTrim = true },
                            enabled = !busy,
                            style = KcButtonStyle.Tonal,
                            modifier = Modifier.testTag("trim"),
                        )
                        KcButton(
                            stringResource(R.string.one_by_one_button),
                            onClick = onOneByOne,
                            style = KcButtonStyle.Text,
                            modifier = Modifier.testTag("oneByOneSafe"),
                        )
                        if (showExternalCacheClear) {
                            KcButton(
                                stringResource(R.string.trim_external_button),
                                onClick = onClearExternalCaches,
                                style = KcButtonStyle.Tonal,
                                modifier = Modifier.testTag("trimExternal"),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClean) {
        CleanConfirmDialog(selectedItems, onClean = onClean, onDismiss = { confirmClean = false })
    }
    if (confirmTrim) {
        TrimConfirmSheet(
            onTrim = {
                confirmTrim = false
                onTrim()
            },
            onDismiss = { confirmTrim = false },
            tag = "confirmTrim",
            bodyTag = "trimLabel",
        )
    }
}

/**
 * SAFE-5: Android trims other apps' temporary files (they come back by themselves). Not the 30-day trash, but not
 * "permanent" in the sense of the design system either (nothing of the user's is lost, brief F2): ink, not red.
 */
@Composable
internal fun TrimConfirmSheet(onTrim: () -> Unit, onDismiss: () -> Unit, tag: String, bodyTag: String) {
    ConfirmSheet(
        title = stringResource(R.string.trim_title),
        dismissText = stringResource(R.string.trash_cancel),
        onDismiss = onDismiss,
        facts = listOf(
            ConfirmFact(stringResource(R.string.confirm_fact_where), stringResource(R.string.confirm_where_trim)),
        ),
        confirmButton = {
            SafeConfirmButton(
                stringResource(R.string.trim_button),
                onClick = onTrim,
                modifier = Modifier.fillMaxWidth().testTag(tag),
                permanent = false,
            )
        },
    ) {
        Text(
            stringResource(R.string.trim_confirm_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(bodyTag),
        )
    }
}

/** F2: what, how many, how much and where it goes, for a ConfirmSheet (brief "ConfirmSheet"). */
@Composable
internal fun confirmFacts(total: Total, where: String): List<ConfirmFact> {
    val locale = LocalConfiguration.current.locales[0]
    val count = pluralStringResource(
        R.plurals.file_count,
        total.files,
        java.text.NumberFormat.getIntegerInstance(locale).format(total.files),
    )
    return listOf(
        ConfirmFact(
            stringResource(R.string.confirm_fact_how_many),
            io.github.tomerar.keepclear.core.common.Bidi.isolate(count),
        ),
        ConfirmFact(
            stringResource(R.string.confirm_fact_how_much),
            io.github.tomerar.keepclear.core.common.Bidi.isolate(ByteSize.format(total.bytes, locale)),
        ),
        ConfirmFact(stringResource(R.string.confirm_fact_where), where),
    )
}

/**
 * SAFE-1: the one confirmation before SAFE items go to the trash (30 days), naming exactly [items]. Used by "Clean
 * now" and by the Free-up-space tab's main button (F1b); the confirm is the tapjacking-safe [SafeConfirmButton].
 */
@Composable
fun CleanConfirmDialog(
    items: List<ClassifiedItem>,
    onClean: (List<ClassifiedItem>, Confirmation) -> Unit,
    onDismiss: () -> Unit,
) {
    val total = items.fold(Total()) { t, i -> t + i }
    ConfirmSheet(
        title = stringResource(R.string.clean_confirm_title, totalText(total)),
        dismissText = stringResource(R.string.trash_cancel),
        onDismiss = onDismiss,
        facts = confirmFacts(total, stringResource(R.string.confirm_where_trash)),
        note = stringResource(R.string.clean_confirm_note),
        modifier = Modifier.testTag("cleanConfirm"),
        confirmButton = {
            SafeConfirmButton(
                stringResource(R.string.clean_confirm_yes),
                onClick = {
                    onDismiss()
                    onClean(items, userConfirmed(items.map { it.item.path }))
                },
                modifier = Modifier.fillMaxWidth().testTag("confirmClean"),
                permanent = false,
            )
        },
    )
}

/**
 * SAFE-1, E-DEL-15 (F2: a ResultBanner): what a cleanup did, with Undo and "Empty now" (opens the Trash). A clean
 * moves files to KeepClear's trash on the same storage, so no free-space change is claimed for it; the space comes
 * back when the trash is emptied, and that is measured then ([MeasuredBanner]).
 */
@Composable
fun CleanResultBanner(
    r: CleanResult,
    onUndo: (CleanResult, Confirmation) -> Unit,
    onOpenTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when {
        r.simulated -> stringResource(R.string.test_mode_would_free, totalText(r.done))
        r.declined -> stringResource(R.string.android_trash_declined)
        r.androidTrash -> stringResource(R.string.android_trash_done, totalText(r.done))
        else -> stringResource(R.string.clean_done, totalText(r.done))
    }
    val detail = buildList {
        if (!r.simulated && !r.androidTrash && !r.declined) add(stringResource(R.string.clean_done_space, trashDate()))
        if (r.skipped > 0) add(countSentence(R.plurals.clean_skipped, r.skipped))
        if (r.protectedSkipped > 0) add(countSentence(R.plurals.clean_skipped_protected, r.protectedSkipped))
    }.joinToString("\n").ifEmpty { null }
    val undoable = !r.simulated && !r.androidTrash && !r.declined
    val tag = when {
        r.simulated -> "simulatedResult"
        r.androidTrash || r.declined -> "androidTrashResult"
        else -> "cleanBanner"
    }
    Box(modifier.fillMaxWidth().testTag("cleanResult")) {
        ResultBanner(
            message = message,
            detail = detail,
            undoText = stringResource(R.string.clean_undo).takeIf { undoable },
            onUndo = { onUndo(r, userConfirmed(r.trashedPaths)) }.takeIf { undoable },
            // "Need the space now? Empty now" opens the trash, where emptying is confirmed separately.
            actionText = stringResource(R.string.clean_empty_now).takeIf { undoable },
            onAction = onOpenTrash.takeIf { undoable },
            undoModifier = Modifier.testTag("undo"),
            actionModifier = Modifier.testTag("emptyNowLink"),
            modifier = Modifier.testTag(tag),
        )
    }
}

/** "Freed 7.9 GB · now 24.7 GB free (was 16.8 GB)" (review PR C), measured after a trim or emptying the trash. */
@Composable
fun MeasuredBanner(m: Measured, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]

    fun s(b: Long) = io.github.tomerar.keepclear.core.common.Bidi.isolate(ByteSize.format(b, locale))
    ResultBanner(
        message = stringResource(R.string.result_freed, s(m.freed), s(m.nowFree), s(m.wasFree)),
        modifier = modifier.testTag("measured"),
    )
}
