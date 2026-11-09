// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.designsystem.component.MediaTile
import io.github.tomerar.keepclear.core.designsystem.component.MonthHeader
import io.github.tomerar.keepclear.core.designsystem.component.ThumbStrip
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.gallery.GalleryScope
import io.github.tomerar.keepclear.engine.gallery.KeeperReason
import java.text.NumberFormat
import java.time.ZoneId

/**
 * G4a: what the gallery screens can do. None of these selects, plans, confirms or changes a file: the only
 * persistent choice is the existing "Don't suggest this again" rule (CLS-7, [onDontOffer]), and the only way on for
 * a memory is the existing backup flow ([onBackUp]).
 */
class GalleryActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onContinue: () -> Unit = {},
    /** The existing permission request (Home's "Allow access"). */
    val onAllowAccess: () -> Unit = {},
    /** On every resume: re-reads the live scope, so a narrowed access hides earlier groups (E-G02, AC-G1). */
    val onCheckAccess: () -> Unit = {},
    /** "Keep them all this time": this session only (C223). */
    val onKeepThisTime: (groupId: String) -> Unit = {},
    /** "Don't suggest this again" for one photo: the existing kept rule (CLS-7, C223). */
    val onDontOffer: (path: String) -> Unit = {},
    /** The Backup tab's existing flow for memories (GAL-4). */
    val onBackUp: () -> Unit = {},
    val onOpenPhoto: (ClassifiedItem) -> Unit = {},
)

/** G4a: the gallery's state as a flow (read only by its own screens) and its actions, for [AppScreens]. */
class GalleryHost(
    val flow: kotlinx.coroutines.flow.StateFlow<GalleryUi>? = null,
    val value: GalleryUi = GalleryUi(),
    val actions: GalleryActions = GalleryActions(),
)

/** G4a: the review lane's entry ("Find similar photos"); user-started only, never a Home hero (ux-flows.md §10). */
@Composable
fun GalleryEntryCard(onClick: () -> Unit) {
    KcCard(onClick = onClick, modifier = Modifier.testTag(GALLERY_ENTRY_TAG)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.gallery_entry_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.gallery_entry_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

const val GALLERY_ENTRY_TAG = "galleryEntry"

/**
 * G4a: the comparison run and its groups (GAL-2): the scope in plain words, calm "N of M" progress with the skipped
 * count, Stop / Continue, honest partial text, and the groups list. Technical counts are under "See details".
 */
@Composable
fun GalleryScreen(
    ui: GalleryUi,
    actions: GalleryActions,
    onOpenGroup: (String) -> Unit,
    onBack: () -> Unit,
) {
    LifecycleResumeEffect(Unit) {
        actions.onCheckAccess()
        onPauseOrDispose { }
    }
    var details by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val number = NumberFormat.getIntegerInstance(locale)

    fun n(value: Int) = Bidi.isolate(number.format(value))
    Scaffold(topBar = { SubScreenTopBar(stringResource(R.string.gallery_title), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag(GALLERY_SCREEN_TAG),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScopeLine(ui.scope, ui.phase, actions)
                    Status(ui, actions, ::n)
                    if (ui.phase in DETAIL_PHASES) {
                        KcButton(
                            stringResource(R.string.details),
                            onClick = { details = true },
                            style = KcButtonStyle.Text,
                            modifier = Modifier.testTag("galleryDetails"),
                        )
                    }
                }
            }
            if (ui.phase in RESULT_PHASES) {
                if (ui.groups.isEmpty()) {
                    if (ui.phase == GalleryPhase.DONE || ui.phase == GalleryPhase.STOPPED) {
                        item {
                            Text(
                                stringResource(R.string.gallery_no_groups),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.testTag("galleryNoGroups"),
                            )
                        }
                    }
                } else {
                    item { MonthHeader(stringResource(R.string.gallery_groups_title)) }
                    itemsIndexed(ui.groups, key = { _, g -> g.id }) { index, group ->
                        GroupCard(group, index, ::n) { onOpenGroup(group.id) }
                    }
                }
            }
        }
    }
    if (details) GalleryDetailsSheet(ui, ::n) { details = false }
}

const val GALLERY_SCREEN_TAG = "galleryScreen"

private val RESULT_PHASES = setOf(
    GalleryPhase.DONE,
    GalleryPhase.STOPPED,
    GalleryPhase.PAUSED_LOW_SPACE,
    GalleryPhase.PAUSED_FOR_FILE_ACTION,
)
private val DETAIL_PHASES = RESULT_PHASES + GalleryPhase.RUNNING

@Composable
private fun ScopeLine(scope: GalleryScope?, phase: GalleryPhase, actions: GalleryActions) {
    val none = phase == GalleryPhase.NO_ACCESS || scope == GalleryScope.NONE
    val text = when {
        none -> R.string.gallery_scope_none
        scope == GalleryScope.SELECTED -> R.string.gallery_scope_selected
        scope == GalleryScope.FULL_IMAGES || scope == GalleryScope.ALL_FILES -> R.string.gallery_scope_all
        else -> null
    } ?: return
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("galleryScope"))
    if (none) {
        KcButton(
            stringResource(R.string.access_allow),
            onClick = actions.onAllowAccess,
            modifier = Modifier.fillMaxWidth().testTag("galleryAllow"),
        )
    }
}

