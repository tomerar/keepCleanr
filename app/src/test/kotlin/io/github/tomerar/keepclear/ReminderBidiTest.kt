// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Owner brief H2 item 10 (PR H2c): the Hebrew reminder setting read „(או GB 2)” because "2 GB" was typed into the
 * sentence with a space. Rendered with Robolectric's real text stack (as `BidiLayoutTest`), as Settings builds it: the
 * size keeps its digits before its unit, and in the right-to-left line „או” comes before the size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "iw")
class ReminderBidiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun check() {
        var layout: TextLayoutResult? = null
        var line = ""
        compose.setContent {
            KeepClearTheme {
                line = reminderBody()
                Text(line, Modifier.width(360.dp), onTextLayout = { layout = it })
            }
        }
        compose.waitForIdle()
        val l = layout!!

        val open = line.indexOf('(')
        val digit = line.indexOf('2', open)
        val unit = line.indexOf("GB", open)
        assertTrue("the size follows „או” in \"$line\"", digit in (open + 1) until unit)
        val two = l.getBoundingBox(digit).center.x
        val gb = l.getBoundingBox(unit).center.x
        assertTrue("2 is drawn left of GB in \"$line\"", two < gb)
        val or = l.getBoundingBox(line.indexOf("או", open)).center.x
        assertTrue("„או” is read before the size (to its right) in \"$line\"", or > gb)
        assertTrue("the size sits inside the parentheses in \"$line\"", l.getBoundingBox(open).center.x > gb)
    }

    @Test
    @Config(sdk = [30])
    fun `the reminder's size reads in order on Android 11`(): Unit = check()

    @Test
    @Config(sdk = [36])
    fun `the reminder's size reads in order on Android 16`(): Unit = check()
}
