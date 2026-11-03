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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import io.github.tomerar.keepclear.core.designsystem.component.StorageLegend
import io.github.tomerar.keepclear.core.designsystem.component.StorageStrip
import io.github.tomerar.keepclear.core.designsystem.component.StorageUsage
import io.github.tomerar.keepclear.core.designsystem.component.StripSegment
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
                var confirmTrim by rememberSaveable { mutableStateOf(false) }
                when (state.spaceMode) {
                    SpaceMode.LIGHT -> Banner(stringResource(R.string.low_space_light))

                    // Owner brief H2 items 4 and 11: the trim moved off Home, so the nearly-full banner carries it as
                    // its next step.
                    SpaceMode.MINIMAL -> Banner(
                        stringResource(R.string.low_space_minimal),
                        stringResource(R.string.trim_button).takeIf { !state.busy },
                    ) { confirmTrim = true }

                    SpaceMode.NORMAL -> Unit
                }
                state.trimResult?.takeIf { state.screens.last() == Screen.Home }?.let { TrimResultLine(it) }
                if (confirmTrim) {
                    TrimConfirmSheet(
                        onTrim = {
                            confirmTrim = false
                            actions.onTrim()
                        },
                        onDismiss = { confirmTrim = false },
                        tag = "confirmHomeTrim",
                        bodyTag = "homeTrimLabel",
                    )
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
                            val safe = it.phoneTotal(Category.SAFE).bytes
                            // Owner brief H2 item 6: never "can free 0B"; a calm "All clean ✓" instead.
                            if (safe > 0) {
                                stringResource(R.string.home_hero_safe, Bidi.isolate(size(safe)))
                            } else {
                                stringResource(R.string.home_all_clean)
                            }
                        },
                        strip = {
                            val usage = StorageUsage(
                                total = state.totalBytes,
                                free = state.freeBytes,
                                safe = summary?.phoneTotal(Category.SAFE)?.bytes ?: 0,
                                review = summary?.phoneTotal(Category.REVIEW)?.bytes ?: 0,
                                move = summary?.phoneTotal(Category.MOVE)?.bytes ?: 0,
                            )
                            StorageStrip(
                                usage = usage,
                                description = stripDescription(state, summary, ::size),
                                modifier = Modifier.testTag("storage"),
                            )
                            // Design QA item 2: the legend names the strip's segments in the strip's order, with
                            // a colour dot, the lane's name and its size (TalkBack hears the strip's description).
                            StorageLegend(
                                usage = usage,
                                labels = stripLabels(summary),
                                sizeText = { Bidi.isolate(size(it)) },
                                modifier = Modifier.padding(top = 8.dp).testTag("areas").clearAndSetSemantics { },
                            )
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
                    // Owner brief H2 item 2: after the first scan, the next step for WhatsApp's files, in context.
                    MoreFilesOffer(state, onExplain = { explainer = it })
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
                // Owner brief H2 item 4: one clean button on Home; apps' temporary files and "one app at a time" are
                // under "See details" (Clean now) and on the Apps tab.
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
                AllClean(summary, actions)
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
        } else if (summary.phoneTotal(category).files == 0) {
            // Owner brief H2 items 6 and 11: an empty lane says so in words, never "0 files · 0B".
            LaneCard(
                lane = lane,
                title = categoryTitle(category),
                supporting = stringResource(R.string.home_lane_empty),
                modifier = Modifier.testTag("empty_$category"),
            )
        } else {
            val total = summary.phoneTotal(category)
            val count = NumberFormat.getIntegerInstance(locale).format(total.files)
            // F3: a few pictures from the lane's largest groups, so memories are recognised by sight.
            val sample = remember(summary, category) { laneThumbItems(summary, category) }
            val thumbs = rememberThumbPainters(sample)
            LaneCard(
                lane = lane,
                title = categoryTitle(category),
                count = Bidi.isolate(pluralStringResource(R.plurals.file_count, total.files, count)),
                size = Bidi.isolate(ByteSize.format(total.bytes, locale)),
                supporting = stringResource(body),
                onClick = onOpen,
                modifier = Modifier.testTag("open_$category"),
                thumbnails = thumbs,
                thumbnailTotal = total.files,
            )
        }
    }
}

/**
 * F3: the items a Home lane card shows as its ThumbStrip: the newest few of the lane's largest groups on the phone
 * (D5), taken in turn from each group so the strip shows the lane's mix (screenshots, AI images, big videos…). Reads
 * only the first items of each group's newest bucket: no extra scan, no walk over thousands of files.
 */
internal fun laneThumbItems(summary: ClassificationSummary, category: Category, max: Int = 8): List<ClassifiedItem> {
    val perGroup = summary.groups(category).take(THUMB_GROUPS).map { group ->
        group.buckets.asSequence().flatMap { it.second.asSequence() }
            .filter { PhoneStorage.contains(it.item.path) }
            .take(max)
            .toList()
    }
    val picked = mutableListOf<ClassifiedItem>()
    var i = 0
    while (picked.size < max && perGroup.any { i < it.size }) {
        perGroup.forEach { items -> if (i < items.size && picked.size < max) picked += items[i] }
        i++
    }
    return picked
}

private const val THUMB_GROUPS = 4