@Composable
private fun Status(ui: GalleryUi, actions: GalleryActions, n: (Int) -> String) {
    val skipped = ui.skipped.takeIf { it > 0 }?.let {
        pluralStringResource(R.plurals.gallery_skipped, it, n(it))
    }

    @Composable
    fun line(text: String, tag: String) = Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )

    @Composable
    fun skippedLine() = skipped?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("gallerySkipped"),
        )
    }

    @Composable
    fun button(text: Int, tag: String, style: KcButtonStyle, onClick: () -> Unit) = KcButton(
        stringResource(text),
        onClick = onClick,
        style = style,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
    when (ui.phase) {
        GalleryPhase.NO_SCAN -> {
            line(stringResource(R.string.gallery_needs_scan), "galleryNeedsScan")
        }

        GalleryPhase.NO_ACCESS -> {}

        GalleryPhase.NOT_STARTED -> {
            Text(stringResource(R.string.gallery_on_phone), style = MaterialTheme.typography.bodyMedium)
            button(R.string.gallery_start, "galleryStart", KcButtonStyle.Primary, actions.onStart)
        }

        GalleryPhase.RUNNING -> {
            Text(stringResource(R.string.gallery_running), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.gallery_progress, n(ui.done), n(ui.total)),
                style = KeepClearTheme.type.number,
                modifier = Modifier.testTag("galleryProgress"),
            )
            skippedLine()
            button(R.string.gallery_stop, "galleryStop", KcButtonStyle.Tonal, actions.onStop)
        }

        GalleryPhase.STOPPED -> {
            line(stringResource(R.string.gallery_stopped, n(ui.compared), n(ui.total)), "galleryResult")
            skippedLine()
            button(R.string.gallery_continue, "galleryContinue", KcButtonStyle.Primary, actions.onContinue)
        }

        GalleryPhase.PAUSED_LOW_SPACE -> {
            line(stringResource(R.string.gallery_paused_space), "galleryResult")
            button(R.string.gallery_continue, "galleryContinue", KcButtonStyle.Primary, actions.onContinue)
        }

        GalleryPhase.PAUSED_FOR_FILE_ACTION -> {
            line(stringResource(R.string.gallery_paused_action), "galleryResult")
            button(R.string.gallery_continue, "galleryContinue", KcButtonStyle.Primary, actions.onContinue)
        }

        GalleryPhase.ACCESS_CHANGED -> {
            line(stringResource(R.string.gallery_access_changed), "galleryResult")
            button(R.string.gallery_start_again, "galleryStart", KcButtonStyle.Primary, actions.onStart)
        }

        GalleryPhase.DONE -> {
            // GAL-2: "all photos" only when nothing was partial, skipped or cut by a limit.
            val text = if (ui.complete) {
                stringResource(R.string.gallery_done_complete, n(ui.total))
            } else {
                stringResource(R.string.gallery_done_partial, n(ui.compared), n(ui.total))
            }
            line(text, "galleryResult")
            skippedLine()
            button(R.string.gallery_start_again, "galleryStart", KcButtonStyle.Tonal, actions.onStart)
        }
    }
}

