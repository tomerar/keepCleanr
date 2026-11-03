// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmSheet
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
import io.github.tomerar.keepclear.core.designsystem.component.IntroIllustration
import io.github.tomerar.keepclear.core.designsystem.component.IntroPicture
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.i18n.R

/** PR H3: a Settings row that opens another screen: the whole card is the target, with a chevron. */
@Composable
private fun LinkCard(title: String, body: String, tag: String, onClick: () -> Unit) {
    KcCard(Modifier.testTag(tag), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.Icon(
                io.github.tomerar.keepclear.core.designsystem.KcIcons.Chevron,
                contentDescription = null,
            )
        }
    }
}

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
    /** M15: the network drive's address, the only place KeepClear connects to; null when none is set up. */
    val networkHost: String? = null,
    val onForgetNetworkDrive: () -> Unit = {},
    /** F2 (C76): set up the computer at home here (Advanced); KeepClear connects once and saves it if that worked. */
    val onSetUpNetworkDrive: (io.github.tomerar.keepclear.backup.smb.SmbLocation) -> Unit = {},
    val savedNetworkDrive: () -> io.github.tomerar.keepclear.backup.smb.SmbLocation? = { null },
    val networkSetup: NetworkSetup? = null,
    val onNetworkSetupSeen: () -> Unit = {},
    /** M17b: the Nextcloud's host (Advanced), another place KeepClear may connect to; null when none is set up. */
    val nextcloudHost: String? = null,
    val onForgetNextcloud: () -> Unit = {},
    /** D6: true while "Forget" revokes the app password on the server. */
    val forgettingNextcloud: Boolean = false,
    /** D6: what forgetting achieved on the server, shown once after it; null before. */
    val nextcloudForgot: io.github.tomerar.keepclear.backup.webdav.NextcloudRevoke? = null,
    val onOpenNextcloud: () -> Unit = {},
    /** CLS-7 / SET-2: what the user kept or marked as junk, and how to undo it. */
    val choices: Choices = Choices(),
    val onRemoveKept: (String) -> Unit = {},
    val onRemoveJunk: (String) -> Unit = {},
    /** Owner decision P1: "Keep the original in the 30-day trash too (frees no space)", off by default. */
    val keepOriginalsInTrash: Boolean = false,
    val onKeepOriginalsInTrash: (Boolean) -> Unit = {},
    /** PR H2b: „להחליף את כיווני ההחלקה” (off: a swipe to the left deletes, C148). */
    val swipeSwapped: Boolean = false,
    val onSwipeSwapped: (Boolean) -> Unit = {},
    /** SET-3: "Remind me when space is low" (off by default). */
    val reminderOn: Boolean = false,
    val onReminder: (Boolean) -> Unit = {},
    /** Owner brief H1 item 11: "Remind me to check the backup" (off by default). */
    val checkReminderOn: Boolean = false,
    val onCheckReminder: (Boolean) -> Unit = {},
    /** F2: Android 13+ asks before showing notifications; the reminder explains first, then asks. */
    val needsNotificationPermission: Boolean = false,
    /** Asks Android for the notification permission and reports whether it was granted. */
    val onAskNotifications: ((granted: Boolean) -> Unit) -> Unit = { it(true) },
    /** Test builds only ([io.github.tomerar.keepclear.devcheck.DeviceCheckGate]): Advanced › Device check. */
    val showDeviceCheck: Boolean = false,
    val onOpenDeviceCheck: () -> Unit = {},
    /** PR H3 (owner brief H3 item 2): opens the "Report a problem" sheet. */
    val onReport: () -> Unit = {},
    /** Owner brief H4 item 7: Android 13+'s own per-app language page; null below Android 13. */
    val onSystemLanguage: (() -> Unit)? = null,
    /** Owner brief H4 item 4: opens one notification channel's page in Android's settings; null hides the rows. */
    val onOpenChannel: ((String) -> Unit)? = null,
    /** Owner brief H4 item 5: Settings › Troubleshooting; null hides it. */
    val troubleshooting: TroubleshootingUi? = null,
)

/** Owner brief H4 item 5: "Troubleshooting": when a backup last ran, and how to keep the phone from stopping it. */
class TroubleshootingUi(
    val lastRunAt: Long?,
    /** The newest backup was stopped by the phone. */
    val stoppedByPhone: Boolean = false,
    val battery: BatteryActions = BatteryActions(),
)

