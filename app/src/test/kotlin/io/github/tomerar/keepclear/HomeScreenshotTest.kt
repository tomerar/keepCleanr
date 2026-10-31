// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.os.Build
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI-3 (M0 subset): Home in {en, he} × {light, dark} on API 30 and 36.
 * Record with `./gradlew :app:recordRoborazziFossDebug`; CI runs `verifyRoborazziFossDebug`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30, 36], qualifiers = "w360dp-h800dp-xxhdpi")
class HomeScreenshotTest(private val locale: String, private val night: Boolean) {
    @Test
    fun home() {
        RuntimeEnvironment.setQualifiers("+$locale-${if (night) "night" else "notnight"}")
        val name = "home_${locale}_${if (night) "dark" else "light"}_api${Build.VERSION.SDK_INT}"
        // Tolerate tiny anti-aliasing differences between machines; layout or text changes still fail.
        // Created inside the test: RoborazziOptions reads settings that exist only in the Robolectric sandbox.
        val options = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f),
        )
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = options) {
            KeepClearTheme(darkTheme = night) { HomeScreen(showDebugTools = false) }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}")
        fun params(): List<Array<Any>> = listOf("en", "iw").flatMap { l ->
            listOf(false, true).map { arrayOf<Any>(l, it) }
        }
    }
}
