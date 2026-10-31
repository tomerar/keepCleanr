// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Calm, neutral palette: no alarm reds for normal states (ux-flows §0). Destructive colours stay
// fixed and high-contrast in both themes (UI-1); dynamic colour comes later as an opt-in.
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1F5F4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9EBD9),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635B),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF181C1B),
    surface = Color(0xFFF7FAF8),
    onSurface = Color(0xFF181C1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F2),
    surfaceContainer = Color(0xFFEBEFEC),
    surfaceContainerHigh = Color(0xFFE5E9E6),
    surfaceContainerHighest = Color(0xFFDFE4E1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF9DD0BE),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF00513D),
    onPrimaryContainer = Color(0xFFB9EBD9),
    secondary = Color(0xFFB1CCC2),
    onSecondary = Color(0xFF1C352E),
    background = Color(0xFF101413),
    onBackground = Color(0xFFDFE4E1),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFDFE4E1),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = Color(0xFF181C1B),
    surfaceContainer = Color(0xFF1C201F),
    surfaceContainerHigh = Color(0xFF262B29),
    surfaceContainerHighest = Color(0xFF313634),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

@Composable
fun KeepClearTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
