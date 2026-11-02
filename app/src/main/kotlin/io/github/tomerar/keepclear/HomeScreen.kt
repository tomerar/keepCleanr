// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcLane
import io.github.tomerar.keepclear.core.designsystem.component.HeroNumber
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.designsystem.component.LaneCard
import io.github.tomerar.keepclear.core.designsystem.component.StorageStrip
import io.github.tomerar.keepclear.core.designsystem.component.StorageUsage
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.Confirmation
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.PhoneStorage
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.scan.SpaceMode
import java.text.NumberFormat

/** What the explainer sheet is about (ONB-2). */
enum class Explainer { MEDIA, ALL_FILES, WHATSAPP }

class HomeActions(
    val onOpenTrash: () -> Unit = {},
    val onOpenCategory: (Category) -> Unit = {},
    val onRequestMedia: () -> Unit = {},
    val onRequestAllFiles: () -> Unit = {},
    val onScanAgain: () -> Unit = {},
    val onCancelScan: () -> Unit = {},
    val onTrim: () -> Unit = {},
    /** SAFE-10: the guided per-app list. */
    val onOneByOne: () -> Unit = {},
    /** M12b, ONB-6: open the system folder picker at WhatsApp's folder. */
    val onPickWhatsApp: () -> Unit = {},
    val onDismissWrongFolder: () -> Unit = {},
    /** F1b: the MOVE card jumps to the Backup tab. */
    val onOpenBackup: () -> Unit = {},
    /** F1b: the main button cleans the SAFE items on the phone, after the usual confirmation (SAFE-1). */
    val onClean: (List<ClassifiedItem>, Confirmation) -> Unit = { _, _ -> },
    val onUndo: (CleanResult, Confirmation) -> Unit = { _, _ -> },
)

