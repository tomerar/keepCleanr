// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
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
fun AppsScreen(
    apps: List<AppInfo>?,
    now: Long,
    actions: AppsActions,
    /** null on the Apps tab (its root: no back arrow). */
    onBack: (() -> Unit)? = null,
    /** F1b: the tab's other parts (one app at a time, apps' own folders, the cloned-apps note). */
    links: AppsTabLinks? = null,
    /** F2: the list is still being read; [apps] == null then doesn't mean "no usage access". */
    loading: Boolean = false,
) {
    val locale = LocalConfiguration.current.locales[0]

    fun size(b: Long) = Bidi.isolate(ByteSize.format(b, locale))
    Scaffold(topBar = { if (onBack != null) SubScreenTopBar(stringResource(R.string.apps_title), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("apps"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (onBack == null) item { SectionHeading(stringResource(R.string.apps_title)) }
            links?.let { l -> item { AppsLinks(l) } }
            if (apps == null && loading) {
                item {
                    Column(
                        Modifier.fillMaxWidth().testTag("appsLoading"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(R.string.apps_loading), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                return@LazyColumn
            }
            if (apps == null) {
                item {
                    KcCard(Modifier.testTag("usageAccess")) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.apps_access_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(stringResource(R.string.apps_access_body), style = MaterialTheme.typography.bodyMedium)
                            KcButton(
                                stringResource(R.string.apps_access_button),
                                onClick = actions.onAllowUsageAccess,
                                modifier = Modifier.testTag("allowUsage"),
                            )
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
                    SectionHeading(stringResource(R.string.apps_unused_title))
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
            // F2: an app is listed once: the unused ones above, every other app here.
            val unusedPkgs = unused.mapTo(HashSet()) { it.pkg }
            val others = AppsReport.bySize(apps).filter { it.pkg !in unusedPkgs }
            if (others.isNotEmpty()) {
                item {
                    SectionHeading(stringResource(if (unused.isEmpty()) R.string.apps_all else R.string.apps_others))
                }
                items(others, key = { it.pkg }) { app -> AppRow(app, now, actions, uninstall = false) }
            }
        }
    }
}

/** F1b: what else the Apps tab holds besides the list of apps. */
class AppsTabLinks(
    /** SAFE-10: clear one app's temporary files at a time. */
    val onOneByOne: () -> Unit = {},
    /** SAFE-9: other apps' own folders; null when the scan found none. */
    val onAppContent: (() -> Unit)? = null,
    /** MSG-3: cloned apps' storage exists but KeepClear can't see it. */
    val clonedHidden: Boolean = false,
)

@Composable
private fun AppsLinks(links: AppsTabLinks) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KcButton(
            stringResource(R.string.one_by_one_button),
            onClick = links.onOneByOne,
            style = KcButtonStyle.Tonal,
            modifier = Modifier.testTag("appsOneByOne"),
        )
        links.onAppContent?.let { open ->
            KcButton(
                stringResource(R.string.app_content_title),
                onClick = open,
                style = KcButtonStyle.Text,
                modifier = Modifier.testTag("appsAppContent"),
            )
        }
        if (links.clonedHidden) {
            Text(
                stringResource(R.string.cloned_hidden_body),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("appsClonedNote"),
            )
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
        // F2: the app's own icon (decorative: its name is next to it).
        AppIcon(app.pkg)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(Bidi.isolate(app.label), style = MaterialTheme.typography.titleSmall)
            Text(Bidi.isolate(ByteSize.format(app.totalBytes, locale)), style = MaterialTheme.typography.bodyMedium)
            Text(lastUsedText(app, now), style = MaterialTheme.typography.bodySmall)
        }
        if (uninstall) {
            KcButton(
                stringResource(R.string.apps_uninstall),
                onClick = {
                    // SAFETY-9: removing an app deletes its data; trial mode never hands that to Android.
                    if (actions.testModeOn) onBlocked() else actions.onUninstall(app.pkg)
                },
                style = KcButtonStyle.Tonal,
                modifier = Modifier.testTag("uninstall_${app.pkg}"),
            )
        } else {
            KcButton(
                stringResource(R.string.app_info),
                onClick = { actions.onOpenAppInfo(app.pkg) },
                style = KcButtonStyle.Text,
                modifier = Modifier.testTag("info_${app.pkg}"),
            )
        }
    }
}

/** F2: loads app icons off the main thread; null in tests and previews (no icon is drawn then). */
class AppIconLoader(private val pm: android.content.pm.PackageManager) {
    private val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(64)

    suspend fun load(pkg: String, sizePx: Int): androidx.compose.ui.graphics.ImageBitmap? {
        cache.get(pkg)?.let { return it }
        val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                pm.getApplicationIcon(pkg).toBitmap(sizePx, sizePx).asImageBitmap()
            }.getOrNull()
        }
        bitmap?.let { cache.put(pkg, it) }
        return bitmap
    }
}

val LocalAppIcons = androidx.compose.runtime.staticCompositionLocalOf<AppIconLoader?> { null }

@Composable
internal fun AppIcon(pkg: String, sizeDp: Int = 40) {
    val loader = LocalAppIcons.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { sizeDp.dp.roundToPx() }
    val icon by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pkg, loader) {
        value = loader?.load(pkg, px)
    }
    androidx.compose.foundation.layout.Box(Modifier.size(sizeDp.dp)) {
        icon?.let {
            androidx.compose.foundation.Image(it, contentDescription = null, modifier = Modifier.size(sizeDp.dp))
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
