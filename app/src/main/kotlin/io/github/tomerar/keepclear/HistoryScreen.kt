// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcLane
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.HeroNumber
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.i18n.R
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Owner brief H3 item 4: "What KeepClear did": the running total of space really freed („פיניתם עד היום 18.4GB”) and
 * every action, newest first, with its date, count and size. Read-only.
 */
@Composable
fun HistoryScreen(history: HistoryState, onBack: () -> Unit, zone: ZoneId = ZoneId.systemDefault()) {
    val locale = LocalConfiguration.current.locales[0]
    Scaffold(topBar = { SubScreenTopBar(stringResource(R.string.history_title), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("history"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val spoken = if (history.totalFreed > 0) {
                    stringResource(R.string.history_total, ByteSize.format(history.totalFreed, locale))
                } else {
                    stringResource(R.string.history_total_none)
                }
                if (history.totalFreed > 0) {
                    HeroNumber(
                        bytes = history.totalFreed,
                        label = stringResource(R.string.history_total_label),
                        sentence = stringResource(R.string.history_note),
                        modifier = Modifier.testTag("historyTotal").semantics { contentDescription = spoken },
                    )
                } else {
                    KcCard(Modifier.testTag("historyTotal")) {
                        Text(spoken, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.history_note), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (history.events.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("historyEmpty"),
                    )
                }
            }
            items(history.events) { event -> HistoryRow(event, zone) }
        }
    }
}

@Composable
private fun HistoryRow(event: HistoryEvent, zone: ZoneId) {
    val locale = LocalConfiguration.current.locales[0]
    val date = Instant.ofEpochMilli(event.at).atZone(zone)
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
    val size = ByteSize.format(event.bytes, locale)
    val amount = if (event.files > 0) {
        val count = pluralStringResource(
            R.plurals.file_count,
            event.files,
            NumberFormat.getIntegerInstance(locale).format(event.files),
        )
        countAndSize(count, size)
    } else {
        Bidi.isolate(size)
    }
    KcCard(Modifier.testTag("historyRow_${event.kind.name}")) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                historyIcon(event.kind),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(historyTitle(event.kind)), style = MaterialTheme.typography.titleSmall)
                Text(
                    amount,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = KeepClearTheme.type.number.fontFamily,
                        fontFeatureSettings = KeepClearTheme.type.number.fontFeatureSettings,
                    ),
                )
                // Shown only when space really came back (and not twice for a cache trim, where it is the size).
                if (event.freedBytes > 0 && event.files > 0) {
                    Text(
                        stringResource(R.string.history_freed, Bidi.isolate(ByteSize.format(event.freedBytes, locale))),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("historyFreed"),
                    )
                }
                Text(
                    Bidi.isolate(date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun historyTitle(kind: HistoryKind): Int = when (kind) {
    HistoryKind.CLEANED -> R.string.history_kind_cleaned
    HistoryKind.CACHES_TRIMMED -> R.string.history_kind_caches
    HistoryKind.TRASH_EMPTIED -> R.string.history_kind_trash_emptied
    HistoryKind.TRASH_EXPIRED -> R.string.history_kind_trash_expired
    HistoryKind.BACKED_UP -> R.string.history_kind_backed_up
    HistoryKind.FREED_AFTER_BACKUP -> R.string.history_kind_freed_backup
    HistoryKind.ORIGINALS_TO_TRASH -> R.string.history_kind_originals_trash
    HistoryKind.RESTORED_FROM_TRASH -> R.string.history_kind_restored_trash
    HistoryKind.RESTORED_FROM_BACKUP -> R.string.history_kind_restored_backup
}

@Composable
private fun historyIcon(kind: HistoryKind): ImageVector = when (kind) {
    HistoryKind.CLEANED, HistoryKind.CACHES_TRIMMED -> KcIcons.lane(KcLane.SAFE)
    HistoryKind.TRASH_EMPTIED, HistoryKind.TRASH_EXPIRED, HistoryKind.ORIGINALS_TO_TRASH -> KcIcons.Trash
    HistoryKind.BACKED_UP, HistoryKind.FREED_AFTER_BACKUP -> KcIcons.lane(KcLane.MOVE)
    HistoryKind.RESTORED_FROM_TRASH, HistoryKind.RESTORED_FROM_BACKUP -> KcIcons.Undo
}
