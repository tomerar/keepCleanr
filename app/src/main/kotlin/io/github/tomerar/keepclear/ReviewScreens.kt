// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.GroupSummary
import io.github.tomerar.keepclear.engine.classify.Total
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun categoryTitle(category: Category): String = stringResource(
    when (category) {
        Category.SAFE -> R.string.home_card_safe_title
        Category.REVIEW -> R.string.home_card_review_title
        Category.MOVE -> R.string.home_card_move_title
        Category.PROTECTED -> R.string.group_protected
    },
)

@Composable
fun totalText(total: Total): String {
    val locale = LocalConfiguration.current.locales[0]
    val count =
        pluralStringResource(
            R.plurals.file_count,
            total.files,
            NumberFormat.getIntegerInstance(locale).format(total.files),
        )
    return stringResource(R.string.total_value, ByteSize.format(total.bytes, locale), Bidi.isolate(count))
}

@Composable
private fun bucketTitle(bucket: String?): String {
    val locale = LocalConfiguration.current.locales[0]
    return when {
        bucket == null -> stringResource(R.string.bucket_other)
        bucket.length == 7 -> YearMonth.parse(bucket).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        else -> bucket
    }
}

@Composable
private fun BackRow(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text(stringResource(R.string.back)) }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** All groups of one category, largest first (REV-1). Nothing is selected or selectable yet. */
@Composable
fun CategoryListScreen(
    summary: ClassificationSummary,
    category: Category,
    onOpenGroup: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("categoryList"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BackRow(categoryTitle(category), onBack)
                Text(totalText(summary.total(category)), style = MaterialTheme.typography.bodyLarge)
            }
            items(summary.groups(category), key = { it.ruleId }) { group ->
                val text = RuleText.of(group.ruleId)
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onOpenGroup(group.ruleId)
                    }.testTag("group_${group.ruleId}"),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(text.group), style = MaterialTheme.typography.titleMedium)
                        Text(totalText(group.total), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(text.why), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** One group as a thumbnail grid with month headers (REV-2). Delete comes in M8. */
@Composable
fun GroupScreen(group: GroupSummary, onOpenItem: (ClassifiedItem) -> Unit, onBack: () -> Unit) {
    val text = RuleText.of(group.ruleId)
    Scaffold(
        bottomBar = {
            Button(onClick = {
            }, enabled = false, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("deleteDisabled")) {
                Text(
                    stringResource(
                        if (group.category ==
                            Category.MOVE
                        ) {
                            R.string.move_coming_soon
                        } else {
                            R.string.delete_coming_soon
                        },
                    ),
                )
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.padding(padding).fillMaxSize().testTag("grid"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BackRow(stringResource(text.group), onBack)
                    Text(totalText(group.total), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.label_why), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(text.why), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.label_if_deleted), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(text.ifDeleted), style = MaterialTheme.typography.bodyMedium)
                }
            }
            for ((bucket, items) in group.buckets) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "h_$bucket") {
                    Text(
                        bucketTitle(bucket),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).testTag("bucket_$bucket"),
                    )
                }
                items(items, key = { it.item.path }) { item ->
                    Tile(item, Modifier.clickable { onOpenItem(item) })
                }
            }
        }
    }
}

@Composable
private fun Tile(item: ClassifiedItem, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).testTag("tile"),
        contentAlignment = Alignment.Center,
    ) {
        Thumb(item, 96)
        if (LocalThumbnailLoader.current == null) {
            Text(
                Bidi.isolate(item.item.path.substringAfterLast('/')),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

@Composable
private fun Thumb(item: ClassifiedItem, sizeDp: Int) {
    val loader = LocalThumbnailLoader.current ?: return
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.item.path) { value = loader.load(item.item, px) }
    bitmap?.let {
        Image(
            it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** One item: preview, exact size and date, and the plain-language explanation (CLS-2). */
@Composable
fun PreviewScreen(item: ClassifiedItem, onBack: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val text = RuleText.of(item.ruleId)
    val date = Instant.ofEpochMilli(item.item.mtimeMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("preview"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { BackRow(stringResource(text.group), onBack) }
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(
                        1f,
                    ).background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Thumb(item, 360)
                }
            }
            item {
                Text(
                    Bidi.isolateLtr(item.item.path),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("path"),
                )
                Text(ByteSize.format(item.item.size, locale), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.preview_modified, date), style = MaterialTheme.typography.bodyMedium)
                AssistChip(onClick = {}, enabled = false, label = { Text(riskText(item)) })
                if (item.keepSignal) {
                    Text(
                        stringResource(R.string.keep_signal_note),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Text(stringResource(R.string.label_why), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(text.why), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.label_if_deleted), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(text.ifDeleted), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun riskText(item: ClassifiedItem): String = stringResource(
    when (item.risk) {
        io.github.tomerar.keepclear.engine.classify.RiskChip.REGENERATES -> R.string.risk_REGENERATES
        io.github.tomerar.keepclear.engine.classify.RiskChip.COPY_EXISTS -> R.string.risk_COPY_EXISTS
        io.github.tomerar.keepclear.engine.classify.RiskChip.ALREADY_DELETED -> R.string.risk_ALREADY_DELETED
        io.github.tomerar.keepclear.engine.classify.RiskChip.MIGHT_BE_MEMORY -> R.string.risk_MIGHT_BE_MEMORY
        io.github.tomerar.keepclear.engine.classify.RiskChip.YOUR_CHATS -> R.string.risk_YOUR_CHATS
        io.github.tomerar.keepclear.engine.classify.RiskChip.UNKNOWN -> R.string.risk_UNKNOWN
    },
)