/**
 * Owner brief H2 item 2: after the first scan, ONE card offers the step for WhatsApp's files where it matters (no
 * longer hidden in a collapsed card at the bottom). `foss`: All files access, explained first (ONB-2). `play` (no All
 * files access there, ADR 0005): says what is possible without it, choosing WhatsApp's own folder. "Not now" hides it
 * for this visit.
 */
@Composable
private fun MoreFilesOffer(state: HomeUiState, onExplain: (Explainer) -> Unit) {
    if (state.scan !is ScanState.Done) return
    val allFiles = state.allFiles == false
    // M12b, ONB-6 (play): WhatsApp's folder through the system picker.
    val folder = state.allFiles == null && !state.wrongFolder && state.grantedFolders.isEmpty()
    if (!allFiles && !folder) return
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (dismissed) return
    KcCard(Modifier.testTag("moreFilesOffer")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.whatsapp_offer_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(
                    if (allFiles) R.string.whatsapp_offer_body_allfiles else R.string.whatsapp_offer_body_folder,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allFiles) {
                    KcButton(
                        stringResource(R.string.allfiles_card_button),
                        { onExplain(Explainer.ALL_FILES) },
                        Modifier.testTag("allowAllFiles"),
                        style = KcButtonStyle.Tonal,
                    )
                } else {
                    KcButton(
                        stringResource(R.string.whatsapp_card_button),
                        { onExplain(Explainer.WHATSAPP) },
                        Modifier.testTag("pickWhatsApp"),
                        style = KcButtonStyle.Tonal,
                    )
                }
                KcButton(
                    stringResource(R.string.explainer_not_now),
                    { dismissed = true },
                    Modifier.testTag("moreFilesNotNow"),
                    style = KcButtonStyle.Text,
                )
            }
        }
    }
}

/**
 * Owner brief H2 item 6: nothing left to clean on the phone. A calm line that points to the biggest win left: the
 * backup (the MOVE lane's size), else choosing what to delete (REVIEW); never "can free 0B".
 */
@Composable
private fun AllClean(summary: ClassificationSummary, actions: HomeActions) {
    val locale = LocalConfiguration.current.locales[0]
    val move = summary.phoneTotal(Category.MOVE).bytes
    val review = summary.phoneTotal(Category.REVIEW).bytes
    Column(Modifier.testTag("allClean"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            move > 0 && move >= review -> {
                Text(
                    stringResource(R.string.home_big_win_backup, Bidi.isolate(ByteSize.format(move, locale))),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("bigWin"),
                )
                KcButton(
                    stringResource(R.string.home_big_win_backup_button),
                    onClick = actions.onOpenBackup,
                    icon = KcIcons.Move,
                    style = KcButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth().testTag("bigWinButton"),
                )
            }

            review > 0 -> {
                Text(
                    stringResource(R.string.home_big_win_review, Bidi.isolate(ByteSize.format(review, locale))),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("bigWin"),
                )
                KcButton(
                    stringResource(R.string.home_card_review_title),
                    onClick = { actions.onOpenCategory(Category.REVIEW) },
                    icon = KcIcons.Review,
                    style = KcButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth().testTag("bigWinButton"),
                )
            }

            // Nothing to back up or choose either: „הכול נקי ✓” in the hero says it all.
            else -> {}
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

/** The measured result of the last trim (SAFE-5), started from the nearly-full banner. */
@Composable
private fun TrimResultLine(result: Long) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        when {
            result == TestMode.TRIM_SKIPPED -> stringResource(R.string.test_mode_caches_not_cleared)
            result <= 0L -> stringResource(R.string.trim_result_zero)
            else -> stringResource(R.string.trim_result, Bidi.isolate(ByteSize.format(result, locale)))
        },
        modifier = Modifier.testTag("homeTrimResult"),
    )
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

/**
 * Design QA item 2, owner brief H2 item 4: the legend's names are nouns, „קבצים מיותרים · לבחירה · לגיבוי · אחר ·
 * פנוי”; the lanes only after a scan.
 */
@Composable
private fun stripLabels(summary: ClassificationSummary?): Map<StripSegment, String> = buildMap {
    // Owner brief H2 item 6: an empty lane isn't listed (never "0B").
    val lanes = listOf(
        Triple(StripSegment.SAFE, Category.SAFE, stringResource(R.string.strip_legend_safe)),
        Triple(StripSegment.REVIEW, Category.REVIEW, stringResource(R.string.strip_legend_review)),
        Triple(StripSegment.MOVE, Category.MOVE, stringResource(R.string.strip_legend_move)),
    )
    for ((segment, category, label) in lanes) {
        if (summary != null && summary.phoneTotal(category).bytes > 0) put(segment, label)
    }
    put(StripSegment.OTHER, stringResource(R.string.strip_legend_other))
    put(StripSegment.FREE, stringResource(R.string.strip_legend_free))
}

/** The strip's spoken description: used and free, then what each lane could free (D5: phone storage only). */
@Composable
private fun stripDescription(state: HomeUiState, summary: ClassificationSummary?, size: (Long) -> String): String {
    val base =
        stringResource(R.string.home_storage_line, size(state.totalBytes - state.freeBytes), size(state.freeBytes))
    if (summary == null) return base
    val lanes = mutableListOf(base)
    for (category in listOf(Category.SAFE, Category.REVIEW, Category.MOVE)) {
        val bytes = summary.phoneTotal(category).bytes
        if (bytes > 0) lanes += stringResource(R.string.area_value, categoryTitle(category), size(bytes))
    }
    return lanes.joinToString(" · ")
}