/**
 * The "Free up space" tab (F1b, design-system.md §12): the free space with the storage strip and the one main
 * button (SAFE, straight to its confirmation; "See details" opens Clean now), the REVIEW card, the MOVE card (to the
 * Backup tab), apps' temporary files, ONE collapsed "See more files" card with the permission steps, and the
 * dismissible notices at the bottom. Explainers open as sheets from what was tapped (ONB-2). The top app bar
 * (wordmark, Trash chip, Settings) and the tabs are [MainTabs].
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
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("homeScreen"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val summary = state.summary
                // F2: the measured result of the last action, on top, where the eye is after tapping.
                state.measured?.takeIf { it.origin == Screen.Home }?.let { MeasuredBanner(it) }
                state.cleanResult?.takeIf { it.origin == Screen.Home }?.let { r ->
                    CleanResultBanner(r, actions.onUndo, actions.onOpenTrash)
                }
                // F2: a running action shows progress, not just disabled buttons.
                if (state.busy) BusyLine()
                when (state.spaceMode) {
                    SpaceMode.LIGHT -> Banner(stringResource(R.string.low_space_light))
                    SpaceMode.MINIMAL -> Banner(stringResource(R.string.low_space_minimal))
                    SpaceMode.NORMAL -> Unit
                }
                val pending = if (state.mediaAccess == MediaAccess.NONE) {
                    R.string.home_card_needs_access
                } else {
                    R.string.home_card_counting
                }
                // The main button cleans what the hero counts: SAFE on the phone's own storage (D5, C74).
                val safeItems = remember(summary) {
                    summary?.groups(Category.SAFE)?.flatMap { it.items }
                        ?.filter { PhoneStorage.contains(it.item.path) }.orEmpty()
                }
                var confirmClean by rememberSaveable { mutableStateOf(false) }
                val safeAction: @Composable () -> Unit = {
                    SafeAction(summary, safeItems, pending, enabled = !state.busy, actions) { confirmClean = true }
                }
                if (state.totalBytes > 0) {
                    HeroNumber(
                        bytes = state.freeBytes,
                        label = stringResource(R.string.home_free_label),
                        sentence = summary?.let {
                            stringResource(
                                R.string.home_hero_safe,
                                Bidi.isolate(size(it.phoneTotal(Category.SAFE).bytes)),
                            )
                        },
                        strip = {
                            StorageStrip(
                                usage = StorageUsage(
                                    total = state.totalBytes,
                                    free = state.freeBytes,
                                    safe = summary?.phoneTotal(Category.SAFE)?.bytes ?: 0,
                                    review = summary?.phoneTotal(Category.REVIEW)?.bytes ?: 0,
                                    move = summary?.phoneTotal(Category.MOVE)?.bytes ?: 0,
                                ),
                                description = stripDescription(state, summary, ::size),
                                modifier = Modifier.testTag("storage"),
                            )
                            // F2: "Where your space is" is the strip's legend now, not a section of its own.
                            if (summary != null) StripLegend(summary, ::size)
                        },
                        action = safeAction,
                        modifier = Modifier.testTag("hero"),
                    )
                } else {
                    KcCard(Modifier.testTag("safeCard")) { safeAction() }
                }
                if (confirmClean && safeItems.isNotEmpty()) {
                    CleanConfirmDialog(safeItems, onClean = actions.onClean, onDismiss = { confirmClean = false })
                }

                if (state.mediaAccess == MediaAccess.NONE) {
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
                    ScanLine(state, actions, ::size)
                }
                // M12b, ONB-6: the folder picked wasn't WhatsApp's (feedback on what was just tapped).
                if (state.wrongFolder) {
                    Banner(
                        stringResource(R.string.whatsapp_wrong_folder),
                        stringResource(R.string.whatsapp_try_again),
                    ) {
                        actions.onDismissWrongFolder()
                        actions.onPickWhatsApp()
                    }
                }

                LaneTotal(Category.REVIEW, summary, pending, R.string.home_total_review_body) {
                    actions.onOpenCategory(Category.REVIEW)
                }
                LaneTotal(Category.MOVE, summary, pending, R.string.home_total_move_body, actions.onOpenBackup)
                // ONB-3 / SAFE-5: always available, even with no permission at all.
                TrimCard(
                    result = state.trimResult.takeIf { state.screens.last() == Screen.Home },
                    enabled = !state.busy,
                    onTrim = actions.onTrim,
                    onOneByOne = actions.onOneByOne,
                )
                MoreFilesCard(state, onExplain = { explainer = it })
                // Notices at the bottom; closing one hides it for this visit (C75).
                var clonedDismissed by rememberSaveable { mutableStateOf(false) }
                if (state.cloned.hidden && !clonedDismissed) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Notice(
                        title = stringResource(R.string.cloned_hidden_title),
                        body = stringResource(R.string.cloned_hidden_body),
                        button = stringResource(R.string.cloned_hidden_button),
                        tag = "clonedHidden",
                        onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                            }
                        },
                        onDismiss = { clonedDismissed = true },
                    )
                }
                if (showDebugTools) {
                    KcButton(
                        stringResource(R.string.debug_toggle_rtl),
                        onClick = { forceFlip = !forceFlip },
                        style = KcButtonStyle.Text,
                    )
                }
                Spacer(Modifier.heightIn(min = 8.dp))
            }
        }
        explainer?.let { which ->
            ExplainerSheet(
                explainer = which,
                onContinue = {
                    explainer = null
                    when (which) {
                        Explainer.MEDIA -> actions.onRequestMedia()
                        Explainer.ALL_FILES -> actions.onRequestAllFiles()
                        Explainer.WHATSAPP -> actions.onPickWhatsApp()
                    }
                },
                onDismiss = { explainer = null },
            )
        }
    }
}

/** SAFE: the one main button (straight to the confirmation) and "See details", or why there is none yet. */
@Composable
private fun SafeAction(
    summary: ClassificationSummary?,
    items: List<ClassifiedItem>,
    pending: Int,
    enabled: Boolean,
    actions: HomeActions,
    onClean: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            summary == null -> {
                Text(
                    stringResource(pending),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("pending_SAFE"),
                )
            }

            items.isNotEmpty() -> {
                val total = remember(items) { items.fold(Total()) { t, i -> t + i } }
                KcButton(
                    stringResource(
                        R.string.home_clean_button,
                        Bidi.isolate(ByteSize.format(total.bytes, LocalConfiguration.current.locales[0])),
                    ),
                    onClick = onClean,
                    enabled = enabled,
                    icon = KcIcons.Safe,
                    modifier = Modifier.fillMaxWidth().testTag("open_SAFE"),
                )
            }

            else -> {
                Text(stringResource(R.string.home_clean_nothing), modifier = Modifier.testTag("nothingToClean"))
            }
        }
        if (summary != null && summary.total(Category.SAFE).files > 0) {
            KcButton(
                stringResource(R.string.home_see_details),
                onClick = { actions.onOpenCategory(Category.SAFE) },
                style = KcButtonStyle.Text,
                modifier = Modifier.fillMaxWidth().testTag("seeDetails"),
            )
        }
    }
}

/**
 * SCAN-2: a lane's total on the phone (D5), opening its list ([onOpen]); before a scan it says why it's empty
 * ("Counting…", "Needs access"), never "soon". Never counts PROTECTED.
 */
