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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.engine.classify.Total

/** An installed app as KeepClear shows it (SAFE-9/10). */
data class AppEntry(val pkg: String, val label: String, val cacheBytes: Long? = null)

/** Actions that open another app or Android's app-info screen. KeepClear never taps anything inside them. */
class AppActions(
    val labelOf: (String) -> String = { it },
    val onOpenApp: (String) -> Unit = {},
    val onOpenAppInfo: (String) -> Unit = {},
)

/** Per-app hint for where to clear its own downloads (SAFE-9); generic for unknown apps. */
fun appHint(pkg: String): Int = when {
    pkg.startsWith("com.spotify") -> {
        R.string.app_hint_spotify
    }

    pkg.startsWith("org.telegram") || pkg.startsWith("org.thunderdog.challegram") -> {
        R.string.app_hint_telegram
    }

    pkg.startsWith("com.google.android.youtube") || pkg.startsWith("com.google.android.apps.youtube") -> {
        R.string.app_hint_youtube
    }

    pkg.startsWith("com.netflix") -> {
        R.string.app_hint_netflix
    }

    else -> {
        R.string.app_hint_generic
    }
}

/**
 * SAFE-9: large content in other apps' own folders, per app with its size, cleared **inside that app**. There is
 * no delete action here, and `Android/data` is PROTECTED at the choke point.
 */
@Composable
fun AppContentScreen(apps: List<Pair<String, Total>>, actions: AppActions, onBack: () -> Unit) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("appContent"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BackRow(stringResource(R.string.app_content_title), onBack)
                Text(stringResource(R.string.app_content_body), style = MaterialTheme.typography.bodyMedium)
            }
            items(apps, key = { it.first }) { (pkg, total) ->
                Card(Modifier.fillMaxWidth().testTag("app_$pkg")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(Bidi.isolate(actions.labelOf(pkg)), style = MaterialTheme.typography.titleMedium)
                        Text(totalText(total))
                        Text(stringResource(appHint(pkg)), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { actions.onOpenApp(pkg) },
                                modifier = Modifier.testTag("openApp_$pkg"),
                            ) { Text(stringResource(R.string.app_open)) }
                            TextButton(
                                onClick = { actions.onOpenAppInfo(pkg) },
                                modifier = Modifier.testTag("appInfo_$pkg"),
                            ) { Text(stringResource(R.string.app_info)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * SAFE-10: "Clear one app at a time". The user opens Android's app-info screen and taps Android's own "Clear
 * cache"; on return KeepClear shows the space actually freed (measured). No special permission.
 */
@Composable
fun OneAppAtATimeScreen(apps: List<AppEntry>, freed: Map<String, Long>, actions: AppActions, onBack: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("oneByOne"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BackRow(stringResource(R.string.one_by_one_title), onBack)
                Text(stringResource(R.string.one_by_one_body), style = MaterialTheme.typography.bodyMedium)
                if (apps.none { it.cacheBytes != null }) {
                    Text(stringResource(R.string.one_by_one_sizes_later), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(apps, key = { it.pkg }) { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("oneApp_${app.pkg}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(Bidi.isolate(app.label), style = MaterialTheme.typography.titleSmall)
                        app.cacheBytes?.let { bytes ->
                            Text(
                                stringResource(R.string.apps_cache, Bidi.isolate(ByteSize.format(bytes, locale))),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("cache_${app.pkg}"),
                            )
                        }
                        freed[app.pkg]?.let { bytes ->
                            Text(
                                if (bytes > 0) {
                                    stringResource(
                                        R.string.one_by_one_freed,
                                        Bidi.isolate(ByteSize.format(bytes, locale)),
                                    )
                                } else {
                                    stringResource(R.string.one_by_one_nothing)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("freed_${app.pkg}"),
                            )
                        }
                    }
                    TextButton(
                        onClick = { actions.onOpenAppInfo(app.pkg) },
                        modifier = Modifier.testTag("openInfo_${app.pkg}"),
                    ) { Text(stringResource(R.string.app_info)) }
                }
            }
        }
    }
}
