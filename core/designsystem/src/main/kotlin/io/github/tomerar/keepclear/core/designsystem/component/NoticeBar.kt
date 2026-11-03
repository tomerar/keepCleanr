// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem.component

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
import kotlinx.coroutines.delay

/**
 * PR H5: how long a short notice with an action stays. Android's accessibility setting "Time to take action"
 * ([AccessibilityManager.getRecommendedTimeoutMillis]) can make it longer, never shorter (owner brief H4 item 6).
 */
object NoticeTimeout {
    /** Material's long duration for a notice with an action. */
    const val BASE_MILLIS = 10_000

    fun millis(context: Context): Long {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return BASE_MILLIS.toLong()
        val recommended = manager.getRecommendedTimeoutMillis(
            BASE_MILLIS,
            AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
        )
        return maxOf(recommended, BASE_MILLIS).toLong()
    }
}

/**
 * PR H5: a short confirmation at the bottom of the screen with one text action (Undo „חזרה”), announced politely
 * by TalkBack. It closes itself after [NoticeTimeout] (keyed by [message]); [onDismiss] is called then.
 */
@Composable
fun NoticeBar(
    message: String,
    actionText: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val timeout = remember(context) { NoticeTimeout.millis(context) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        delay(timeout)
        dismiss()
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { paneTitle = message },
        shape = KcShapes.card,
        // Paper with an outline and the ink action: the same pairs every card and text button use (ContrastTest).
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 3.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = KcSpacing.l,
                end = KcSpacing.s,
                top = KcSpacing.xs,
                bottom = KcSpacing.xs,
            ),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            KcButton(actionText, onAction, actionModifier, style = KcButtonStyle.Text)
        }
    }
}
