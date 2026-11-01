// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.scan.SpaceMode
import java.text.NumberFormat

/** What the explainer sheet is about (ONB-2). */
enum class Explainer { MEDIA, ALL_FILES }

class HomeActions(
    val onOpenTrash: () -> Unit = {},
    val onOpenCategory: (io.github.tomerar.keepclear.core.model.Category) -> Unit = {},
    val onRequestMedia: () -> Unit = {},
    val onRequestAllFiles: () -> Unit = {},
    val onScanAgain: () -> Unit = {},
    val onCancelScan: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

/**
 * Home (M3): storage line, permission cards with plain-language explainers, scan progress and
 * summary, low-space banners. Category cards stay "coming soon" until M5.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    showDebugTools: Boolean,
    modifier: Modifier = Modifier,
    actions: HomeActions = HomeActions(),
) {
    var forceFlip by rememberSaveable { mutableStateOf(false) }
    var explainer by rememberSaveable { mutableStateOf<Explainer?>(null) }
    val systemDirection = LocalLayoutDirection.current
    val direction = when {
        !forceFlip -> systemDirection
        systemDirection == LayoutDirection.Ltr -> LayoutDirection.Rtl
        else -> LayoutDirection.Ltr
    }
    val locale = LocalConfiguration.current.locales[0]

    fun size(bytes: Long) = ByteSize.format(bytes, locale)

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Scaffold(modifier = modifier.fillMaxSize()) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.trash.entries.isNotEmpty()) {
                        AssistChip(
                            onClick = actions.onOpenTrash,
                            label = { Text(stringResource(R.string.trash_chip, size(state.trash.bytes))) },
                            modifier = Modifier.testTag("trashChip"),
                        )
                    }
                }
                Text(
                    stringResource(R.string.home_tagline),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("tagline"),
                )
                if (state.totalBytes > 0) {
                    Text(
                        stringResource(
                            R.string.home_storage_line,
                            size(state.totalBytes - state.freeBytes),
                            size(state.freeBytes),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("storage"),
                    )
                    LinearProgressIndicator(
                        progress = { (state.totalBytes - state.freeBytes).toFloat() / state.totalBytes },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                when (state.spaceMode) {
                    SpaceMode.LIGHT -> Banner(stringResource(R.string.low_space_light))
                    SpaceMode.MINIMAL -> Banner(stringResource(R.string.low_space_minimal))
                    SpaceMode.NORMAL -> Unit
                }

                if (explainer != null) {
                    ExplainerCard(
                        explainer = explainer!!,
                        onContinue = {
                            val which = explainer
                            explainer = null
                            if (which == Explainer.MEDIA) actions.onRequestMedia() else actions.onRequestAllFiles()
                        },
                        onDismiss = { explainer = null },
                    )
                } else if (state.mediaAccess == MediaAccess.NONE) {
                    ActionCard(
                        title = stringResource(R.string.access_card_title),
                        body = stringResource(R.string.access_card_body),
                        button = stringResource(R.string.access_allow),
                        tag = "allowAccess",
                        onClick = { explainer = Explainer.MEDIA },
                    )
                } else {
                    if (state.mediaAccess == MediaAccess.PARTIAL) {
                        Banner(stringResource(R.string.partial_banner), stringResource(R.string.partial_change)) {
                            explainer = Explainer.MEDIA
                        }
                    }
                    ScanCard(state, actions, ::size)
                    if (state.allFiles == false) {
                        ActionCard(
                            title = stringResource(R.string.allfiles_card_title),
                            body = stringResource(R.string.allfiles_card_body),
                            button = stringResource(R.string.allfiles_card_button),
                            tag = "allowAllFiles",
                            onClick = { explainer = Explainer.ALL_FILES },
                        )
                    }
                }

                val summary = state.summary
                if (summary == null) {
                    CategoryCard(R.string.home_card_safe_title, R.string.home_card_safe_body)
                    CategoryCard(R.string.home_card_review_title, R.string.home_card_review_body)
                    CategoryCard(R.string.home_card_move_title, R.string.home_card_move_body)
                } else {
                    TotalCard(Category.SAFE, summary, R.string.home_total_safe_body, actions.onOpenCategory)
                    TotalCard(Category.REVIEW, summary, R.string.home_total_review_body, actions.onOpenCategory)
                    TotalCard(Category.MOVE, summary, R.string.home_total_move_body, actions.onOpenCategory)
                    Areas(summary)
                }
                Text(stringResource(R.string.home_privacy), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = actions.onOpenSettings, modifier = Modifier.testTag("openSettings")) {
                    Text(stringResource(R.string.settings_title))
                }
                if (showDebugTools) {
                    TextButton(onClick = { forceFlip = !forceFlip }) { Text(stringResource(R.string.debug_toggle_rtl)) }
                }
            }
        }
    }
}

@Composable
private fun ScanCard(state: HomeUiState, actions: HomeActions, size: (Long) -> String) {
    val numbers = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])

    @Composable
    fun files(n: Int) = pluralStringResource(R.plurals.file_count, n, numbers.format(n))
    Card(modifier = Modifier.fillMaxWidth().testTag("scanCard")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val scan = state.scan) {
                is ScanState.Running -> {
                    Text(
                        stringResource(R.string.scan_running, files(scan.files)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = actions.onCancelScan) { Text(stringResource(R.string.scan_cancel)) }
                }

                is ScanState.Done -> {
                    Text(
                        stringResource(R.string.scan_done, files(scan.files), size(scan.bytes)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!scan.complete) {
                        Text(
                            stringResource(R.string.scan_incomplete),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.allFiles !=
                        true
                    ) {
                        Text(stringResource(R.string.media_only_note), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = actions.onScanAgain) { Text(stringResource(R.string.scan_again)) }
                }

                ScanState.Idle -> {
                    OutlinedButton(onClick = actions.onScanAgain) { Text(stringResource(R.string.scan_again)) }
                }
            }
        }
    }
}

@Composable
private fun ExplainerCard(explainer: Explainer, onContinue: () -> Unit, onDismiss: () -> Unit) {
    val (title, why, never, without) = when (explainer) {
        Explainer.MEDIA -> listOf(
            R.string.explainer_media_title,
            R.string.explainer_media_why,
            R.string.explainer_media_never,
            R.string.explainer_media_without,
        )

        Explainer.ALL_FILES -> listOf(
            R.string.explainer_allfiles_title,
            R.string.explainer_allfiles_why,
            R.string.explainer_allfiles_never,
            R.string.explainer_allfiles_without,
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("explainer"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            Labeled(R.string.explainer_label_why, why)
            Labeled(R.string.explainer_label_never, never)
            Labeled(R.string.explainer_label_without, without)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onContinue, modifier = Modifier.testTag("explainerContinue")) {
                    Text(stringResource(R.string.explainer_continue))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.explainer_not_now)) }
            }
        }
    }
}

@Composable
private fun Labeled(label: Int, text: Int) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionCard(title: String, body: String, button: String, tag: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(button) }
        }
    }
}

@Composable
private fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("banner"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** SCAN-2: the three action totals, each opening its read-only list. Never counts PROTECTED. */
@Composable
private fun TotalCard(category: Category, summary: ClassificationSummary, body: Int, onOpen: (Category) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("total_$category")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(categoryTitle(category), style = MaterialTheme.typography.titleMedium)
            Text(totalText(summary.total(category)), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { onOpen(category) }, modifier = Modifier.testTag("open_$category")) {
                Text(stringResource(R.string.action_look))
            }
        }
    }
}

@Composable
private fun Areas(summary: ClassificationSummary) {
    val locale = LocalConfiguration.current.locales[0]
    val labels = mapOf(
        ClassificationSummary.Area.CAMERA to R.string.area_camera,
        ClassificationSummary.Area.WHATSAPP to R.string.area_whatsapp,
        ClassificationSummary.Area.JUNK to R.string.area_junk,
        ClassificationSummary.Area.OTHER to R.string.area_other,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("areas")) {
        Text(stringResource(R.string.areas_title), style = MaterialTheme.typography.titleSmall)
        summary.areas().forEach { (area, bytes) ->
            Text(
                "${stringResource(labels.getValue(area))}: ${ByteSize.format(bytes, locale)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CategoryCard(title: Int, body: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(R.string.home_coming_soon)) })
        }
    }
}
