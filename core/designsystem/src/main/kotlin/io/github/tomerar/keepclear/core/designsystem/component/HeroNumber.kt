// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KcMotion
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme

/** Test tag of the big number itself. */
const val HERO_NUMBER_TAG = "kcHeroNumber"

const val HERO_LABEL_TAG = "kcHeroLabel"

/** The one byte formatter (decimal, core:common [ByteSize]) for the current locale. */
@Composable
fun rememberByteFormatter(): (Long) -> String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { { bytes: Long -> ByteSize.format(bytes.coerceAtLeast(0), locale) } }
}

/**
 * The free space as the biggest thing on screen (design-system.md §8): "[bytes] [label]" in Rubik SemiBold with
 * tabular figures, then one [sentence], an optional [strip] and one [action] (an ink pill). When [bytes] changes the
 * number counts there (spring); under reduced motion it jumps. TalkBack reads the final value only, as a heading.
 * At large font sizes the number shrinks to stay on one line and the label moves below it; nothing is clipped.
 */
@Composable
fun HeroNumber(
    bytes: Long,
    label: String,
    modifier: Modifier = Modifier,
    sentence: String? = null,
    format: (Long) -> String = rememberByteFormatter(),
    strip: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val reduced = KeepClearTheme.reducedMotion
    val animated by animateFloatAsState(
        targetValue = bytes.toFloat(),
        animationSpec = if (reduced) snap() else KcMotion.count(),
        label = "heroNumber",
    )
    // Float can't hold every byte count exactly: once the animation has arrived, show the exact value.
    val shown = if (reduced || animated == bytes.toFloat()) bytes else animated.toLong()
    val finalText = format(bytes)
    Surface(modifier = modifier.fillMaxWidth(), shape = KcShapes.hero, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(KcSpacing.hero)) {
            NumberAndLabel(
                number = format(shown),
                finalNumber = finalText,
                label = label,
                modifier = Modifier.semantics(mergeDescendants = true) { heading() },
            )
            if (sentence != null) {
                Spacer(Modifier.height(KcSpacing.s))
                Text(sentence, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            if (strip != null) {
                Spacer(Modifier.height(KcSpacing.m))
                strip()
            }
            if (action != null) {
                Spacer(Modifier.height(KcSpacing.l))
                action()
            }
        }
    }
}

/** Number and label on one baseline when they fit, else the label under the number. */
@Composable
private fun NumberAndLabel(number: String, finalNumber: String, label: String, modifier: Modifier) {
    val hero = KeepClearTheme.type.hero
    // Shrinks the number (never below half) until it fits on one line; re-measured only when its length changes.
    var scale by remember(number.length) { mutableFloatStateOf(1f) }
    Layout(
        modifier = modifier,
        content = {
            Text(
                number,
                style = hero.copy(fontSize = hero.fontSize * scale, lineHeight = hero.lineHeight * scale),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                onTextLayout = { if (it.didOverflowWidth && scale > 0.5f) scale *= 0.9f },
                // TalkBack reads the final value and its label ("24.7 GB free"), never a number in the middle of
                // counting. A description wins over the merged text, so the label is part of it (PR #46 review).
                modifier = Modifier.testTag(HERO_NUMBER_TAG).semantics { contentDescription = "$finalNumber $label" },
            )
            Text(
                label,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                // Already in the number's description: not read twice.
                modifier = Modifier.clearAndSetSemantics { testTag = HERO_LABEL_TAG },
            )
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val num = measurables[0].measure(loose)
        val gap = 8.dp.roundToPx()
        val inline = measurables[1].measure(loose.copy(maxHeight = Constraints.Infinity))
        val fits = num.width + gap + inline.width <= constraints.maxWidth
        if (fits) {
            val numBase =
                num[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: num.height
            val labBase =
                inline[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified }
                    ?: inline.height
            val top = maxOf(numBase, labBase)
            val height = maxOf(top - numBase + num.height, top - labBase + inline.height)
            layout(maxOf(constraints.minWidth, num.width + gap + inline.width), height) {
                num.placeRelative(0, top - numBase)
                inline.placeRelative(num.width + gap, top - labBase)
            }
        } else {
            layout(maxOf(constraints.minWidth, num.width, inline.width), num.height + inline.height) {
                num.placeRelative(0, 0)
                inline.placeRelative(0, num.height)
            }
        }
    }
}