@Composable
private fun LaneTotal(
    category: Category,
    summary: ClassificationSummary?,
    pending: Int,
    body: Int,
    onOpen: () -> Unit,
) {
    val lane = if (category == Category.MOVE) KcLane.MOVE else KcLane.REVIEW
    val locale = LocalConfiguration.current.locales[0]
    Box(Modifier.testTag(if (summary == null) "pending_$category" else "total_$category")) {
        if (summary == null) {
            LaneCard(lane = lane, title = categoryTitle(category), supporting = stringResource(pending))
        } else {
            val total = summary.phoneTotal(category)
            val count = NumberFormat.getIntegerInstance(locale).format(total.files)
            LaneCard(
                lane = lane,
                title = categoryTitle(category),
                count = Bidi.isolate(pluralStringResource(R.plurals.file_count, total.files, count)),
                size = Bidi.isolate(ByteSize.format(total.bytes, locale)),
                supporting = stringResource(body),
                onClick = onOpen,
                modifier = Modifier.testTag("open_$category"),
            )
        }
    }
}

/** F1b: the permission steps beyond photos (All files access, WhatsApp's folder), in one collapsed card. */
@Composable
private fun MoreFilesCard(state: HomeUiState, onExplain: (Explainer) -> Unit) {
    val allFiles = state.mediaAccess != MediaAccess.NONE && state.allFiles == false
    // M12b, ONB-6 (play): WhatsApp's folder through the system picker, explained first.
    val whatsApp = !state.wrongFolder && state.allFiles == null && state.grantedFolders.isEmpty()
    if (!allFiles && !whatsApp) return
    var open by rememberSaveable { mutableStateOf(false) }
    KcCard(Modifier.testTag("moreFiles")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = open, role = Role.Button, onValueChange = { open = it })
                .testTag("moreFilesToggle"),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.home_more_files),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Icon(KcIcons.ExpandMore, contentDescription = null, modifier = Modifier.rotate(if (open) 180f else 0f))
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                if (allFiles) {
                    ActionCard(
                        title = stringResource(R.string.allfiles_card_title),
                        body = stringResource(R.string.allfiles_card_body),
                        button = stringResource(R.string.allfiles_card_button),
                        tag = "allowAllFiles",
                        onClick = { onExplain(Explainer.ALL_FILES) },
                        primary = false,
                    )
                }
                if (whatsApp) {
                    ActionCard(
                        title = stringResource(R.string.whatsapp_card_title),
                        body = stringResource(R.string.whatsapp_card_body),
                        button = stringResource(R.string.whatsapp_card_button),
                        tag = "pickWhatsApp",
                        onClick = { onExplain(Explainer.WHATSAPP) },
                        primary = false,
                    )
                }
            }
        }
    }
}

/** A notice at the bottom of the tab, with a close button. */
@Composable
private fun Notice(
    title: String,
    body: String,
    button: String,
    tag: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    KcCard(Modifier.testTag("notice_$tag")) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("dismiss_$tag")) {
                Icon(KcIcons.Close, contentDescription = stringResource(R.string.home_notice_dismiss))
            }
        }
        Text(body, style = MaterialTheme.typography.bodyMedium)
        KcButton(button, onClick, Modifier.padding(top = 8.dp).testTag(tag), style = KcButtonStyle.Tonal)
    }
}

/** ONB-2: why a permission is needed, what KeepClear never does, and what happens without it; then Android asks. */
@Composable
private fun ExplainerSheet(explainer: Explainer, onContinue: () -> Unit, onDismiss: () -> Unit) {
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

        Explainer.WHATSAPP -> listOf(
            R.string.explainer_whatsapp_title,
            R.string.explainer_whatsapp_why,
            R.string.explainer_whatsapp_never,
            R.string.explainer_whatsapp_steps,
        )
    }
    val lastLabel = if (explainer == Explainer.WHATSAPP) {
        R.string.explainer_label_steps
    } else {
        R.string.explainer_label_without
    }
    InfoSheet(
        title = stringResource(title),
        dismissText = stringResource(R.string.explainer_not_now),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("explainer"),
        primaryButton = {
            KcButton(
                stringResource(R.string.explainer_continue),
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().testTag("explainerContinue"),
            )
        },
    ) {
        Labeled(R.string.explainer_label_why, why)
        Labeled(R.string.explainer_label_never, never)
        Labeled(lastLabel, without)
    }
}

