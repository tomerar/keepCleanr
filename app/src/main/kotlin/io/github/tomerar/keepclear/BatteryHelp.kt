// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.i18n.R

/** Owner brief H4 item 5: what the kill card, Help and Settings › Troubleshooting open. Screens only. */
class BatteryActions(
    val steps: MakerSteps = BatterySteps.forMaker(null),
    /** Android's battery-optimisation list (ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS). */
    val onAllowBackground: () -> Unit = {},
    /** The maker's page on dontkillmyapp.com, in the browser. */
    val onOpenGuide: () -> Unit = {},
)

/** "Allow the backup to run in the background", then this phone maker's steps and the link to more. */
@Composable
fun BatteryHelp(actions: BatteryActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KcButton(
            stringResource(R.string.battery_allow_background),
            onClick = actions.onAllowBackground,
            style = KcButtonStyle.Tonal,
            modifier = Modifier.fillMaxWidth().testTag("allowBackground"),
        )
        Text(
            stringResource(R.string.battery_maker_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        stringResource(actions.steps.steps).lines().filter { it.isNotBlank() }.forEach { line ->
            Text(
                "• $line",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("makerStep"),
            )
        }
        KcButton(
            stringResource(R.string.battery_maker_guide),
            onClick = actions.onOpenGuide,
            style = KcButtonStyle.Text,
            modifier = Modifier.testTag("makerGuide"),
        )
    }
}
