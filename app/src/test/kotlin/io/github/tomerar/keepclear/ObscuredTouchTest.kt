// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** E-SEC-10 / SEC-7: a tap that arrives while another window covers the screen never confirms. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ObscuredTouchTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var clicks = 0

    private fun tap(flags: Int) {
        val bounds = compose.onNodeWithTag("confirm").fetchSemanticsNode().boundsInWindow
        val x = bounds.center.x
        val y = bounds.center.y
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val t = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val props = arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType =
                            MotionEvent.TOOL_TYPE_FINGER
                    },
                )
                val coords = arrayOf(
                    MotionEvent.PointerCoords().apply {
                        this.x = x
                        this.y = y
                        pressure = 1f
                        size =
                            1f
                    },
                )
                val e = MotionEvent.obtain(t, t + 10, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0x1002, flags)
                view.dispatchTouchEvent(e)
                e.recycle()
            }
        }
        compose.waitForIdle()
    }

    private fun show() {
        compose.setContent {
            KeepClearTheme {
                SafeConfirmButton("Delete", onClick = {
                    clicks++
                }, modifier = androidx.compose.ui.Modifier.testTag("confirm"))
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    @Test
    fun `an obscured tap is ignored and explained`() {
        show()
        tap(MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(0, clicks)
        assertEquals(1, compose.onAllNodesWithTag("obscuredHint").fetchSemanticsNodes().size)
        tap(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        assertEquals(0, clicks)
    }

    @Test
    fun `a normal tap confirms`() {
        show()
        tap(0)
        assertEquals(1, clicks)
    }

    @Test
    fun `after a rejected obscured tap, TalkBack or keyboard activation still works`() {
        show()
        tap(MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(0, clicks)
        compose.onNodeWithTag("confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, clicks)
    }
}