/** PR H3: the screens Settings links to (screens only; nothing here acts on files). */
class SettingsNav(
    val onHelp: () -> Unit = {},
    val onHistory: () -> Unit = {},
    val onAbout: () -> Unit = {},
    val onTrash: () -> Unit = {},
    val onRestoreFromBackup: () -> Unit = {},
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
    nav: SettingsNav = SettingsNav(),
) {
    // 0 = no dialog, 1 = first confirmation, 2 = second confirmation.
    var step by rememberSaveable { mutableIntStateOf(0) }
    var explainReminder by rememberSaveable { mutableStateOf(false) }
    var networkForm by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { SubScreenTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("settings"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeading(stringResource(R.string.settings_language))
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
            actions.onSystemLanguage?.let { open ->
                KcButton(
                    stringResource(R.string.settings_language_system_screen),
                    open,
                    Modifier.testTag("systemLanguage"),
                    style = KcButtonStyle.Text,
                )
            }
            // Owner brief H3 item 1: Help, one tap from Settings (and from the top bar's "?").
            LinkCard(
                stringResource(R.string.help_title),
                stringResource(R.string.settings_help_body),
                "openHelpSettings",
                nav.onHelp,
            )
            // Owner brief H3 item 1: the card links to the Trash and to restoring from a backup.
            KcCard(Modifier.testTag("getBack")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_get_back_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.settings_get_back_body), style = MaterialTheme.typography.bodyMedium)
                    KcButton(
                        stringResource(R.string.settings_get_back_trash),
                        nav.onTrash,
                        Modifier.testTag("getBackTrash"),
                        style = KcButtonStyle.Text,
                    )
                    KcButton(
                        stringResource(R.string.restore_start),
                        nav.onRestoreFromBackup,
                        Modifier.testTag("getBackRestore"),
                        style = KcButtonStyle.Text,
                    )
                }
            }
            if (actions.trashBytes > 0) {
                KcCard(Modifier.testTag("beforeUninstall")) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    }
                }
            }
            if (actions.choices.kept.isNotEmpty() || actions.choices.junk.isNotEmpty()) {
                KcCard(Modifier.testTag("keptByYou")) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            val reminderLabel = stringResource(R.string.reminder_setting)
            KcCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(reminderLabel, style = MaterialTheme.typography.titleSmall)
                        Text(
                            reminderBody(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("reminderBody"),
                        )
                    }
                    Switch(
                        checked = actions.reminderOn,
                        // F2: turning it on without the notification permission explains first; it stays off
                        // unless Android allows notifications (a reminder nobody can see is no reminder).
                        onCheckedChange = { on ->
                            if (on && actions.needsNotificationPermission) {
                                explainReminder = true
                            } else {
                                actions.onReminder(on)
                            }
                        },
                        // M18 / A11Y-1: TalkBack names the switch, not just "Switch, off".
                        modifier = Modifier.testTag("reminderSwitch").semantics { contentDescription = reminderLabel },
                    )
                }
            }
            val checkLabel = stringResource(R.string.check_reminder_setting)
            KcCard(Modifier.testTag("checkReminder")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(checkLabel, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.check_reminder_setting_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = actions.checkReminderOn,
                        // Asked in context: Android's notification question comes only when this is turned on.
                        onCheckedChange = { on ->
                            if (on && actions.needsNotificationPermission) {
                                actions.onAskNotifications { granted -> if (granted) actions.onCheckReminder(true) }
                            } else {
                                actions.onCheckReminder(on)
                            }
                        },
                        modifier = Modifier.testTag("checkReminderSwitch").semantics {
                            contentDescription = checkLabel
                        },
                    )
                }
            }
            val keepLabel = stringResource(R.string.settings_keep_originals)
            KcCard(Modifier.testTag("keepOriginals")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(keepLabel, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_keep_originals_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = actions.keepOriginalsInTrash,
                        onCheckedChange = actions.onKeepOriginalsInTrash,
                        // M18 / A11Y-1: TalkBack names the switch, not just "Switch, off".
                        modifier = Modifier.testTag("keepOriginalsSwitch").semantics { contentDescription = keepLabel },
                    )
                }
            }
            val swapLabel = stringResource(R.string.settings_swipe_swap)
            KcCard(Modifier.testTag("swipeSwap")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(swapLabel, style = MaterialTheme.typography.titleSmall)
                        // Says what the switch does now, in the words of the swipe screen's hint.
                        Text(
                            stringResource(
                                if (SwipeSession.deleteOnLeft(actions.swipeSwapped)) {
                                    R.string.swipe_dir_left_deletes
                                } else {
                                    R.string.swipe_dir_right_deletes
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("swipeSwapNow"),
                        )
                    }
                    Switch(
                        checked = actions.swipeSwapped,
                        onCheckedChange = actions.onSwipeSwapped,
                        // M18 / A11Y-1: TalkBack names the switch, not just "Switch, off".
                        modifier = Modifier.testTag("swipeSwapSwitch").semantics { contentDescription = swapLabel },
                    )
                }
            }
            actions.onOpenChannel?.let { open -> NotificationRows(open) }
            actions.troubleshooting?.let { TroubleshootingSection(it) }
            KcButton(
                stringResource(R.string.settings_replay_intro),
                actions.onReplayIntro,
                Modifier.testTag("replayIntro"),
                style = KcButtonStyle.Text,
            )
            // Owner brief H3 items 4 and 2.
            LinkCard(
                stringResource(R.string.history_title),
                stringResource(R.string.settings_history_body),
                "openHistory",
                nav.onHistory,
            )
            LinkCard(
                stringResource(R.string.report_title),
                stringResource(R.string.settings_report_body),
                "openReport",
                actions.onReport,
            )
            KcCard(Modifier.testTag("about")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.settings_about_body,
                            io.github.tomerar.keepclear.core.common.Bidi.isolate(actions.version),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    KcButton(
                        stringResource(R.string.settings_privacy),
                        actions.onOpenPrivacy,
                        Modifier.testTag("privacy"),
                        style = KcButtonStyle.Text,
                    )
                    // Owner brief H3 item 5: version, source, licences, translations.
                    KcButton(
                        stringResource(R.string.settings_about_open),
                        nav.onAbout,
                        Modifier.testTag("openAbout"),
                        style = KcButtonStyle.Text,
                    )
                }
            }
            SectionHeading(stringResource(R.string.settings_advanced))
            val testModeLabel = stringResource(R.string.test_mode_title)
            KcCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(testModeLabel, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.test_mode_body), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = testModeOn,
                        onCheckedChange = { on -> if (on) onTestModeOn() else step = 1 },
                        // M18 / A11Y-1: TalkBack names the switch, not just "Switch, off".
                        modifier = Modifier.testTag("testModeSwitch").semantics { contentDescription = testModeLabel },
                    )
                }
            }
            // F1b: the backup places (Advanced only; the default path never shows them). M15/M17b: the only
            // addresses KeepClear may connect to are shown here, with a way to forget them (PRIVACY.md "Network").
            KcCard(Modifier.testTag("backupPlacesSettings")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.backup_places_title), style = MaterialTheme.typography.titleSmall)
                    // F2 (C76): the computer at home is set up here, not in the backup picker.
                    Text(stringResource(R.string.move_network_option), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.settings_network_body), style = MaterialTheme.typography.bodySmall)
                    KcButton(
                        stringResource(R.string.settings_network_button),
                        onClick = {
                            actions.onNetworkSetupSeen()
                            networkForm = true
                        },
                        enabled = actions.networkSetup != NetworkSetup.Checking,
                        style = KcButtonStyle.Text,
                        modifier = Modifier.testTag("setUpNetwork"),
                    )
                    NetworkSetupLine(actions.networkSetup)
                    Text(stringResource(R.string.nextcloud_title), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.nextcloud_settings_body), style = MaterialTheme.typography.bodySmall)
                    KcButton(
                        stringResource(R.string.nextcloud_settings_button),
                        actions.onOpenNextcloud,
                        Modifier.testTag("openNextcloud"),
                        style = KcButtonStyle.Text,
                    )
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
                    actions.nextcloudHost?.let { host ->
                        Text(
                            stringResource(
                                R.string.settings_nextcloud_only,
                                io.github.tomerar.keepclear.core.common.Bidi.isolate(host),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("nextcloudOnly"),
                        )
                        TextButton(
                            onClick = actions.onForgetNextcloud,
                            enabled = !actions.forgettingNextcloud,
                            modifier = Modifier.testTag("forgetNextcloud"),
                        ) {
                            Text(stringResource(R.string.settings_nextcloud_forget))
                        }
                    }
                    actions.nextcloudForgot?.let { result ->
                        Text(
                            stringResource(
                                if (result == io.github.tomerar.keepclear.backup.webdav.NextcloudRevoke.NOT_REVOKED) {
                                    R.string.settings_nextcloud_forgot_not_revoked
                                } else {
                                    R.string.settings_nextcloud_forgot_revoked
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("nextcloudForgot"),
                        )
                    }
                }
            }
            if (actions.showDeviceCheck) {
                KcCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.debug_device_check_button_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = actions.onOpenDeviceCheck,
                            modifier = Modifier.testTag("openDeviceCheck"),
                        ) {
                            Text(stringResource(R.string.debug_device_check_button))
                        }
                    }
                }
            }
        }
    }
    if (networkForm) {
        NetworkDriveDialog(
            saved = remember { actions.savedNetworkDrive() },
            onConnect = { location ->
                networkForm = false
                actions.onSetUpNetworkDrive(location)
            },
            onDismiss = { networkForm = false },
        )
    }
    if (explainReminder) {
        InfoSheet(
            title = stringResource(R.string.reminder_setting),
            dismissText = stringResource(R.string.explainer_not_now),
            onDismiss = { explainReminder = false },
            modifier = Modifier.testTag("reminderExplain"),
            primaryButton = {
                KcButton(
                    stringResource(R.string.explainer_continue),
                    onClick = {
                        explainReminder = false
                        actions.onAskNotifications { granted -> if (granted) actions.onReminder(true) }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("reminderContinue"),
                )
            },
        ) {
            Text(stringResource(R.string.reminder_notif_explain), style = MaterialTheme.typography.bodyMedium)
        }
    }
    when (step) {
        1 -> ConfirmSheet(
            title = stringResource(R.string.test_mode_off_title),
            dismissText = stringResource(R.string.move_cancel),
            onDismiss = { step = 0 },
            note = stringResource(R.string.test_mode_off_body),
            confirmButton = {
                KcButton(
                    stringResource(R.string.test_mode_off_continue),
                    onClick = { step = 2 },
                    style = KcButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth().testTag("testModeOff1"),
                )
            },
        )

        2 -> ConfirmSheet(
            title = stringResource(R.string.test_mode_off_title2),
            dismissText = stringResource(R.string.move_cancel),
            onDismiss = { step = 0 },
            note = stringResource(R.string.test_mode_off_body2),
            confirmButton = {
                SafeConfirmButton(
                    stringResource(R.string.test_mode_off_confirm),
                    onClick = {
                        step = 0
                        onTestModeOff()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("testModeOff2"),
                    // Turning test mode off loses nothing (it can be turned on again): ink, not red (F2).
                    permanent = false,
                )
            },
        )
    }
}

/** F2: the result of setting up the computer at home, in plain words. */
@Composable
private fun NetworkSetupLine(setup: NetworkSetup?) {
    val text = when (setup) {
        null -> return

        NetworkSetup.Checking -> stringResource(R.string.settings_network_checking)

        is NetworkSetup.Saved -> stringResource(
            R.string.settings_network_saved,
            io.github.tomerar.keepclear.core.common.Bidi.isolate(setup.host),
        )

        is NetworkSetup.Failed -> stringResource(
            when (setup.problem) {
                TargetProblem.REFUSED -> R.string.move_problem_refused
                TargetProblem.NETWORK_ON_THIS_PHONE -> R.string.move_problem_network_phone
                else -> R.string.move_problem_unreachable
            },
        )
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("networkSetupResult"))
    if (setup == NetworkSetup.Checking) LinearProgressIndicator(Modifier.fillMaxWidth())
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
        KcButton(
            stringResource(R.string.choice_remove),
            onRemove,
            Modifier.testTag("${tag}_$path"),
            style = KcButtonStyle.Text,
        )
    }
}

