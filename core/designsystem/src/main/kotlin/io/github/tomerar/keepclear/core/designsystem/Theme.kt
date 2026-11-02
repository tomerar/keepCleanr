// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The "Paper & Ink" theme (ADR 0013). Dynamic colour is off by default so lane colours keep their meaning; the
 * parameter stays for a later opt-in (UI-1). [reducedMotion] defaults to Android's "Remove animations" setting,
 * read once; tests pass it explicitly.
 */
@Composable
fun KeepClearTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    reducedMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> {
            KeepClearSchemes.Dark
        }

        else -> {
            KeepClearSchemes.Light
        }
    }
    val systemReduced = remember(context) { KcMotion.systemReducedMotion(context) }
    CompositionLocalProvider(
        LocalKeepClearColors provides if (darkTheme) KeepClearColors.Dark else KeepClearColors.Light,
        LocalKeepClearType provides KeepClearType.Default,
        LocalReducedMotion provides (reducedMotion ?: systemReduced),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = KeepClearTypography,
            shapes = KeepClearShapes,
            content = content,
        )
    }
}

/** KeepClear's additions to [MaterialTheme]: `KeepClearTheme.colors.safe.accent`, `KeepClearTheme.type.hero`, … */
object KeepClearTheme {
    val colors: KeepClearColors
        @Composable @ReadOnlyComposable
        get() = LocalKeepClearColors.current

    val type: KeepClearType
        @Composable @ReadOnlyComposable
        get() = LocalKeepClearType.current

    /** True when animations are off ("Remove animations"): components jump to their end state. */
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalReducedMotion.current
}
