// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Owner brief H6 (UI-3): the backup screens PR H1 changed that had no picture yet: copied and waiting for the second
 * check, each live state of the re-plug check, the free step's confirmation (P1, P2, P4) and its measured result, in
 * {en, he} × {light, dark}. 200 % is in [KeyScreensScreenshotTest], tablet in [TabletScreenshotTest].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class H6ScreenshotTest(private val locale: String, private val night: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    private val shots get() = Shots(compose, locale, night)

    @Test fun backupCopied(): Unit = shots.backupCopied()

    @Test fun checkUnplug(): Unit = shots.backupCheck("unplug")

    @Test fun checkPlugBack(): Unit = shots.backupCheck("plugback")

    @Test fun checkChecking(): Unit = shots.backupCheck("checking")

    @Test fun checkDone(): Unit = shots.backupCheck("done")

    @Test fun freeConfirm(): Unit = shots.freeConfirm()

    @Test fun freeResult(): Unit = shots.freeResult()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}")
        fun params(): List<Array<Any>> = listOf("en", "iw").flatMap { l -> listOf(false, true).map { arrayOf(l, it) } }
    }
}
