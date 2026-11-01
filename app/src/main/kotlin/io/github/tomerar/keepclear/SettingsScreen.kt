// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * Settings (M11 adds more). **Advanced** holds the test-mode switch: turning test mode on is one tap; turning it
 * off needs two confirmations, because from then on actions really delete and move files.
 */
@Composable
fun SettingsScreen(testModeOn: Boolean, onTestModeOn: () -> Unit, onTestModeOff: () -> Unit, onBack: () -> Unit) {
    // 0 = no dialog, 1 = first confirmation, 2 = second confirmation.
    var step by rememberSaveable { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("settings"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackRow(stringResource(R.string.settings_title), onBack)
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
