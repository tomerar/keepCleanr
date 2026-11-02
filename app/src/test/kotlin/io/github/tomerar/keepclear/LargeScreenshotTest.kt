// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.os.Build
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UI-2: Home (F3: on the owner-shaped phone) at foldable (673×841 dp) and tablet (1280×800 dp) sizes keeps a readable width. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LargeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /** F3: the owner-shaped phone, with its pictures, as on the phone screenshots. */
    private val scanned = OwnerPhone.home.copy(trash = OwnerPhone.trash)

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun capture(name: String, locale: String = "en") {
        RuntimeEnvironment.setQualifiers("+$locale")
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalThumbnailLoader provides OwnerPhone.thumbnails,
                    LocalClock provides { OwnerPhone.NOW },
                ) {
                    AppScreens(state = scanned, onOpen = {}, onBack = {}, home = {
                        HomeScreen(state = scanned, showDebugTools = false)
                    })
                }
            }
        }
        // Waits for the pictures, which load after the first frame.
        compose.waitForIdle()
        val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f))
        com.github.takahirom.roborazzi.captureScreenRoboImage(
            "src/test/screenshots/${name}_api${Build.VERSION.SDK_INT}.png",
            roborazziOptions = options,
        )
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp-xhdpi")
    fun foldable(): Unit = capture("large_foldable_en")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun tablet(): Unit = capture("large_tablet_en")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun tabletHebrew(): Unit = capture("large_tablet_iw", locale = "iw")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun `content is never wider than the readable width`() {
        RuntimeEnvironment.setQualifiers("+en")
        compose.setContent {
            KeepClearTheme {
                AppScreens(state = scanned, onOpen = {}, onBack = {}, home = {
                    HomeScreen(state = scanned, showDebugTools = false)
                })
            }
        }
        val bounds = compose.onNodeWithTag("storage").getBoundsInRoot()
        assertTrue(bounds.right - bounds.left <= MAX_CONTENT_WIDTH + 1.dp)
    }
}
