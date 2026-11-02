// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcShapes
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonDefaults
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.safety.Confirmation
import kotlinx.coroutines.delay

/**
 * Review PR B: the user's tap on a confirm UI, naming exactly the items it showed. Only UI code calls this (Konsist);
 * ViewModels receive the [Confirmation] and can't make one.
 */
fun userConfirmed(
    shownPaths: Collection<String>,
): Confirmation = Confirmation.of(shownPaths, System.currentTimeMillis())

/** F2: whether a confirm button is styled as permanent (error red); tests read it. */
val PermanentAction = SemanticsPropertyKey<Boolean>("PermanentAction")

/** SEC-7 / E-SEC-10: a touch delivered while another app's window covers ours. */
object ObscuredTouch {
    fun isObscured(flags: Int): Boolean =
        flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
}

/** Test tag of [SafeConfirmButton]'s fill while it is getting ready (design QA item 5). */
const val CONFIRM_FILL_TAG = "confirmFill"

/**
 * Confirm button for destructive actions (SEC-7). It can be tapped only [delayMillis] after it appears, and it ignores
 * a tap whose touch arrived while another window covered the screen (tapjacking, E-SEC-10). The check runs on the
 * touch itself, so it also works inside dialogs, whose windows don't inherit the activity's
 * `filterTouchesWhenObscured` (review PR B, M1).
 *
 * Design QA item 5: during the delay it doesn't look disabled; it fills like a progress bar from the start side (ink
 * over a light track, the label readable on both). It has no click action until it is ready, so a tap during the
 * fill does nothing. Under reduced motion it stays an unfilled track (clearly not ready) and is filled at once when
 * ready.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SafeConfirmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    delayMillis: Long = 500,
    /**
     * True only for actions that can't be undone (empty the trash, release now): error red. False for everything
     * else (to the 30-day trash, trimming Android's caches, restoring a changed file, test mode off): ink, so a
     * recoverable action never looks alarming (review PR C). F2: no default; every call site says which it is.
     */
    permanent: Boolean,
    colors: ButtonColors? = null,
) {
    var enabled by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    val lastDownObscured = remember { booleanArrayOf(false) }
    val reduced = KeepClearTheme.reducedMotion
    val fill = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) {
            delay(delayMillis)
        } else {
            fill.animateTo(1f, tween(delayMillis.toInt(), easing = LinearEasing))
        }
        enabled = true
    }
    val buttonColors = colors ?: KcButtonDefaults.filledColors(permanent)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!enabled) {
            ConfirmFill(text, { fill.value }, buttonColors, modifier.semantics { this[PermanentAction] = permanent })
        } else {
            Button(
                onClick = {
                    // The flag belongs to the gesture that just ended: reset it, so a later keyboard or TalkBack
                    // activation (no touch) or an unobscured tap is not blocked by an old rejected tap.
                    val obscured = lastDownObscured[0]
                    lastDownObscured[0] = false
                    if (obscured) blocked = true else onClick()
                },
                colors = buttonColors,
                shape = KcShapes.pill,
                contentPadding = KcButtonDefaults.ContentPadding,
                modifier = modifier.heightIn(min = KcButtonDefaults.MinHeight)
                    .semantics { this[PermanentAction] = permanent }
                    .pointerInteropFilter { event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            lastDownObscured[0] = ObscuredTouch.isObscured(event.flags)
                        }
                        false // observe only; the button still gets the touch and decides in onClick
                    },
            ) { Text(text, style = MaterialTheme.typography.labelLarge) }
        }
        if (blocked) {
            Text(
                stringResource(R.string.obscured_touch_hint),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("obscuredHint"),
            )
        }
    }
}

/**
 * The button while it gets ready: the same pill and label as the button, on a light track of its colour, with the
 * button's colour filling from the start side to [progress]. The label is drawn twice (ink on the track, the
 * button's text colour on the fill) so it reads on both parts. Not clickable; TalkBack hears a disabled button.
 */
@Composable
private fun ConfirmFill(text: String, progress: () -> Float, colors: ButtonColors, modifier: Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val track = colors.containerColor.copy(alpha = KcButtonDefaults.ProgressTrackAlpha)
    val label: @Composable (androidx.compose.ui.graphics.Color) -> Unit = { color ->
        Box(
            Modifier.fillMaxWidth().heightIn(min = KcButtonDefaults.MinHeight)
                .padding(KcButtonDefaults.ContentPadding),
            contentAlignment = Alignment.Center,
        ) { Text(text, style = MaterialTheme.typography.labelLarge, color = color) }
    }
    Box(
        modifier
            .heightIn(min = KcButtonDefaults.MinHeight)
            .clip(KcShapes.pill)
            .background(track)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                disabled()
            },
    ) {
        label(colors.containerColor)
        Box(
            Modifier.matchParentSize()
                // The second label is drawn only: TalkBack hears the button once.
                .clearAndSetSemantics { testTag = CONFIRM_FILL_TAG }
                .drawWithContent {
                    val w = size.width * progress().coerceIn(0f, 1f)
                    val left = if (rtl) size.width - w else 0f
                    clipRect(left = left, right = left + w) { this@drawWithContent.drawContent() }
                }
                .background(colors.containerColor),
        ) { label(colors.contentColor) }
    }
}
