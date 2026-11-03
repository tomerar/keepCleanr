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
 * F3 (UI-3): every screen on the owner-shaped phone ([OwnerPhone]) in {en, he} × {light, dark} on API 36. Home and
 * the ConfirmSheet also on API 30 and at 200% font ([KeyScreensScreenshotTest]); tablet and foldable in
 * [LargeScreenshotTest] (C96). Record with `./gradlew :app:recordRoborazziFossDebug`; CI runs
 * `verifyRoborazziFossDebug`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class ScreensScreenshotTest(private val locale: String, private val night: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    private val shots get() = Shots(compose, locale, night)

    @Test fun homeBeforeAccess(): Unit = shots.homeBeforeAccess()

    @Test fun home(): Unit = shots.home()

    @Test fun confirm(): Unit = shots.confirm()

    @Test fun result(): Unit = shots.result()

    @Test fun reviewList(): Unit = shots.reviewList()

    @Test fun reviewGroup(): Unit = shots.reviewGroup()

    @Test fun swipe(): Unit = shots.swipe()

    @Test fun swipeHint(): Unit = shots.swipeHint()

    @Test fun swipeDrag(): Unit = shots.swipeDrag()

    @Test fun swipeResume(): Unit = shots.swipeResume()

    @Test fun trash(): Unit = shots.trash()

    @Test fun trashActions(): Unit = shots.trashActions()

    @Test fun trashList(): Unit = shots.trashList()

    @Test fun trashDetails(): Unit = shots.trashDetails()

    @Test fun backup(): Unit = shots.backup()

    @Test fun backupProgress(): Unit = shots.backupProgress()

    @Test fun backupSummary(): Unit = shots.backupSummary()

    @Test fun restore(): Unit = shots.restore()

    @Test fun restorePartial(): Unit = shots.restorePartial()

    @Test fun apps(): Unit = shots.apps()

    @Test fun settings(): Unit = shots.settings()

    @Test fun settingsAdvanced(): Unit = shots.settingsAdvanced()

    @Test fun allClean(): Unit = shots.allClean()

    @Test fun intro1(): Unit = shots.introPage(0)

    @Test fun intro2(): Unit = shots.introPage(1)

    @Test fun intro3(): Unit = shots.introPage(2)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}")
        fun params(): List<Array<Any>> = listOf("en", "iw").flatMap { l -> listOf(false, true).map { arrayOf(l, it) } }
    }
}
