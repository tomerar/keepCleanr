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
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
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
    /**
     * Owner decision 2026-11-02: the part of [done] that was deleted for good (rebuilds itself, already in another
     * app's bin). The rest of [done] went to the trash.
     */
    val deletedForGood: Total = Total(),
    /** Free space measured before and after, when something was deleted for good (the trash part frees nothing yet). */
    val measured: Measured? = null,
    /** PR H5: test mode only: what the run would have deleted, for „לייצא את הרשימה”; empty otherwise. */
    val wouldDelete: List<WouldDelete> = emptyList(),
) {
    /** What went to a trash (KeepClear's or Android's) and can be put back. */
    val trashed: Total get() = Total(done.files - deletedForGood.files, done.bytes - deletedForGood.bytes)
}

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
                // Owner brief H2 item 10: never "0 files · 0B"; with nothing selected the disabled button is "Clean".
                if (selectedItems.isEmpty()) {
                    stringResource(R.string.clean_button_empty)
                } else {
                    stringResource(R.string.clean_button, totalText(selectedTotal))
                },
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
                    selectionText(selectedTotal, R.string.clean_selected),
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
internal fun confirmFacts(total: Total, where: String?): List<ConfirmFact> {
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
    ) + listOfNotNull(where?.let { ConfirmFact(stringResource(R.string.confirm_fact_where), it) })
}

/**
 * Owner decision 2026-11-02: what a clean confirmation shows, split into "deleted for good" and "to the trash". Owner
 * brief H2 item 5: the row "What happens" (not "Where"); the one red line is [risk] (other apps' bins, gone for
 * good), shown first; [note] is never red.
 */
internal class CleanSheetText(
    val facts: List<ConfirmFact>,
    val note: String,
    val risk: String?,
    val anyForGood: Boolean,
) {
    /** "Move to trash" only when everything goes to the trash; "Clean now" when some of it is deleted for good. */
    val confirmLabel: Int get() = if (anyForGood) R.string.clean_confirm_yes_now else R.string.clean_confirm_yes
}

/**
 * The rows and notes of a clean confirmation (SAFE-1, REV-3). How many and how much in all, then "What happens":
 * what rebuilds itself is deleted now and the phone makes it again; the rest goes to the trash for 30 days (with
 * each part's size when there is more than one). Items already in another app's bin are the red [CleanSheetText.risk]
 * line instead. When every item is SAFE junk, the note always reassures that photos, videos and chats aren't deleted
 * (owner brief H2 item 5, C141).
 */
@Composable
internal fun cleanSheetText(items: List<ClassifiedItem>): CleanSheetText {
    val now = remember { System.currentTimeMillis() }
    val reasons = remember(items) {
        io.github.tomerar.keepclear.engine.enrich.CleanPlanner.permanentReasons(items, now)
    }
    val bins = items.filter {
        reasons[it.item.path] == io.github.tomerar.keepclear.core.safety.PermanentReason.ALREADY_IN_A_BIN
    }
    val rebuilds = items.filter {
        reasons[it.item.path] == io.github.tomerar.keepclear.core.safety.PermanentReason.REBUILDS_ITSELF
    }
    val toTrash = items.filter { it.item.path !in reasons }
    val total = items.fold(Total()) { t, i -> t + i }
    val parts = listOf(bins, rebuilds, toTrash).count { it.isNotEmpty() }
    val happens = buildList {
        if (rebuilds.isNotEmpty()) add(rebuilds to stringResource(R.string.clean_happens_rebuilds))
        if (toTrash.isNotEmpty()) add(toTrash to stringResource(R.string.confirm_where_trash))
    }.map { (part, sentence) ->
        if (parts > 1) stringResource(R.string.clean_happens_part, partValue(part), sentence) else sentence
    }
    val facts = confirmFacts(total, null) + listOfNotNull(
        happens.takeIf { it.isNotEmpty() }?.let {
            ConfirmFact(stringResource(R.string.confirm_fact_what_happens), it.joinToString("\n"))
        },
    )
    val risk = bins.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.clean_risk_bins, partValue(it)) }
    val note = buildList {
        if (items.all { it.category == io.github.tomerar.keepclear.core.model.Category.SAFE }) {
            add(stringResource(R.string.clean_reassurance))
        }
        when {
            toTrash.isNotEmpty() && toTrash.size == items.size -> add(stringResource(R.string.clean_confirm_note))
            toTrash.isNotEmpty() -> add(stringResource(R.string.clean_confirm_note_rest))
        }
    }.joinToString("\n")
    return CleanSheetText(facts, note, risk, anyForGood = reasons.isNotEmpty())
}

/** "1,234 files · 4.1GB" (count first, owner brief H2 item 10), each part isolated. */
@Composable
private fun partValue(items: List<ClassifiedItem>): String = totalText(items.fold(Total()) { t, i -> t + i })

/** Design QA item 5: the title is only the question, „לנקות 7.9GB?”; the count and the rest are in the rows. */
@Composable
internal fun sizeOnly(items: List<ClassifiedItem>): String {
    val locale = LocalConfiguration.current.locales[0]
    return io.github.tomerar.keepclear.core.common.Bidi.isolate(ByteSize.format(items.sumOf { it.item.size }, locale))
}

/**
 * SAFE-1: the one confirmation before SAFE items are cleaned, naming exactly [items]. Used by "Clean now" and by the
 * Free-up-space tab's main button (F1b); the confirm is the tapjacking-safe [SafeConfirmButton]. Items that rebuild
 * themselves or are already in another app's bin are deleted for good; the rest go to the trash (owner decision
 * 2026-11-02), and the sheet says which is which.
 */
