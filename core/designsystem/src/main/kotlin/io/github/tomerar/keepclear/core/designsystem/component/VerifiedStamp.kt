// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcMotion
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme

/** Test tag of the stamp. */
const val VERIFIED_STAMP_TAG = "kcVerifiedStamp"

/** How far the stamp is turned, like a rubber stamp on a paper slip. */
private const val STAMP_ROTATION = -4f

/**
 * Proof after a verified backup (design-system.md §8), e.g. "✓ Checked file by file": an ink outline stamp, slightly
 * turned. It appears once with a short fade and settle; under reduced motion it is simply there.
 */
@Composable
fun VerifiedStamp(text: String, modifier: Modifier = Modifier) {
    val reduced = KeepClearTheme.reducedMotion
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (reduced) progress.snapTo(1f) else progress.animateTo(1f, KcMotion.layout())
    }
    val ink = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .testTag(VERIFIED_STAMP_TAG)
            .graphicsLayer {
                val p = progress.value
                alpha = p
                scaleX = 1.15f - 0.15f * p
                scaleY = 1.15f - 0.15f * p
                rotationZ = STAMP_ROTATION
            }
            .border(2.dp, ink, RoundedCornerShape(10.dp))
            .padding(horizontal = KcSpacing.m - 4.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(KcIcons.Verified, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}