/**
 * ONB-1: three short screens on first launch; Settings can show them again. Owner brief H2 item 1: a picture per page
 * ([IntroPicture]), dots that TalkBack reads as "1 of 3", the honest trash promise, and "New phone? Restore from a
 * backup" ([onRestore]) straight into the restore flow.
 */
@Composable
fun IntroScreen(onDone: () -> Unit, onRestore: () -> Unit = onDone) {
    val pages = listOf(
        Triple(R.string.intro_1_title, R.string.intro_1_body, IntroPicture.STRIP),
        Triple(R.string.intro_2_title, R.string.intro_2_body, IntroPicture.CHOOSE),
        Triple(R.string.intro_3_title, R.string.intro_3_body, IntroPicture.BACKUP),
    )
    var page by rememberSaveable { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(24.dp).testTag("intro"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IntroIllustration(
                pages[page].third,
                Modifier.align(Alignment.CenterHorizontally).testTag("introArt_${pages[page].third.name}"),
            )
            PageDots(page, pages.size)
            Text(
                stringResource(pages[page].first),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(pages[page].second), style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 8.dp))
            KcButton(
                stringResource(R.string.intro_restore),
                onRestore,
                Modifier.testTag("introRestore"),
                style = KcButtonStyle.Text,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page < pages.lastIndex) {
                    KcButton(
                        stringResource(R.string.intro_skip),
                        onDone,
                        Modifier.testTag("introSkip"),
                        style = KcButtonStyle.Text,
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                KcButton(
                    stringResource(if (page < pages.lastIndex) R.string.intro_next else R.string.intro_start),
                    onClick = { if (page < pages.lastIndex) page++ else onDone() },
                    modifier = Modifier.testTag("introNext"),
                )
            }
        }
    }
}

