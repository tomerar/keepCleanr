// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.tomerar.keepclear.core.designsystem.KeepClearColors
import org.junit.Assert.assertEquals
import org.junit.Test

/** Review PR C (UX 8): a complete theme with readable text in light and dark. */
class ThemeTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance() + 0.05
        val l2 = b.luminance() + 0.05
        return (maxOf(l1, l2) / minOf(l1, l2))
    }

    private fun pairs(c: ColorScheme) = mapOf(
        "primary" to (c.onPrimary to c.primary),
        "primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
        "secondary" to (c.onSecondary to c.secondary),
        "secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
        "tertiary" to (c.onTertiary to c.tertiary),
        "tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
        "error" to (c.onError to c.error),
        "errorContainer" to (c.onErrorContainer to c.errorContainer),
        "surface" to (c.onSurface to c.surface),
        "surfaceVariant" to (c.onSurfaceVariant to c.surfaceVariant),
        "errorOnSurface" to (c.error to c.surface),
        "primaryOnSurface" to (c.primary to c.surface),
        "inverse" to (c.inverseOnSurface to c.inverseSurface),
    )

    @Test
    fun `every text colour reads on its background (WCAG AA 4,5 to 1) in light and dark`() {
        for ((name, scheme) in listOf("light" to KeepClearColors.Light, "dark" to KeepClearColors.Dark)) {
            val bad = pairs(scheme).filter { (_, p) -> contrast(p.first, p.second) < 4.5 }.keys
            assertEquals("$name: low contrast", emptySet<String>(), bad)
        }
    }

    @Test
    fun `no colour role is left at Material's default (no stray lavender)`() {
        fun roles(c: ColorScheme) = mapOf(
            "secondaryContainer" to c.secondaryContainer,
            "tertiary" to c.tertiary,
            "tertiaryContainer" to c.tertiaryContainer,
            "errorContainer" to c.errorContainer,
            "outline" to c.outline,
            "outlineVariant" to c.outlineVariant,
            "inverseSurface" to c.inverseSurface,
            "inversePrimary" to c.inversePrimary,
            "surfaceTint" to c.surfaceTint,
            "surfaceDim" to c.surfaceDim,
        )
        val lightDefault = roles(lightColorScheme())
        val darkDefault = roles(darkColorScheme())
        assertEquals(emptySet<String>(), roles(KeepClearColors.Light).filter { (k, v) -> lightDefault[k] == v }.keys)
        assertEquals(emptySet<String>(), roles(KeepClearColors.Dark).filter { (k, v) -> darkDefault[k] == v }.keys)
    }
}