@Composable
private fun GroupCard(group: GalleryGroupUi, index: Int, n: (Int) -> String, onClick: () -> Unit) {
    val thumbs = rememberThumbPainters(group.members.map { it.item })
    KcCard(onClick = onClick, modifier = Modifier.testTag("galleryGroup_$index")) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                pluralStringResource(R.plurals.gallery_group_count, group.members.size, n(group.members.size)),
                style = MaterialTheme.typography.titleMedium,
            )
            if (thumbs.isNotEmpty()) ThumbStrip(thumbs, total = group.members.size)
            Text(adviceText(group), style = MaterialTheme.typography.bodyMedium)
            if (group.hasMemories) {
                Text(
                    stringResource(R.string.gallery_group_memories),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Suggested to keep: Sharper · Higher resolution", or "No clear recommendation" when the facts abstain. */
@Composable
private fun adviceText(group: GalleryGroupUi): String = if (group.reasons.isEmpty() || group.suggested == null) {
    stringResource(R.string.gallery_no_recommendation)
} else {
    stringResource(R.string.gallery_suggested_reasons, group.reasons.map { reasonText(it) }.joinToString(" · "))
}

@Composable
private fun reasonText(reason: KeeperReason): String = stringResource(
    when (reason) {
        KeeperReason.HIGHER_USABLE_RESOLUTION -> R.string.gallery_reason_resolution
        KeeperReason.SHARPER -> R.string.gallery_reason_sharper
        KeeperReason.LESS_CLIPPING -> R.string.gallery_reason_clipping
    },
)

@Composable
private fun GalleryDetailsSheet(ui: GalleryUi, n: (Int) -> String, onDismiss: () -> Unit) {
    val d = ui.details
    InfoSheet(
        title = stringResource(R.string.gallery_details_title),
        dismissText = stringResource(R.string.gallery_close),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("galleryDetailsSheet"),
    ) {
        @Composable
        fun row(text: Int, value: Int, always: Boolean = false) {
            if (always || value > 0) Text(stringResource(text, n(value)), style = MaterialTheme.typography.bodyMedium)
        }
        row(R.string.gallery_detail_eligible, d.eligible, always = true)
        row(R.string.gallery_detail_from_cache, d.fromCache, always = true)
        row(R.string.gallery_detail_decoded, d.newlyDecoded, always = true)
        row(R.string.gallery_detail_not_reached, d.notReached)
        row(R.string.gallery_detail_skip_format, GallerySkipGroups.count(d.skipped, GallerySkipGroups.FORMAT))
        row(R.string.gallery_detail_skip_variant, GallerySkipGroups.count(d.skipped, GallerySkipGroups.VARIANT))
        row(R.string.gallery_detail_skip_large, GallerySkipGroups.count(d.skipped, GallerySkipGroups.LARGE))
        row(R.string.gallery_detail_skip_damaged, GallerySkipGroups.count(d.skipped, GallerySkipGroups.DAMAGED))
        row(R.string.gallery_detail_skip_changed, GallerySkipGroups.count(d.skipped, GallerySkipGroups.CHANGED))
        row(R.string.gallery_detail_skip_no_reader, GallerySkipGroups.count(d.skipped, GallerySkipGroups.NO_READER))
        row(R.string.gallery_detail_excluded, d.excluded)
        if (d.notSaved) {
            Text(
                stringResource(R.string.gallery_detail_not_saved),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (d.limited) Text(stringResource(R.string.gallery_detail_limits), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.gallery_detail_method),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * G4a: one group side by side (GAL-6, GAL-10): date and size per photo, the "Suggested to keep" marker with its
 * reasons or "No clear recommendation". No checkbox, nothing preselected, no delete button. Memories (MOVE) offer the
 * existing backup; other photos have no action in this version (G4b adds the review choice).
 */
@Composable
fun GalleryGroupScreen(group: GalleryGroupUi, actions: GalleryActions, onBack: () -> Unit) {
    LifecycleResumeEffect(Unit) {
        actions.onCheckAccess()
        onPauseOrDispose { }
    }
    val (memories, others) = group.members.partition { it.item.category == Category.MOVE }
    Scaffold(topBar = { SubScreenTopBar(stringResource(R.string.gallery_title), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag(GALLERY_GROUP_TAG),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.gallery_compare_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("galleryHint"),
                )
            }
            item {
                KcCard(modifier = Modifier.testTag("galleryAdvice")) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (group.suggested != null && group.reasons.isNotEmpty()) {
                            Text(
                                stringResource(R.string.gallery_suggested),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            group.reasons.forEach { Text(reasonText(it), style = MaterialTheme.typography.bodyMedium) }
                        } else {
                            Text(
                                stringResource(R.string.gallery_no_recommendation),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.gallery_no_recommendation_body),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            if (memories.isNotEmpty()) {
                item { MonthHeader(stringResource(R.string.gallery_section_memories)) }
                item { MemberRows(memories, group, actions) }
                item {
                    KcButton(
                        stringResource(R.string.gallery_back_up),
                        onClick = actions.onBackUp,
                        modifier = Modifier.fillMaxWidth().testTag("galleryBackUp"),
                    )
                }
            }
            if (others.isNotEmpty()) {
                if (memories.isNotEmpty()) item { MonthHeader(stringResource(R.string.gallery_section_review)) }
                item { MemberRows(others, group, actions) }
                item {
                    Text(
                        stringResource(R.string.gallery_review_later),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                KcButton(
                    stringResource(R.string.gallery_keep_this_time),
                    onClick = {
                        actions.onKeepThisTime(group.id)
                        onBack()
                    },
                    style = KcButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth().testTag("galleryKeepThisTime"),
                )
            }
        }
    }
}

const val GALLERY_GROUP_TAG = "galleryGroup"

/** Two photos per row, large enough to compare; each is one TalkBack node with its own actions. */
@Composable
private fun MemberRows(members: List<GalleryMember>, group: GalleryGroupUi, actions: GalleryActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        members.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { member ->
                    MemberTile(member, group.members.indexOf(member), actions, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MemberTile(member: GalleryMember, index: Int, actions: GalleryActions, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val item = member.item.item
    val name = item.path.substringAfterLast('/')
    val date = Bidi.isolate(MediaGrid.dayText(item.mtimeMillis, ZoneId.systemDefault(), locale))
    val size = Bidi.isolate(ByteSize.format(item.size, locale))
    val marker = if (member.suggested) stringResource(R.string.gallery_suggested) else null
    val base = stringResource(R.string.grid_tile_description, Bidi.fileName(name), date, size)
    val previewLabel = stringResource(R.string.grid_preview)
    val dontOfferLabel = stringResource(R.string.choice_keep)
    MediaTile(
        thumbnail = rememberTilePainter(item.path, sizeDp = 180) { loader, px -> loader.load(item, px) },
        typeIcon = kindIcon(FileKinds.of(name)),
        date = date,
        size = size,
        description = if (marker != null) "$base. $marker" else base,
        onClick = { actions.onOpenPhoto(member.item) },
        modifier = modifier.testTag("galleryMember_$index"),
        actions = listOf(
            CustomAccessibilityAction(previewLabel) {
                actions.onOpenPhoto(member.item)
                true
            },
            CustomAccessibilityAction(dontOfferLabel) {
                actions.onDontOffer(item.path)
                true
            },
        ),
        note = marker,
    )
}
