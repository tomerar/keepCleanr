// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import java.text.NumberFormat

/** At most this many places; with a "+N" tile, one fewer thumbnail (so the tile has room for "+2,320"). */
const val THUMB_STRIP_MAX = 4

/**
 * 3–4 real thumbnails on a REVIEW/MOVE card (design-system.md §8): memories are recognised by sight, not by file
 * name. The app supplies up to 4 [thumbnails] (it owns loading); when the group has more than fit ([total] items),
 * the last place is a "+N" tile. Decorative for TalkBack:
 * the card's own label carries the count.
 */
@Composable
fun ThumbStrip(
    thumbnails: List<Painter>,
    modifier: Modifier = Modifier,
    total: Int = thumbnails.size,
    size: Dp = 56.dp,
) {
    if (thumbnails.isEmpty()) return
    val all = total <= THUMB_STRIP_MAX && thumbnails.size >= total
    val shown = thumbnails.take(if (all) THUMB_STRIP_MAX else THUMB_STRIP_MAX - 1)
    val moreCount = (total - shown.size).coerceAtLeast(0)
    val locale = LocalConfiguration.current.locales[0]
    Row(
        modifier = modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(KcSpacing.s),
    ) {
        shown.forEach { painter ->
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(KcShapes.thumbnail)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
        if (moreCount > 0) {
            Box(
                modifier = Modifier
                    .height(size)
                    .widthIn(min = size)
                    .clip(KcShapes.thumbnail)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = KcSpacing.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Isolated left-to-right, so "+2,320" keeps its sign in front in Hebrew too.
                    Bidi.isolateLtr("+" + NumberFormat.getIntegerInstance(locale).format(moreCount)),
                    style = KeepClearTheme.type.number,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
