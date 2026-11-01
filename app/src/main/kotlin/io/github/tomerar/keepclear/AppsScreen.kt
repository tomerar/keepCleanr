// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.engine.classify.AppInfo
import io.github.tomerar.keepclear.engine.classify.AppsReport

class AppsActions(
    val onAllowUsageAccess: () -> Unit = {},
    val onUninstall: (String) -> Unit = {},
    val onOpenAppInfo: (String) -> Unit = {},
    /** SAFETY-9: in trial mode Uninstall explains instead of opening Android's uninstall window. */
    val testModeOn: Boolean = false,
)

/**
 * M13 (ux-flows §7): apps by size, last opened, and the "not opened in about 6 months" bucket with Android's own
 * uninstall dialog. Without usage access it explains why it's needed first (ONB-7). Nothing is removed by KeepClear.
 */
@Composable
fun AppsScreen(apps: List<AppInfo>?, now: Long, actions: AppsActions, onBack: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]

    fun size(b: Long) = Bidi.isolate(ByteSize.format(b, locale))
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("apps"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { BackRow(stringResource(R.string.apps_title), onBack) }
            if (apps == null) {
                item {
                    Card(Modifier.fillMaxWidth().testTag("usageAccess")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.apps_access_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(stringResource(R.string.apps_access_body), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = actions.onAllowUsageAccess, modifier = Modifier.testTag("allowUsage")) {
                                Text(stringResource(R.string.apps_access_button))
                            }
                        }
                    }
                }
                return@LazyColumn
            }
            item {
                Text(
                    stringResource(R.string.apps_total, size(apps.sumOf { it.totalBytes })),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("appsTotal"),
                )
            }
            val unused = AppsReport.unused(apps, now)
            if (unused.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.apps_unused_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            R.string.total_value,
                            size(unused.sumOf { it.totalBytes }),
                            countSentence(R.plurals.app_count, unused.size),
                        ),
                        modifier = Modifier.testTag("unusedTotal"),
                    )
                    Text(stringResource(R.string.apps_unused_body), style = MaterialTheme.typography.bodySmall)
                }
                items(unused, key = { "unused_${it.pkg}" }) { app -> AppRow(app, now, actions, uninstall = true) }
            }
            item { Text(stringResource(R.string.apps_all), style = MaterialTheme.typography.titleMedium) }
            items(AppsReport.bySize(apps), key = { it.pkg }) { app -> AppRow(app, now, actions, uninstall = false) }
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, now: Long, actions: AppsActions, uninstall: Boolean) {
    var blocked by rememberSaveable { mutableStateOf(false) }
    // One column: a lazy item with two root composables would draw them on top of each other.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppRowContent(app, now, actions, uninstall, blocked) { blocked = true }
    }
}

@Composable
private fun AppRowContent(
    app: AppInfo,
    now: Long,
    actions: AppsActions,
    uninstall: Boolean,
    blocked: Boolean,
    onBlocked: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    if (blocked) {
        Text(
            stringResource(R.string.test_mode_uninstall_blocked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(8.dp)
                .testTag("uninstallTrial_${app.pkg}"),
        )
    }
    Row(
        Modifier.fillMaxWidth().testTag(if (uninstall) "unused_${app.pkg}" else "app_${app.pkg}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(Bidi.isolate(app.label), style = MaterialTheme.typography.titleSmall)
            Text(Bidi.isolate(ByteSize.format(app.totalBytes, locale)), style = MaterialTheme.typography.bodyMedium)
            Text(lastUsedText(app, now), style = MaterialTheme.typography.bodySmall)
        }
        if (uninstall) {
            OutlinedButton(onClick = {
                // SAFETY-9: removing an app deletes its data; trial mode never hands that to Android.
                if (actions.testModeOn) onBlocked() else actions.onUninstall(app.pkg)
            }, modifier = Modifier.testTag("uninstall_${app.pkg}")) {
                Text(stringResource(R.string.apps_uninstall))
            }
        } else {
            TextButton(onClick = { actions.onOpenAppInfo(app.pkg) }, modifier = Modifier.testTag("info_${app.pkg}")) {
                Text(stringResource(R.string.app_info))
            }
        }
    }
}

@Composable
private fun lastUsedText(app: AppInfo, now: Long): String {
    val last = app.lastUsedMillis ?: return stringResource(R.string.apps_never_used)
    val months = AppsReport.monthsSince(last, now).toInt()
    return if (months ==
        0
    ) {
        stringResource(R.string.apps_used_this_month)
    } else {
        countSentence(R.plurals.apps_last_used, months)
    }
}