/** Review PR C: the scan status is one line, not a card. */
@Composable
private fun ScanLine(state: HomeUiState, actions: HomeActions, size: (Long) -> String) {
    val numbers = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])

    @Composable
    fun files(n: Int) = Bidi.isolate(pluralStringResource(R.plurals.file_count, n, numbers.format(n)))
    Column(modifier = Modifier.fillMaxWidth().testTag("scanLine")) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            when (val scan = state.scan) {
                is ScanState.Running -> {
                    Text(
                        stringResource(R.string.scan_status_running, files(scan.files)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    KcButton(stringResource(R.string.scan_cancel), actions.onCancelScan, style = KcButtonStyle.Text)
                }

                is ScanState.Done -> {
                    Text(
                        stringResource(R.string.scan_status_done, files(scan.files), Bidi.isolate(size(scan.bytes))),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    KcButton(
                        stringResource(R.string.scan_again),
                        actions.onScanAgain,
                        Modifier.testTag("scanAgain"),
                        style = KcButtonStyle.Text,
                    )
                }

                ScanState.Idle -> {
                    Spacer(Modifier.weight(1f))
                    KcButton(
                        stringResource(R.string.scan_again),
                        actions.onScanAgain,
                        Modifier.testTag("scanAgain"),
                        style = KcButtonStyle.Text,
                    )
                }
            }
        }
        if (state.scan is ScanState.Running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        val done = state.scan as? ScanState.Done
        if (done != null && !done.complete) {
            Text(stringResource(R.string.scan_incomplete), style = MaterialTheme.typography.bodySmall)
        }
        if (done != null && state.allFiles != true) {
            Text(stringResource(R.string.media_only_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** F2: a running action shows progress (an indeterminate bar and a plain line), not only disabled buttons. */
@Composable
internal fun BusyLine(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { }.testTag("busy"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.home_busy), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** ONB-3 / SAFE-5: "Clear apps' temporary files", on Home always. */
@Composable
private fun TrimCard(result: Long?, enabled: Boolean, onTrim: () -> Unit, onOneByOne: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    KcCard(Modifier.testTag("trimCard")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.trim_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.trim_body), style = MaterialTheme.typography.bodyMedium)
            result?.let {
                Text(
                    when {
                        it == TestMode.TRIM_SKIPPED -> stringResource(R.string.test_mode_caches_not_cleared)
                        it <= 0L -> stringResource(R.string.trim_result_zero)
                        else -> stringResource(R.string.trim_result, Bidi.isolate(ByteSize.format(it, locale)))
                    },
                    modifier = Modifier.testTag("homeTrimResult"),
                )
            }
            KcButton(
                stringResource(R.string.trim_button),
                onClick = { confirm = true },
                enabled = enabled,
                style = KcButtonStyle.Tonal,
                modifier = Modifier.testTag("homeTrim"),
            )
            // SAFE-5 → SAFE-10: when Android trims little, the guided per-app list is the next step.
            KcButton(
                stringResource(R.string.one_by_one_button),
                onClick = onOneByOne,
                style = KcButtonStyle.Text,
                modifier = Modifier.testTag("oneByOneLink"),
            )
        }
    }
    if (confirm) {
        TrimConfirmSheet(
            onTrim = {
                confirm = false
                onTrim()
            },
            onDismiss = { confirm = false },
            tag = "confirmHomeTrim",
            bodyTag = "homeTrimLabel",
        )
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
private fun ActionCard(
    title: String,
    body: String,
    button: String,
    tag: String,
    onClick: () -> Unit,
    primary: Boolean = true,
) {
    KcCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyMedium)
            KcButton(
                button,
                onClick,
                Modifier.testTag(tag),
                style = if (primary) KcButtonStyle.Primary else KcButtonStyle.Tonal,
            )
        }
    }
}

@Composable
private fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    KcCard(Modifier.testTag("banner"), containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (action != null) {
            KcButton(action, onAction, Modifier.testTag("bannerAction"), style = KcButtonStyle.Text)
        }
    }
}

private val areaLabels = mapOf(
    ClassificationSummary.Area.CAMERA to R.string.area_camera,
    ClassificationSummary.Area.WHATSAPP to R.string.area_whatsapp,
    ClassificationSummary.Area.JUNK to R.string.area_junk,
    ClassificationSummary.Area.OTHER to R.string.area_other,
)

/** F2: "Where your space is" as one line under the strip (camera, WhatsApp, junk, other, with exact sizes). */
@Composable
private fun StripLegend(summary: ClassificationSummary, size: (Long) -> String) {
    val parts = mutableListOf<String>()
    for ((area, bytes) in summary.areas()) {
        parts +=
            stringResource(R.string.area_value, stringResource(areaLabels.getValue(area)), Bidi.isolate(size(bytes)))
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp).testTag("areas"),
    )
}

/** The strip's spoken description: used and free, then what each lane could free (D5: phone storage only). */
@Composable
private fun stripDescription(state: HomeUiState, summary: ClassificationSummary?, size: (Long) -> String): String {
    val base =
        stringResource(R.string.home_storage_line, size(state.totalBytes - state.freeBytes), size(state.freeBytes))
    if (summary == null) return base
    val lanes = mutableListOf(base)
    for (category in listOf(Category.SAFE, Category.REVIEW, Category.MOVE)) {
        lanes += stringResource(R.string.area_value, categoryTitle(category), size(summary.phoneTotal(category).bytes))
    }
    return lanes.joinToString(" · ")
}
