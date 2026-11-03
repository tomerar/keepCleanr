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
 * Owner brief H6 (UI-2, UI-3): the screens the H round changed most, on a tablet (1280 × 800 dp, the navigation
 * rail): Home, the trash grid (more columns), backup mode, swipe, the free confirmation and Help. Hebrew light and
 * English dark only, to keep the repository small (C195); [LargeScreenshotTest] has Home in both languages, light.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-mdpi")
class TabletScreenshotTest(private val locale: String, private val night: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    private val shots get() = Shots(compose, locale, night, prefix = "tablet_")

    @Test fun home(): Unit = shots.home()

    @Test fun trash(): Unit = shots.trash()

    @Test fun backupProgress(): Unit = shots.backupProgress()

    @Test fun swipe(): Unit = shots.swipe()

    @Test fun freeConfirm(): Unit = shots.freeConfirm()

    @Test
    fun help() {
        val s = shots
        s.show(alone = true) { HelpScreen(HelpTopics.all(foss = true), onOpenTopic = {}, onBack = {}) }
        s.capture("help")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}")
        fun params(): List<Array<Any>> = listOf(arrayOf("iw", false), arrayOf("en", true))
    }
}
