// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import io.github.tomerar.keepclear.core.designsystem.KcContrast
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.move.Receipt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PR H4 (UI-3): the manage-space screen, Settings' troubleshooting section, the "the phone stopped the backup" card,
 * Home in high contrast, and Home at 200 % font with the largest display size, in {en, he} × {light, dark}.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class H4ScreenshotTest(private val locale: String, private val night: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    private val shots get() = Shots(compose, locale, night)

    @Test
    fun manageSpace() {
        val s = shots
        s.show { ManageSpaceScreen(ManageSpaceUi(trash = Total(1_284, 3_400_000_000))) }
        s.capture("manage_space")
    }

    @Test
    fun troubleshooting() {
        val s = shots
        s.show {
            SettingsScreen(
                false,
                {},
                {},
                {},
                SettingsActions(
                    troubleshooting = TroubleshootingUi(
                        lastRunAt = OwnerPhone.NOW - 3_600_000,
                        stoppedByPhone = true,
                        battery = BatteryActions(BatterySteps.forMaker("OnePlus")),
                    ),
                    onOpenChannel = {},
                ),
            )
        }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("troubleshooting"))
        s.capture("troubleshooting")
    }

    @Test
    fun killCard() {
        val s = shots
        s.show {
            MoveScreen(
                summary = ClassificationSummary.of(emptyList()),
                state = MoveUiState(
                    jobs = listOf(
                        JobView(
                            MoveJob(
                                "j",
                                "t",
                                "SanDisk",
                                "KeepClear/OnePlus 10T",
                                1,
                                JobState.PAUSED,
                                PauseReason.STOPPED_BY_PHONE,
                            ),
                            Receipt(
                                "j",
                                "SanDisk",
                                "KeepClear/OnePlus 10T",
                                2_104,
                                9_000_000_000,
                                emptyMap(),
                                0,
                                4_726,
                                0,
                            ),
                            true,
                        ),
                    ),
                ),
                now = OwnerPhone.NOW,
                notificationsOff = false,
                actions = MoveActions(battery = BatteryActions(BatterySteps.forMaker("OnePlus"))),
                onBack = null,
            )
        }
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("killCard"))
        s.capture("kill_card")
    }

    @Test
    fun homeHighContrast() {
        val s = shots
        compose.setContent {
            KeepClearTheme(darkTheme = night, reducedMotion = true, contrast = KcContrast.HIGH) {
                CompositionLocalProvider(
                    LocalThumbnailLoader provides OwnerPhone.thumbnails,
                    LocalClock provides { OwnerPhone.NOW },
                ) { s.Home() }
            }
        }
        s.capture("home_high_contrast")
    }

    /** 200 % font and the largest display size: the same phone is only about 290 dp wide. */
    @Test
    @Config(qualifiers = "w290dp-h640dp-xhdpi")
    fun homeLargest() {
        RuntimeEnvironment.setFontScale(2f)
        val s = Shots(compose, locale, night, fontScale = 2f)
        s.show { s.Home() }
        s.capture("home_largest")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}")
        fun params(): List<Array<Any>> = listOf("en", "iw").flatMap { l -> listOf(false, true).map { arrayOf(l, it) } }
    }
}
