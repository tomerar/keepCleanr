// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.github.tomerar.keepclear.core.i18n.R

/** Always visible while test mode is on (SAFETY-9): nothing will be deleted or moved. */
@Composable
fun TestModeBanner(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.test_mode_banner),
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("testModeBanner"),
    )
}

/** Review PR C (UX 12): language, About, privacy, replay the intro, how to get files back. */
class SettingsActions(
    /** null = the phone's language; otherwise a BCP-47 tag such as "iw" or "en". */
    val currentLanguage: String? = null,
    val onLanguage: (String?) -> Unit = {},
    val onReplayIntro: () -> Unit = {},
    val onOpenPrivacy: () -> Unit = {},
    val version: String = "",
    /** TRASH-7: bytes in KeepClear's trash now; the uninstall note shows while it isn't empty. */
    val trashBytes: Long = 0,
    val onOpenTrash: () -> Unit = {},
    /** M15: the network drive's address, the only place KeepClear connects to; null when none is set up. */
    val networkHost: String? = null,
    val onForgetNetworkDrive: () -> Unit = {},
    /** CLS-7 / SET-2: what the user kept or marked as junk, and how to undo it. */
    val choices: Choices = Choices(),
    val onRemoveKept: (String) -> Unit = {},
    val onRemoveJunk: (String) -> Unit = {},
)

/**
 * Settings. **Advanced** holds the test-mode switch: turning test mode on is one tap; turning it off needs two
 * confirmations, because from then on actions really delete and move files.
 */
@Composable
fun SettingsScreen(
    testModeOn: Boolean,
    onTestModeOn: () -> Unit,
    onTestModeOff: () -> Unit,
    onBack: () -> Unit,
    actions: SettingsActions = SettingsActions(),
) {
    // 0 = no dialog, 1 = first confirmation, 2 = second confirmation.
    var step by rememberSaveable { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("settings"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackRow(stringResource(R.string.settings_title), onBack)
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("languages")) {
                listOf(null to stringResource(R.string.settings_language_system), "iw" to "עברית", "en" to "English")
                    .forEach { (tag, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = actions.currentLanguage == tag,
                            onClick = { actions.onLanguage(tag) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("lang_${tag ?: "system"}"),
                        )
                    }
            }
            Card(Modifier.fillMaxWidth().testTag("getBack")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_get_back_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.settings_get_back_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (actions.trashBytes > 0) {
                Card(Modifier.fillMaxWidth().testTag("beforeUninstall")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.settings_uninstall_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(
                                R.string.trash_uninstall_note,
                                io.github.tomerar.keepclear.core.common.Bidi.isolate(
                                    io.github.tomerar.keepclear.core.common.ByteSize.format(
                                        actions.trashBytes,
                                        androidx.compose.ui.platform.LocalConfiguration.current.locales[0],
                                    ),
                                ),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = actions.onOpenTrash, modifier = Modifier.testTag("settingsOpenTrash")) {
                            Text(stringResource(R.string.settings_open_trash))
                        }
                    }
                }
            }
            if (actions.choices.kept.isNotEmpty() || actions.choices.junk.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().testTag("keptByYou")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.group_user_kept), style = MaterialTheme.typography.titleSmall)
                        actions.choices.kept.sorted().forEach { path ->
                            ChoiceRow(path, R.string.choice_kept_label, "removeKept") { actions.onRemoveKept(path) }
                        }
                        actions.choices.junk.sorted().forEach { folder ->
                            ChoiceRow(folder, R.string.choice_junk_label, "removeJunk") { actions.onRemoveJunk(folder) }
                        }
                    }
                }
            }
            TextButton(onClick = actions.onReplayIntro, modifier = Modifier.testTag("replayIntro")) {
                Text(stringResource(R.string.settings_replay_intro))
            }
            Card(Modifier.fillMaxWidth().testTag("about")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.settings_about_body,
                            io.github.tomerar.keepclear.core.common.Bidi.isolate(actions.version),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = actions.onOpenPrivacy, modifier = Modifier.testTag("privacy")) {
                        Text(stringResource(R.string.settings_privacy))
                    }
                    actions.networkHost?.let { host ->
                        Text(
                            stringResource(
                                R.string.settings_network_only,
                                io.github.tomerar.keepclear.core.common.Bidi.isolate(host),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("networkOnly"),
                        )
                        TextButton(
                            onClick = actions.onForgetNetworkDrive,
                            modifier = Modifier.testTag("forgetNetwork"),
                        ) {
                            Text(stringResource(R.string.settings_network_forget))
                        }
                    }
                }
            }
            Text(stringResource(R.string.settings_advanced), style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.test_mode_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.test_mode_body), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = testModeOn,
                        onCheckedChange = { on -> if (on) onTestModeOn() else step = 1 },
                        modifier = Modifier.testTag("testModeSwitch"),
                    )
                }
            }
        }
    }
    when (step) {
        1 -> AlertDialog(
            onDismissRequest = { step = 0 },
            title = { Text(stringResource(R.string.test_mode_off_title)) },
            text = { Text(stringResource(R.string.test_mode_off_body)) },
            confirmButton = {
                TextButton(onClick = { step = 2 }, modifier = Modifier.testTag("testModeOff1")) {
                    Text(stringResource(R.string.test_mode_off_continue))
                }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.move_cancel)) } },
        )

        2 -> AlertDialog(
            onDismissRequest = { step = 0 },
            title = { Text(stringResource(R.string.test_mode_off_title2)) },
            text = { Text(stringResource(R.string.test_mode_off_body2)) },
            confirmButton = {
                SafeConfirmButton(
                    stringResource(R.string.test_mode_off_confirm),
                    onClick = {
                        step = 0
                        onTestModeOff()
                    },
                    modifier = Modifier.testTag("testModeOff2"),
                )
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.move_cancel)) } },
        )
    }
}

@Composable
private fun ChoiceRow(path: String, label: Int, tag: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            Text(
                io.github.tomerar.keepclear.core.common.Bidi.isolateLtr(path),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onRemove, modifier = Modifier.testTag("${tag}_$path")) {
            Text(stringResource(R.string.choice_remove))
        }
    }
}

/** ONB-1: three short screens on first launch; Settings can show them again. */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val pages = listOf(
        R.string.intro_1_title to R.string.intro_1_body,
        R.string.intro_2_title to R.string.intro_2_body,
        R.string.intro_3_title to R.string.intro_3_body,
    )
    var page by rememberSaveable { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().navigationBarsPadding().padding(24.dp).testTag("intro"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "${page + 1} / ${pages.size}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("introPage"),
            )
            Text(stringResource(pages[page].first), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(pages[page].second), style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page < pages.lastIndex) {
                    TextButton(onClick = onDone, modifier = Modifier.testTag("introSkip")) {
                        Text(stringResource(R.string.intro_skip))
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                androidx.compose.material3.Button(
                    onClick = { if (page < pages.lastIndex) page++ else onDone() },
                    modifier = Modifier.testTag("introNext"),
                ) {
                    Text(stringResource(if (page < pages.lastIndex) R.string.intro_next else R.string.intro_start))
                }
            }
        }
    }
}

/** Whether the intro was seen (ONB-1). */
object Onboarding {
    private const val PREFS = "onboarding"
    private const val KEY = "introSeen"

    fun seen(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setSeen(context: android.content.Context, value: Boolean) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY, value)
        }
}
