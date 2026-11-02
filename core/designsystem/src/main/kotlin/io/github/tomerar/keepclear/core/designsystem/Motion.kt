// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion (design-system.md §6): subtle springs without bounce. Under "Remove animations"
 * (`ANIMATOR_DURATION_SCALE` = 0) [LocalReducedMotion] is true and every component jumps to its end state.
 */
object KcMotion {
    /** Layout changes (the storage strip sliding). */
    fun <T> layout(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Counting numbers (the hero number). */
    fun <T> count(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** True when the user turned animations off in Android's settings (read once when the theme starts). */
    fun systemReducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

val LocalReducedMotion = staticCompositionLocalOf { false }
