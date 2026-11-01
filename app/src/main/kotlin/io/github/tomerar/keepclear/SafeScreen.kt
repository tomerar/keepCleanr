// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.ByteSize
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
    /** Test mode (SAFETY-9): nothing was changed; [done] is what would have been freed. */
    val simulated: Boolean = false,
    /** The screen the action was started from; the result (and its Undo) shows only there (review PR C). */
    val origin: Screen? = null,
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
) {
    val groups = summary.groups(Category.SAFE)
    var unselected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirmClean by remember { mutableStateOf(false) }
    var confirmTrim by remember { mutableStateOf(false) }
    val selectedItems = groups.filter { it.ruleId !in unselected }.flatMap { it.items }
    val selectedTotal = selectedItems.fold(Total()) { t, i -> t + i }
    val locale = LocalConfiguration.current.locales[0]

    Scaffold(
        bottomBar = {
            Button(
                onClick = { confirmClean = true },
                enabled = selectedItems.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("cleanButton"),
            ) { Text(stringResource(R.string.clean_button, totalText(selectedTotal))) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("safeScreen"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                        Text(stringResource(R.string.back))
                    }
                    Text(stringResource(R.string.clean_title), style = MaterialTheme.typography.titleLarge)
                }
            }
            result?.let { r ->
                item {
                    Card(modifier = Modifier.fillMaxWidth().testTag("cleanResult")) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (r.simulated) {
                                Text(
                                    stringResource(R.string.test_mode_would_free, totalText(r.done)),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.testTag("simulatedResult"),
                                )
                            } else {
                                Text(
                                    stringResource(R.string.clean_done, totalText(r.done)),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(R.string.clean_done_space, trashDate()),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (r.skipped > 0) Text(countSentence(R.plurals.clean_skipped, r.skipped))
                            if (!r.simulated) {
                                UndoRow(onUndo = {
                                    onUndo(r, userConfirmed(r.trashedPaths))
                                }, onOpenTrash = onOpenTrash)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.clean_selected, totalText(selectedTotal)),
                    modifier = Modifier.testTag("selectedTotal"),
                )
            }
            items(groups, key = { it.ruleId }) { group ->
                val text = RuleText.of(group.ruleId)
                val checked = group.ruleId !in unselected
                Card(modifier = Modifier.fillMaxWidth().testTag("safe_${group.ruleId}")) {
                    // Review PR C: checkbox and text are one touch target (and one TalkBack checkbox).
                    Row(
                        modifier = Modifier
                            .toggleable(value = checked, role = Role.Checkbox) { on ->
                                unselected = if (on) unselected - group.ruleId else unselected + group.ruleId
                            }
                            .padding(12.dp)
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
                    TextButton(
                        onClick = { onOpenGroup(group.ruleId) },
                        modifier = Modifier.padding(start = 8.dp).testTag("open_${group.ruleId}"),
                    ) { Text(stringResource(R.string.action_look)) }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.trim_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.trim_body), style = MaterialTheme.typography.bodyMedium)
                        trimResult?.let {
                            Text(
                                when {
                                    it == TestMode.TRIM_SKIPPED -> stringResource(R.string.test_mode_caches_not_cleared)

                                    // Review PR C: say why instead of "Android freed 0 B".
                                    it <= 0L -> stringResource(R.string.trim_result_zero)

                                    else -> stringResource(R.string.trim_result, ByteSize.format(it, locale))
                                },
                                modifier = Modifier.testTag("trimResult"),
                            )
                        }
                        OutlinedButton(onClick = { confirmTrim = true }, modifier = Modifier.testTag("trim")) {
                            Text(stringResource(R.string.trim_button))
                        }
                        if (showExternalCacheClear) {
                            OutlinedButton(
                                onClick = onClearExternalCaches,
                                modifier = Modifier.testTag("trimExternal"),
                            ) {
                                Text(stringResource(R.string.trim_external_button))
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClean) {
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text(stringResource(R.string.clean_confirm_title, totalText(selectedTotal))) },
            text = { Text(stringResource(R.string.clean_confirm_body, totalText(selectedTotal))) },
            confirmButton = {
                SafeConfirmButton(
                    stringResource(R.string.clean_confirm_yes),
                    onClick = {
                        confirmClean = false
                        onClean(selectedItems, userConfirmed(selectedItems.map { it.item.path }))
                    },
                    modifier = Modifier.testTag("confirmClean"),
                    permanent = false,
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmClean = false }) { Text(stringResource(R.string.trash_cancel)) }
            },
        )
    }
    if (confirmTrim) {
        AlertDialog(
            onDismissRequest = { confirmTrim = false },
            title = { Text(stringResource(R.string.trim_title)) },
            text = { Text(stringResource(R.string.trim_confirm_body), modifier = Modifier.testTag("trimLabel")) },
            confirmButton = {
                SafeConfirmButton(
                    stringResource(R.string.trim_button),
                    onClick = {
                        confirmTrim = false
                        onTrim()
                    },
                    modifier = Modifier.testTag("confirmTrim"),
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmTrim = false }) { Text(stringResource(R.string.trash_cancel)) }
            },
        )
    }
}

@Composable
private fun UndoRow(onUndo: () -> Unit, onOpenTrash: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onUndo, modifier = Modifier.testTag("undo")) {
            Text(stringResource(R.string.clean_undo))
        }
        // "Need the space now? Empty now" opens the trash, where emptying is confirmed separately.
        TextButton(onClick = onOpenTrash, modifier = Modifier.testTag("emptyNowLink")) {
            Text(stringResource(R.string.clean_empty_now))
        }
    }
}
