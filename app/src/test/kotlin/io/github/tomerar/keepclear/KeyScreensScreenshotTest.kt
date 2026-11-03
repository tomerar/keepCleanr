// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.os.Build
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F3 (UI-3): Home and its ConfirmSheet, the two screens every user sees, also on API 30 (100% font) and at 200% font
 * (API 36), in {en, he} × {light, dark}; the review grid, the backup receipt and the Trash at 200% too. The rest of the cross product (every screen × API 30 × 200%) is left out to
 * keep CI time and the repository small (C96).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30, 36], qualifiers = "w360dp-h800dp-xhdpi")
class KeyScreensScreenshotTest(private val locale: String, private val night: Boolean, private val font: Int) {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun onlyTheChosenCombinations() {
        // API 30 at 100%; API 36 at 200% (API 36 at 100% is in ScreensScreenshotTest).
        assumeTrue(if (Build.VERSION.SDK_INT == 30) font == 100 else font == 200)
    }

    private val shots get() = Shots(compose, locale, night, fontScale = font / 100f)

    @Test fun home(): Unit = shots.home()

    @Test fun confirm(): Unit = shots.confirm()

    // UI-3 also asks for 200% on the review grid, the backup receipt and the Trash.
    @Test fun reviewGroup(): Unit = at200 { shots.reviewGroup() }

    @Test fun backupSummary(): Unit = at200 { shots.backupSummary() }

    @Test fun trash(): Unit = at200 { shots.trash() }

    // Owner brief H6: swipe at 200% too (PR H2b: the session, the hint and the resume prompt).
    @Test fun swipe(): Unit = at200 { shots.swipe() }

    @Test fun swipeHint(): Unit = at200 { shots.swipeHint() }

    @Test fun swipeResume(): Unit = at200 { shots.swipeResume() }

    // Owner brief H6: the first-run and the all-clean Home, and the backup (the tab, backup mode, the free step) at
    // 200 % too.
    @Test fun homeBeforeAccess(): Unit = at200 { shots.homeBeforeAccess() }

    @Test fun allClean(): Unit = at200 { shots.allClean() }

    @Test fun backup(): Unit = at200 { shots.backup() }

    @Test fun backupProgress(): Unit = at200 { shots.backupProgress() }

    @Test fun freeConfirm(): Unit = at200 { shots.freeConfirm() }

    private fun at200(shot: () -> Unit) {
        assumeTrue(font == 200)
        shot()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}-font={2}")
        fun params(): List<Array<Any>> = listOf("en", "iw").flatMap { l ->
            listOf(false, true).flatMap { night -> listOf(100, 200).map { arrayOf(l, night, it) } }
        }
    }
}
