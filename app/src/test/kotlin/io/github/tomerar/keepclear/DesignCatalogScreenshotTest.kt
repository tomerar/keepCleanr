// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KcLane
import io.github.tomerar.keepclear.core.designsystem.KcSpacing
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.DaysLeftChip
import io.github.tomerar.keepclear.core.designsystem.component.HeroNumber
import io.github.tomerar.keepclear.core.designsystem.component.InfoChip
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.designsystem.component.LaneCard
import io.github.tomerar.keepclear.core.designsystem.component.ResultBanner
import io.github.tomerar.keepclear.core.designsystem.component.StorageStrip
import io.github.tomerar.keepclear.core.designsystem.component.StorageUsage
import io.github.tomerar.keepclear.core.designsystem.component.VerifiedStamp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F1a: the design system's component catalogue (design-system.md §7) in {en, he} × {light, dark}. Sample texts are
 * test fixtures (the screens' real strings come in F2). Record with `./gradlew :app:recordRoborazziFossDebug`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xxhdpi")
class DesignCatalogScreenshotTest(private val locale: String, private val night: Boolean) {
    private val he get() = locale == "iw"

    private fun t(en: String, iw: String) = if (he) iw else en

    private val thumbs = listOf(Color(0xFF9DB4C0), Color(0xFFC2A878), Color(0xFF7D9A84), Color(0xFFB59AA8))
        .map(::ColorPainter)

    @Test
    fun catalogue() {
        RuntimeEnvironment.setQualifiers("+$locale-${if (night) "night" else "notnight"}")
        val suffix = "${locale}_${if (night) "dark" else "light"}_api${Build.VERSION.SDK_INT}"
        val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f))
        captureRoboImage("src/test/screenshots/design_home_$suffix.png", roborazziOptions = options) {
            Page { HomeParts() }
        }
        captureRoboImage("src/test/screenshots/design_parts_$suffix.png", roborazziOptions = options) {
            Page { OtherParts() }
        }
    }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        KeepClearTheme(darkTheme = night, reducedMotion = true) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(KcSpacing.m), verticalArrangement = Arrangement.spacedBy(KcSpacing.m)) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun HomeParts() {
        HeroNumber(
            bytes = 16_800_000_000,
            label = t("free", "פנויים"),
            sentence = t("7.9 GB can be freed safely", "אפשר לפנות בבטחה 7.9GB"),
            strip = {
                StorageStrip(
                    StorageUsage(
                        total = 128_000_000_000,
                        free = 16_800_000_000,
                        safe = 7_900_000_000,
                        review = 9_400_000_000,
                        move = 31_000_000_000,
                    ),
                    description = "",
                    afterCleaning = true,
                )
            },
            action = { KcButton(t("Clean now", "לנקות עכשיו"), {}, icon = KcIcons.Safe) },
        )
        LaneCard(
            KcLane.REVIEW,
            t("Choose what to delete", "לבחור מה למחוק"),
            count = t("2,324 screenshots", "2,324 צילומי מסך"),
            size = t("9.4 GB", "9.4GB"),
            onClick = {},
            thumbnails = thumbs,
            thumbnailTotal = 2_324,
        )
        LaneCard(
            KcLane.MOVE,
            t("Move to backup", "להעביר לגיבוי"),
            count = t("10,472 photos and videos", "10,472 תמונות וסרטונים"),
            size = t("31 GB", "31.0GB"),
            onClick = {},
        )
    }

    @Composable
    private fun OtherParts() {
        LaneCard(
            KcLane.SAFE,
            t("Clean now", "לנקות עכשיו"),
            count = t("1,204 files", "1,204 קבצים"),
            size = t("7.9 GB", "7.9GB"),
            supporting = t("Goes to the trash for 30 days", "עובר לסל המחזור ל-30 יום"),
        )
        ResultBanner(
            message = t("Freed 7.9 GB", "פונו 7.9GB"),
            detail = t("Now 24.7 GB free (was 16.8 GB)", "עכשיו פנויים 24.7GB (היו 16.8GB)"),
            undoText = t("Undo", "לבטל"),
            onUndo = {},
            dismissLabel = t("Close", "לסגור"),
            onDismiss = {},
        )
        Row(horizontalArrangement = Arrangement.spacedBy(KcSpacing.s)) {
            DaysLeftChip(t("23 days left", "עוד 23 ימים"), daysLeft = 23)
            DaysLeftChip(t("2 days left", "עוד יומיים"), daysLeft = 2)
        }
        InfoChip(t("Why is this here?", "למה זה כאן?"), onClick = {})
        KcCard {
            VerifiedStamp(t("Checked file by file", "נבדק קובץ-קובץ"), Modifier.padding(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KcSpacing.s)) {
            KcButton(t("Restore", "לשחזר"), {}, style = KcButtonStyle.Tonal)
            KcButton(t("See details", "לראות פרטים"), {}, style = KcButtonStyle.Text)
        }
        KcButton(t("Empty now", "לרוקן עכשיו"), {}, style = KcButtonStyle.Destructive)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}")
        fun params(): List<Array<Any>> = listOf("en", "iw").flatMap { l ->
            listOf(false, true).map { arrayOf<Any>(l, it) }
        }
    }
}
