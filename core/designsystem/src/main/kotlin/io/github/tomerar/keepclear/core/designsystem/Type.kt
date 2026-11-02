// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Bundled fonts (SIL OFL 1.1, THIRD_PARTY.md): static instances of the Rubik and Heebo variable fonts, only the
 * weights used, so every Android version draws the same weight (design-system.md §3). Rubik: headings and every
 * number; Heebo: body and UI text.
 */
object KcFonts {
    val Rubik = FontFamily(
        Font(R.font.kc_rubik_regular, FontWeight.Normal),
        Font(R.font.kc_rubik_medium, FontWeight.Medium),
        Font(R.font.kc_rubik_semibold, FontWeight.SemiBold),
    )
    val Heebo = FontFamily(
        Font(R.font.kc_heebo_regular, FontWeight.Normal),
        Font(R.font.kc_heebo_medium, FontWeight.Medium),
    )

    /** Tabular figures, so digits keep their width while a number counts. */
    const val TABULAR = "tnum"
}

private fun rubik(size: Int, line: Int, weight: FontWeight = FontWeight.Medium) = TextStyle(
    fontFamily = KcFonts.Rubik,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
)

private fun heebo(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = KcFonts.Heebo,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
)

val KeepClearTypography = Typography(
    displayLarge = rubik(57, 64),
    displayMedium = rubik(45, 52),
    displaySmall = rubik(36, 44),
    headlineLarge = rubik(32, 40),
    headlineMedium = rubik(28, 36),
    headlineSmall = rubik(24, 32),
    titleLarge = rubik(22, 28),
    titleMedium = rubik(18, 24),
    titleSmall = rubik(16, 22),
    bodyLarge = heebo(16, 24),
    bodyMedium = heebo(14, 20),
    bodySmall = heebo(13, 18),
    labelLarge = heebo(14, 20, FontWeight.Medium),
    labelMedium = heebo(13, 18, FontWeight.Medium),
    labelSmall = heebo(12, 16, FontWeight.Medium),
)

/** Number styles beyond Material's scale, read through `KeepClearTheme.type`. */
@Immutable
data class KeepClearType(
    /** The free-space number: Rubik SemiBold 52sp, tabular figures. */
    val hero: TextStyle,
    /** Sizes and counts inside rows and cards: Rubik Medium 16sp, tabular figures. */
    val number: TextStyle,
) {
    companion object {
        val Default = KeepClearType(
            hero = rubik(52, 60, FontWeight.SemiBold).copy(fontFeatureSettings = KcFonts.TABULAR),
            number = rubik(16, 24).copy(fontFeatureSettings = KcFonts.TABULAR),
        )
    }
}

val LocalKeepClearType = staticCompositionLocalOf { KeepClearType.Default }
