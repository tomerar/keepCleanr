// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design QA item 8 with Robolectric's real Android text stack: a Hebrew line with a size and then a count, as
 * `totalText` and the F3 ConfirmSheet title build it, keeps the count's number before its word and the size first, on
 * API 30 and 36. With the count in an FSI isolate, API 30 drew „7.9GB · 4,983 קבצים” as "קבצים4,983" (the F3 bug);
 * the on-device test (`BidiTitleOnDeviceTest`, CI emulators) checks the same on a real Android 11.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BidiLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val size = Bidi.isolate("7.9GB")
    private val count = Bidi.isolate("4,983 קבצים")
    private val lines = listOf(
        "$size · $count",
        "לנקות $size · $count?",
        "$count · $size",
        "לנקות $size?",
    )

    private fun check() {
        val layouts = mutableMapOf<Int, TextLayoutResult>()
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(Modifier.width(360.dp)) {
                    lines.forEachIndexed { i, line -> Text(line, onTextLayout = { layouts[i] = it }) }
                }
            }
        }
        compose.waitForIdle()
        lines.forEachIndexed { i, line ->
            val layout = layouts.getValue(i)

            fun x(part: String) = line.indexOf(part).takeIf { it >= 0 }?.let { layout.getBoundingBox(it).center.x }
            val number = x("4,")
            val word = x("קבצים")
            val gb = x("GB")!!
            if (number != null && word != null) {
                // Right to left: the number is read first, so it is to the right of its word.
                assertEquals("$i: number before word in \"$line\"", true, number > word)
                val sizeFirst = line.indexOf("GB") < line.indexOf("4,")
                assertEquals("$i: size and count in reading order in \"$line\"", sizeFirst, gb > number && gb > word)
            }
            line.indexOf('?').takeIf { it >= 0 }?.let { q ->
                assertEquals(
                    "$i: question mark at the end (left) of \"$line\"",
                    true,
                    layout.getBoundingBox(q).center.x < gb,
                )
            }
        }
    }

    @Test
    @Config(sdk = [30])
    fun `a Hebrew size and count read in order on Android 11`(): Unit = check()

    @Test
    @Config(sdk = [36])
    fun `a Hebrew size and count read in order on Android 16`(): Unit = check()
}