/** The intro's page dots: the current one filled in ink, the others outlined; TalkBack hears "1 of 3". */
@Composable
private fun PageDots(page: Int, count: Int) {
    val label = stringResource(R.string.intro_page, page + 1, count)
    val ink = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = label
            testTag = "introPage"
        },
    ) {
        repeat(count) { i ->
            androidx.compose.foundation.Canvas(Modifier.size(if (i == page) 10.dp else 8.dp)) {
                if (i == page) {
                    drawCircle(ink)
                } else {
                    drawCircle(outline, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

/** Whether the intro was seen (ONB-1). */
object Onboarding {
    private const val PREFS = "onboarding"
    private const val KEY = "introSeen"

    fun seen(context: android.content.Context): Boolean = MainThreadPolicy.settings {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun setSeen(context: android.content.Context, value: Boolean) = MainThreadPolicy.settings {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY, value)
        }
    }
}

/**
 * „…פחות מ-10% (או 2GB) פנוי”: the size comes from the reminder's own threshold, formatted like every size and
 * isolated, so it never reads "(או GB 2)" in Hebrew (owner brief H2 item 10).
 */
@Composable
internal fun reminderBody(): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    // ByteSize.format is already a left-to-right isolate.
    return stringResource(
        R.string.reminder_setting_body,
        io.github.tomerar.keepclear.core.common.ByteSize.format(ReminderPolicy.MIN_FREE_BYTES, locale),
    )
}

/** Owner brief H4 item 4: one row per notification channel, each opening its page in Android's settings. */
@Composable
private fun NotificationRows(open: (String) -> Unit) {
    SectionHeading(stringResource(R.string.settings_notifications))
    Text(stringResource(R.string.settings_notifications_body), style = MaterialTheme.typography.bodyMedium)
    NotificationChannels.all.forEach { channel ->
        LinkCard(stringResource(channel.name), "", "channel_${channel.id}") { open(channel.id) }
    }
}

/** Owner brief H4 item 5: „פתרון בעיות”. */
@Composable
private fun TroubleshootingSection(ui: TroubleshootingUi) {
    SectionHeading(stringResource(R.string.settings_troubleshooting), Modifier.testTag("troubleshooting"))
    KcCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                ui.lastRunAt?.let { at ->
                    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                    stringResource(
                        R.string.troubleshooting_last_run,
                        io.github.tomerar.keepclear.core.common.Bidi.isolate(lastRunText(at, locale)),
                    )
                } ?: stringResource(R.string.troubleshooting_never),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("lastBackupRun"),
            )
            if (ui.stoppedByPhone) {
                Text(stringResource(R.string.troubleshooting_stopped), style = MaterialTheme.typography.bodyMedium)
            }
            BatteryHelp(ui.battery)
        }
    }
}

/** "3 Nov 2026, 14:05" in the app's language and the phone's time zone. */
internal fun lastRunText(at: Long, locale: java.util.Locale): String =
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(
        java.time.format.FormatStyle.MEDIUM,
        java.time.format.FormatStyle.SHORT,
    ).withLocale(locale)
        .format(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()))