@Composable
fun CleanConfirmDialog(
    items: List<ClassifiedItem>,
    onClean: (List<ClassifiedItem>, Confirmation) -> Unit,
    onDismiss: () -> Unit,
) {
    val text = cleanSheetText(items)
    ConfirmSheet(
        title = stringResource(R.string.clean_confirm_title, sizeOnly(items)),
        dismissText = stringResource(R.string.trash_cancel),
        onDismiss = onDismiss,
        facts = text.facts,
        note = text.note,
        risk = text.risk,
        modifier = Modifier.testTag("cleanConfirm"),
        confirmButton = {
            SafeConfirmButton(
                stringResource(text.confirmLabel),
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
 * SAFE-1, E-DEL-15 (F2: a ResultBanner): what a cleanup did, with Undo and "Empty now" (opens the Trash). Moving files
 * to KeepClear's trash on the same storage frees nothing yet, so no free-space change is claimed for that part; the
 * space comes back when the trash is emptied. Owner decision 2026-11-02: when something was deleted for good, the
 * banner leads with the measured result („פונו 4.1GB · עכשיו פנויים 20.9GB”), then the trash part („3.8GB בסל,
 * יתפנו ב-…”) with Undo and "Empty now", which act on the trash part only.
 */
@Composable
fun CleanResultBanner(
    r: CleanResult,
    onUndo: (CleanResult, Confirmation) -> Unit,
    onOpenTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]

    fun s(b: Long) = io.github.tomerar.keepclear.core.common.Bidi.isolate(ByteSize.format(b, locale))
    val forGood = r.deletedForGood.files > 0
    val measured = r.measured.takeIf { forGood && !r.simulated }
    val trashedPart = r.trashed.files > 0
    val message = when {
        r.simulated -> stringResource(R.string.test_mode_would_free, totalText(r.done))
        measured != null -> stringResource(R.string.result_freed_now, s(measured.freed), s(measured.nowFree))
        r.declined -> stringResource(R.string.android_trash_declined)
        r.androidTrash -> stringResource(R.string.android_trash_done, totalText(r.trashed))
        forGood && !trashedPart -> stringResource(R.string.clean_deleted_for_good, totalText(r.deletedForGood))
        else -> stringResource(R.string.clean_done, totalText(r.trashed))
    }
    val ownTrash = !r.simulated && !r.androidTrash && !r.declined && trashedPart
    val detail = buildList {
        if (r.simulated && forGood) {
            add(stringResource(R.string.test_mode_would_delete_for_good, totalText(r.deletedForGood)))
        }
        if (!r.simulated && forGood && measured == null && trashedPart) {
            add(stringResource(R.string.clean_deleted_for_good, totalText(r.deletedForGood)))
        }
        if (ownTrash) {
            if (measured != null) {
                add(stringResource(R.string.clean_in_trash, s(r.trashed.bytes), trashDate()))
            } else {
                add(stringResource(R.string.clean_done_space, trashDate()))
            }
        }
        if (r.skipped > 0) add(countSentence(R.plurals.clean_skipped, r.skipped))
        if (r.protectedSkipped > 0) add(countSentence(R.plurals.clean_skipped_protected, r.protectedSkipped))
    }.joinToString("\n").ifEmpty { null }
    // Undo and "Empty now" only for what is in KeepClear's trash; what was deleted for good can't come back.
    val undoable = ownTrash
    // PR H5: in test mode, „לייצא את הרשימה” (after a line that says the list has file names; never automatic).
    val exporter = LocalTestExporter.current
    val exportable = exporter != null && TestExport.offered(r, exporter.testModeOn())
    var askExport by remember { mutableStateOf(false) }
    val exportDetail = when (exporter?.saved?.value.takeIf { exportable }) {
        true -> stringResource(R.string.test_export_done)
        false -> stringResource(R.string.test_export_failed)
        null -> null
    }
    val tag = when {
        r.simulated -> "simulatedResult"
        r.androidTrash || r.declined -> "androidTrashResult"
        else -> "cleanBanner"
    }
    Box(modifier.fillMaxWidth().testTag("cleanResult")) {
        ResultBanner(
            message = message,
            detail = listOfNotNull(detail, exportDetail).joinToString("\n").ifEmpty { null },
            undoText = stringResource(R.string.clean_undo).takeIf { undoable },
            onUndo = { onUndo(r, userConfirmed(r.trashedPaths)) }.takeIf { undoable },
            // "Need the space now? Empty now" opens the trash, where emptying is confirmed separately.
            actionText = when {
                undoable -> stringResource(R.string.clean_empty_now)
                exportable -> stringResource(R.string.test_export_button)
                else -> null
            },
            onAction = when {
                undoable -> {
                    onOpenTrash
                }

                exportable -> {
                    { askExport = true }
                }

                else -> {
                    null
                }
            },
            undoModifier = Modifier.testTag("undo"),
            actionModifier = Modifier.testTag(if (undoable) "emptyNowLink" else "testExport"),
            modifier = Modifier.testTag(tag),
        )
    }
    if (askExport && exporter != null) {
        // The list has file names and folders: said before anything is saved, and saved only where chosen.
        InfoSheet(
            title = stringResource(R.string.test_export_title),
            dismissText = stringResource(R.string.move_cancel),
            onDismiss = { askExport = false },
            modifier = Modifier.testTag("testExportSheet"),
            primaryButton = {
                KcButton(
                    stringResource(R.string.test_export_choose),
                    onClick = {
                        askExport = false
                        exporter.export(r.wouldDelete)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("testExportChoose"),
                )
            },
        ) {
            Text(
                stringResource(R.string.test_export_body, totalText(r.done)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
