// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Calm, neutral palette: no alarm reds for normal states (ux-flows §0). Destructive colours stay fixed and
 * high-contrast in both themes (UI-1). Every role is set (review PR C), so Material's default lavender never
 * shows; `ThemeTest` checks text/background contrast for each pair. Dynamic colour comes later as an opt-in.
 */
object KeepClearColors {
    val Light: ColorScheme = lightColorScheme(
        primary = Color(0xFF1F5F4F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB9EBD9),
        onPrimaryContainer = Color(0xFF00201A),
        inversePrimary = Color(0xFF9DD0BE),
        secondary = Color(0xFF4A635B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCCE8DD),
        onSecondaryContainer = Color(0xFF06201A),
        tertiary = Color(0xFF3F6375),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC3E8FD),
        onTertiaryContainer = Color(0xFF001F2A),
        background = Color(0xFFF7FAF8),
        onBackground = Color(0xFF181C1B),
        surface = Color(0xFFF7FAF8),
        onSurface = Color(0xFF181C1B),
        surfaceVariant = Color(0xFFDBE5E0),
        onSurfaceVariant = Color(0xFF3F4945),
        surfaceTint = Color(0xFF1F5F4F),
        inverseSurface = Color(0xFF2D3130),
        inverseOnSurface = Color(0xFFEEF2EF),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFE1DD),
        onErrorContainer = Color(0xFF410E0B),
        outline = Color(0xFF6F7975),
        outlineVariant = Color(0xFFBFC9C4),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFF7FAF8),
        surfaceContainer = Color(0xFFEBEFEC),
        surfaceContainerHigh = Color(0xFFE5E9E6),
        surfaceContainerHighest = Color(0xFFDFE4E1),
        surfaceContainerLow = Color(0xFFF1F5F2),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFD7DBD8),
    )

    val Dark: ColorScheme = darkColorScheme(
        primary = Color(0xFF9DD0BE),
        onPrimary = Color(0xFF003829),
        primaryContainer = Color(0xFF00513D),
        onPrimaryContainer = Color(0xFFB9EBD9),
        inversePrimary = Color(0xFF1F5F4F),
        secondary = Color(0xFFB1CCC2),
        onSecondary = Color(0xFF1C352E),
        secondaryContainer = Color(0xFF334B44),
        onSecondaryContainer = Color(0xFFCCE8DD),
        tertiary = Color(0xFFA7CCE0),
        onTertiary = Color(0xFF0C3445),
        tertiaryContainer = Color(0xFF264B5C),
        onTertiaryContainer = Color(0xFFC3E8FD),
        background = Color(0xFF101413),
        onBackground = Color(0xFFDFE4E1),
        surface = Color(0xFF101413),
        onSurface = Color(0xFFDFE4E1),
        surfaceVariant = Color(0xFF3F4945),
        onSurfaceVariant = Color(0xFFBFC9C4),
        surfaceTint = Color(0xFF9DD0BE),
        inverseSurface = Color(0xFFDFE4E1),
        inverseOnSurface = Color(0xFF2D3130),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF7D1A15),
        onErrorContainer = Color(0xFFFFE1DD),
        outline = Color(0xFF899390),
        outlineVariant = Color(0xFF3F4945),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF363A39),
        surfaceContainer = Color(0xFF1C201F),
        surfaceContainerHigh = Color(0xFF262B29),
        surfaceContainerHighest = Color(0xFF313634),
        surfaceContainerLow = Color(0xFF181C1B),
        surfaceContainerLowest = Color(0xFF0B0F0E),
        surfaceDim = Color(0xFF101413),
    )
}

@Composable
fun KeepClearTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) KeepClearColors.Dark else KeepClearColors.Light,
        content = content,
    )
}
