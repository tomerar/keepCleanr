// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * "Paper & Ink" Material 3 colour schemes (ADR 0013, docs/design/design-system.md §2). The brief's tokens are used
 * exactly; every other role is derived from the palette and documented in §2.2, so Material's default lavender never
 * shows. `ContrastTest` checks every text/background pair in both themes.
 */
object KeepClearSchemes {
    val Light: ColorScheme = lightColorScheme(
        primary = Color(0xFF2F4B7C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE4F5),
        onPrimaryContainer = Color(0xFF13284A),
        inversePrimary = Color(0xFFA9C1EE),
        secondary = Color(0xFF5B5850),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFECE6DA),
        onSecondaryContainer = Color(0xFF1E1C19),
        tertiary = Color(0xFF3D6B4F),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDCEBDF),
        onTertiaryContainer = Color(0xFF10291A),
        background = Color(0xFFF6F2EA),
        onBackground = Color(0xFF1E1C19),
        surface = Color(0xFFFFFDF8),
        onSurface = Color(0xFF1E1C19),
        surfaceVariant = Color(0xFFEFE9DE),
        onSurfaceVariant = Color(0xFF5A554D),
        // = surface: raised paper gets no blue tint (tonal elevation draws nothing).
        surfaceTint = Color(0xFFFFFDF8),
        inverseSurface = Color(0xFF33302B),
        inverseOnSurface = Color(0xFFF6F2EA),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        outline = Color(0xFF8A8378),
        outlineVariant = Color(0xFFDDD6CA),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFFFDF8),
        surfaceContainer = Color(0xFFFBF8F1),
        surfaceContainerHigh = Color(0xFFF4EFE6),
        surfaceContainerHighest = Color(0xFFEEE8DD),
        surfaceContainerLow = Color(0xFFFDFAF4),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFE4DED3),
    )

    val Dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFA9C1EE),
        onPrimary = Color(0xFF10284F),
        primaryContainer = Color(0xFF2A3F66),
        onPrimaryContainer = Color(0xFFDCE4F5),
        inversePrimary = Color(0xFF2F4B7C),
        secondary = Color(0xFFCFC7BA),
        onSecondary = Color(0xFF2E2B26),
        secondaryContainer = Color(0xFF3A3732),
        onSecondaryContainer = Color(0xFFECE6DC),
        tertiary = Color(0xFF9DCDAE),
        onTertiary = Color(0xFF0E2A19),
        tertiaryContainer = Color(0xFF24392C),
        onTertiaryContainer = Color(0xFFDCEBDF),
        background = Color(0xFF161513),
        onBackground = Color(0xFFECE6DC),
        surface = Color(0xFF1E1C1A),
        onSurface = Color(0xFFECE6DC),
        surfaceVariant = Color(0xFF2E2B27),
        onSurfaceVariant = Color(0xFFBDB5A8),
        surfaceTint = Color(0xFF1E1C1A),
        inverseSurface = Color(0xFFECE6DC),
        inverseOnSurface = Color(0xFF33302B),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        outline = Color(0xFF8F887D),
        outlineVariant = Color(0xFF3A3732),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF3A3732),
        surfaceContainer = Color(0xFF24221F),
        surfaceContainerHigh = Color(0xFF2C2A26),
        surfaceContainerHighest = Color(0xFF35322E),
        surfaceContainerLow = Color(0xFF1B1917),
        surfaceContainerLowest = Color(0xFF121110),
        surfaceDim = Color(0xFF161513),
    )
}

/** The three lanes of the app (requirements: SAFE, REVIEW, MOVE). Always shown as icon + label, never colour alone. */
enum class KcLane { SAFE, REVIEW, MOVE }

/**
 * One lane's colours: [accent] for its icon, strip segment and short labels; text on [container] uses
 * [onContainer].
 */
@Immutable
data class LaneColors(val accent: Color, val container: Color, val onContainer: Color)

/** KeepClear's colours beyond Material's roles (design-system.md §2.3), read through `KeepClearTheme.colors`. */
@Immutable
data class KeepClearColors(
    val safe: LaneColors,
    val review: LaneColors,
    val move: LaneColors,
    /** StorageStrip: space used by everything else (= outline, ≥ 3:1 on surface). */
    val stripUsed: Color,
    /** StorageStrip track: free space (= outlineVariant). */
    val stripFree: Color,
) {
    fun lane(lane: KcLane): LaneColors = when (lane) {
        KcLane.SAFE -> safe
        KcLane.REVIEW -> review
        KcLane.MOVE -> move
    }

    companion object {
        val Light = KeepClearColors(
            safe = LaneColors(Color(0xFF3D6B4F), Color(0xFFDCEBDF), Color(0xFF10291A)),
            review = LaneColors(Color(0xFF4D5D73), Color(0xFFE1E6EE), Color(0xFF151E2B)),
            move = LaneColors(Color(0xFF8A5A12), Color(0xFFF5E4C6), Color(0xFF2B1A00)),
            stripUsed = Color(0xFF8A8378),
            stripFree = Color(0xFFDDD6CA),
        )
        val Dark = KeepClearColors(
            safe = LaneColors(Color(0xFF9DCDAE), Color(0xFF24392C), Color(0xFFDCEBDF)),
            review = LaneColors(Color(0xFFB7C4D8), Color(0xFF2C3440), Color(0xFFE1E6EE)),
            move = LaneColors(Color(0xFFE7B970), Color(0xFF3D2E14), Color(0xFFF5E4C6)),
            stripUsed = Color(0xFF8F887D),
            stripFree = Color(0xFF3A3732),
        )
    }
}

val LocalKeepClearColors = staticCompositionLocalOf { KeepClearColors.Light }
