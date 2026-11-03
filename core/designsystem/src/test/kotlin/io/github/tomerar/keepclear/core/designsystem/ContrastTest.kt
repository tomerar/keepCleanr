// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonDefaults
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

/**
 * UI-1 / ADR 0013: text ≥ 4.5:1 and large numbers, icons and outlines ≥ 3:1 against their backgrounds, in light and
 * dark (design-system.md §2.4). Pure JVM: WCAG 2.x relative luminance computed here, not by Compose.
 */
class ContrastTest {
    private fun channel(c: Float): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a) + 0.05
        val lb = luminance(b) + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    private val themes = listOf(
        Triple("light", KeepClearSchemes.Light, KeepClearColors.Light),
        Triple("dark", KeepClearSchemes.Dark, KeepClearColors.Dark),
    )

    private fun textPairs(c: ColorScheme, k: KeepClearColors): Map<String, Pair<Color, Color>> {
        val pairs = mutableMapOf(
            "onSurface/surface" to (c.onSurface to c.surface),
            "onSurface/background" to (c.onSurface to c.background),
            "onBackground/background" to (c.onBackground to c.background),
            "onSurfaceVariant/surface" to (c.onSurfaceVariant to c.surface),
            "onSurfaceVariant/background" to (c.onSurfaceVariant to c.background),
            "onSurfaceVariant/surfaceVariant" to (c.onSurfaceVariant to c.surfaceVariant),
            "onPrimary/primary" to (c.onPrimary to c.primary),
            "onPrimaryContainer/primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
            "primary/surface" to (c.primary to c.surface),
            "primary/background" to (c.primary to c.background),
            "primary/primaryContainer" to (c.primary to c.primaryContainer),
            "onSecondary/secondary" to (c.onSecondary to c.secondary),
            "onSecondaryContainer/secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
            "onTertiary/tertiary" to (c.onTertiary to c.tertiary),
            "onTertiaryContainer/tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
            "onError/error" to (c.onError to c.error),
            "onErrorContainer/errorContainer" to (c.onErrorContainer to c.errorContainer),
            "error/surface" to (c.error to c.surface),
            "error/background" to (c.error to c.background),
            "error/surfaceContainerLow" to (c.error to c.surfaceContainerLow),
            "inverseOnSurface/inverseSurface" to (c.inverseOnSurface to c.inverseSurface),
        )
        // Every surface tier the components draw text on.
        val tiers = mapOf(
            "surfaceContainerLowest" to c.surfaceContainerLowest,
            "surfaceContainerLow" to c.surfaceContainerLow,
            "surfaceContainer" to c.surfaceContainer,
            "surfaceContainerHigh" to c.surfaceContainerHigh,
            "surfaceContainerHighest" to c.surfaceContainerHighest,
            "surfaceBright" to c.surfaceBright,
            "surfaceDim" to c.surfaceDim,
        )
        tiers.forEach { (name, bg) ->
            pairs["onSurface/$name"] = c.onSurface to bg
            pairs["onSurfaceVariant/$name"] = c.onSurfaceVariant to bg
            pairs["primary/$name"] = c.primary to bg
        }
        for ((lane, colors) in mapOf("safe" to k.safe, "review" to k.review, "move" to k.move)) {
            pairs["$lane/container"] = colors.accent to colors.container
            pairs["$lane.onContainer/container"] = colors.onContainer to colors.container
            pairs["onSurface/$lane.container"] = c.onSurface to colors.container
            pairs["$lane/surface"] = colors.accent to c.surface
            pairs["$lane/background"] = colors.accent to c.background
            pairs["$lane/surfaceContainer"] = colors.accent to c.surfaceContainer
        }
        return pairs
    }

    /** Large numbers, icons, outlines and strip segments (WCAG 1.4.11 non-text, 1.4.3 large text). */
    private fun graphicPairs(c: ColorScheme, k: KeepClearColors) = mapOf(
        "outline/surface" to (c.outline to c.surface),
        "outline/background" to (c.outline to c.background),
        "outline/surfaceContainer" to (c.outline to c.surfaceContainer),
        "stripUsed/surface" to (k.stripUsed to c.surface),
        "stripUsed/background" to (k.stripUsed to c.background),
        "safe/surface" to (k.safe.accent to c.surface),
        "review/surface" to (k.review.accent to c.surface),
        "move/surface" to (k.move.accent to c.surface),
        "primary/surface (stamp, icons)" to (c.primary to c.surface),
        // Design QA: KcButtonStyle.OutlinedDestructive's red outline on the screens it sits on.
        "error outline/background" to (c.error to c.background),
        "error outline/surface" to (c.error to c.surface),
    )

    /** [fg] at [alpha] over the opaque [bg] (what the confirm button's track looks like). */
    private fun over(fg: Color, alpha: Float, bg: Color) = Color(
        red = fg.red * alpha + bg.red * (1 - alpha),
        green = fg.green * alpha + bg.green * (1 - alpha),
        blue = fg.blue * alpha + bg.blue * (1 - alpha),
    )

    @Test
    fun `the confirm button's label reads on its track while it fills, in light and dark`() {
        // Design QA item 5 (app SafeConfirmButton): the label is the button's colour on a 16 % track of that colour
        // over the sheet, and the button's text colour on the fill.
        for ((name, c, _) in themes) {
            val pairs = mapOf(
                "primary/track" to
                    (c.primary to over(c.primary, KcButtonDefaults.ProgressTrackAlpha, c.surfaceContainerLow)),
                "error/track" to (c.error to over(c.error, KcButtonDefaults.ProgressTrackAlpha, c.surfaceContainerLow)),
                "onPrimary/primary" to (c.onPrimary to c.primary),
                "onError/error" to (c.onError to c.error),
            )
            val bad = pairs.filterValues { (fg, bg) -> ratio(fg, bg) < 4.5 }
                .mapValues { (_, p) -> "%.2f".format(ratio(p.first, p.second)) }
            assertEquals("$name: confirm label below 4.5:1", emptyMap<String, String>(), bad)
        }
    }

    @Test
    fun `text roles reach 4,5 to 1 on their backgrounds in light and dark`() {
        for ((name, scheme, lanes) in themes) {
            val bad = textPairs(scheme, lanes).filterValues { (fg, bg) -> ratio(fg, bg) < 4.5 }
                .mapValues { (_, p) -> "%.2f".format(ratio(p.first, p.second)) }
            assertEquals("$name: text below 4.5:1", emptyMap<String, String>(), bad)
        }
    }

    @Test
    fun `large numbers, icons and outlines reach 3 to 1 in light and dark`() {
        for ((name, scheme, lanes) in themes) {
            val bad = graphicPairs(scheme, lanes).filterValues { (fg, bg) -> ratio(fg, bg) < 3.0 }
                .mapValues { (_, p) -> "%.2f".format(ratio(p.first, p.second)) }
            assertEquals("$name: non-text below 3:1", emptyMap<String, String>(), bad)
        }
    }

    /** Owner brief H4 item 6: every contrast level, light and dark. */
    private val levels = listOf(KcContrast.MEDIUM, KcContrast.HIGH).flatMap { level ->
        listOf(false, true).map { dark ->
            Triple(
                "${level.name.lowercase()} ${if (dark) "dark" else "light"}",
                KeepClearContrastSchemes.of(dark, level),
                if (dark) KeepClearColors.Dark else KeepClearColors.Light,
            ) to (if (dark) KeepClearSchemes.Dark else KeepClearSchemes.Light)
        }
    }

    @Test
    fun `medium and high contrast pass every pair and never make one weaker than standard (H4 item 6)`() {
        for ((theme, standard) in levels) {
            val (name, scheme, lanes) = theme
            val text = textPairs(scheme, lanes)
            val graphic = graphicPairs(scheme, lanes)
            assertEquals(
                "$name: text below 4.5:1",
                emptyMap<String, String>(),
                text.filterValues { (fg, bg) ->
                    ratio(fg, bg) < 4.5
                }.mapValues { (_, p) -> "%.2f".format(ratio(p.first, p.second)) },
            )
            assertEquals(
                "$name: non-text below 3:1",
                emptyMap<String, String>(),
                graphic.filterValues { (fg, bg) ->
                    ratio(fg, bg) < 3.0
                }.mapValues { (_, p) -> "%.2f".format(ratio(p.first, p.second)) },
            )
            val before = textPairs(standard, lanes) + graphicPairs(standard, lanes)
            val weaker = (text + graphic).filter { (key, p) ->
                val old = before.getValue(key)
                ratio(p.first, p.second) < ratio(old.first, old.second) - 0.001
            }.keys
            assertEquals("$name: a pair got weaker than the standard scheme", emptySet<String>(), weaker)
        }
    }

    @Test
    fun `high contrast reaches 7 to 1 for body text and secondary text (H4 item 6)`() {
        for ((theme, _) in levels.filter { it.first.first.startsWith("high") }) {
            val (name, c, _) = theme
            for ((label, pair) in mapOf(
                "onSurface/surface" to (c.onSurface to c.surface),
                "onSurfaceVariant/surface" to (c.onSurfaceVariant to c.surface),
                "onSurfaceVariant/background" to (c.onSurfaceVariant to c.background),
                "primary/background" to (c.primary to c.background),
            )) {
                assert(ratio(pair.first, pair.second) >= 7.0) {
                    "$name $label: %.2f".format(ratio(pair.first, pair.second))
                }
            }
            assert(ratio(c.outline, c.surface) >= 4.5) { "$name outline/surface" }
        }
    }

    @Test
    fun `Android's contrast value picks the level`() {
        assertEquals(KcContrast.STANDARD, KcContrast.of(0f))
        assertEquals(KcContrast.STANDARD, KcContrast.of(-1f))
        assertEquals(KcContrast.MEDIUM, KcContrast.of(0.5f))
        assertEquals(KcContrast.HIGH, KcContrast.of(1f))
    }

    @Test
    fun `the contrast formula matches WCAG reference values`() {
        assertEquals(21.0, ratio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, ratio(Color(0xFF777777), Color(0xFF777777)), 0.001)
        // #767676 on white is the classic 4.54:1 boundary grey.
        assertEquals(4.54, ratio(Color(0xFF767676), Color.White), 0.01)
    }

    @Test
    fun `brief tokens are used exactly`() {
        val l = KeepClearSchemes.Light
        val d = KeepClearSchemes.Dark
        val kl = KeepClearColors.Light
        val kd = KeepClearColors.Dark
        val tokens = listOf(
            Token("background", l.background, 0xFFF6F2EA, d.background, 0xFF161513),
            Token("surface", l.surface, 0xFFFFFDF8, d.surface, 0xFF1E1C1A),
            Token("surfaceContainer", l.surfaceContainer, 0xFFFBF8F1, d.surfaceContainer, 0xFF24221F),
            Token("onSurface", l.onSurface, 0xFF1E1C19, d.onSurface, 0xFFECE6DC),
            Token("onSurfaceVariant", l.onSurfaceVariant, 0xFF5A554D, d.onSurfaceVariant, 0xFFBDB5A8),
            Token("outline", l.outline, 0xFF8A8378, d.outline, 0xFF8F887D),
            Token("outlineVariant", l.outlineVariant, 0xFFDDD6CA, d.outlineVariant, 0xFF3A3732),
            Token("primary", l.primary, 0xFF2F4B7C, d.primary, 0xFFA9C1EE),
            Token("onPrimary", l.onPrimary, 0xFFFFFFFF, d.onPrimary, 0xFF10284F),
            Token("primaryContainer", l.primaryContainer, 0xFFDCE4F5, d.primaryContainer, 0xFF2A3F66),
            Token("error", l.error, 0xFFB3261E, d.error, 0xFFF2B8B5),
            Token("safe", kl.safe.accent, 0xFF3D6B4F, kd.safe.accent, 0xFF9DCDAE),
            Token("safeContainer", kl.safe.container, 0xFFDCEBDF, kd.safe.container, 0xFF24392C),
            Token("move", kl.move.accent, 0xFF8A5A12, kd.move.accent, 0xFFE7B970),
            Token("moveContainer", kl.move.container, 0xFFF5E4C6, kd.move.container, 0xFF3D2E14),
            Token("review", kl.review.accent, 0xFF4D5D73, kd.review.accent, 0xFFB7C4D8),
            Token("reviewContainer", kl.review.container, 0xFFE1E6EE, kd.review.container, 0xFF2C3440),
        )
        val wrong = tokens.filter { it.light != Color(it.lightBrief) || it.dark != Color(it.darkBrief) }.map { it.name }
        assertEquals(emptyList<String>(), wrong)
    }

    private class Token(val name: String, val light: Color, val lightBrief: Long, val dark: Color, val darkBrief: Long)

    @Test
    fun `no colour role is left at Material's default (no stray lavender)`() {
        fun roles(c: ColorScheme) = mapOf(
            "primary" to c.primary,
            "primaryContainer" to c.primaryContainer,
            "onPrimaryContainer" to c.onPrimaryContainer,
            "inversePrimary" to c.inversePrimary,
            "secondary" to c.secondary,
            "secondaryContainer" to c.secondaryContainer,
            "onSecondaryContainer" to c.onSecondaryContainer,
            "tertiary" to c.tertiary,
            "tertiaryContainer" to c.tertiaryContainer,
            "onTertiaryContainer" to c.onTertiaryContainer,
            "background" to c.background,
            "onBackground" to c.onBackground,
            "surface" to c.surface,
            "onSurface" to c.onSurface,
            "surfaceVariant" to c.surfaceVariant,
            "onSurfaceVariant" to c.onSurfaceVariant,
            "surfaceTint" to c.surfaceTint,
            "inverseSurface" to c.inverseSurface,
            "inverseOnSurface" to c.inverseOnSurface,
            "outline" to c.outline,
            "outlineVariant" to c.outlineVariant,
            "surfaceBright" to c.surfaceBright,
            "surfaceContainer" to c.surfaceContainer,
            "surfaceContainerHigh" to c.surfaceContainerHigh,
            "surfaceContainerHighest" to c.surfaceContainerHighest,
            "surfaceContainerLow" to c.surfaceContainerLow,
            "surfaceDim" to c.surfaceDim,
        )
        // Not listed: white "on" colours, scrim and the error family, which may equal Material's baseline (the brief's
        // error is the baseline's #B3261E; its containers are the baseline's error tones, not lavender).
        val lightDefault = roles(lightColorScheme())
        val darkDefault = roles(darkColorScheme())
        assertEquals(emptySet<String>(), roles(KeepClearSchemes.Light).filter { (k, v) -> lightDefault[k] == v }.keys)
        assertEquals(emptySet<String>(), roles(KeepClearSchemes.Dark).filter { (k, v) -> darkDefault[k] == v }.keys)
    }
}
