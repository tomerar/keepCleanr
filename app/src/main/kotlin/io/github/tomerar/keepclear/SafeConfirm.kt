// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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

/** SEC-7 / E-SEC-10: a touch delivered while another app's window covers ours. */
object ObscuredTouch {
    fun isObscured(flags: Int): Boolean =
        flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
}

/**
 * Confirm button for destructive actions (SEC-7). Enabled only [delayMillis] after it appears, and it ignores a
 * tap whose touch arrived while another window covered the screen (tapjacking, E-SEC-10). The check runs on the
 * touch itself, so it also works inside dialogs, whose windows don't inherit the activity's
 * `filterTouchesWhenObscured` (review PR B, M1).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SafeConfirmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    delayMillis: Long = 500,
    colors: ButtonColors? = null,
    /**
     * True for actions that can't be undone (empty the trash, free now): error colours. False for actions that go
     * to the 30-day trash: normal primary colours, so "recoverable" never looks alarming (review PR C).
     */
    permanent: Boolean = true,
) {
    var enabled by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    val lastDownObscured = remember { booleanArrayOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis)
        enabled = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = {
                // The flag belongs to the gesture that just ended: reset it, so a later keyboard or TalkBack
                // activation (no touch) or an unobscured tap is not blocked by an old rejected tap.
                val obscured = lastDownObscured[0]
                lastDownObscured[0] = false
                if (obscured) blocked = true else onClick()
            },
            enabled = enabled,
            colors = colors ?: if (permanent) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            },
            modifier = modifier.pointerInteropFilter { event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    lastDownObscured[0] = ObscuredTouch.isObscured(event.flags)
                }
                false // observe only; the button still gets the touch and decides in onClick
            },
        ) { Text(text) }
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
