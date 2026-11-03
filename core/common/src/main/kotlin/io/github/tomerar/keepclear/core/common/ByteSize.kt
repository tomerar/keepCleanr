// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.common

import java.text.NumberFormat
import java.util.Locale

/**
 * Human-readable sizes (I18N-3): decimal units like Android Settings (1 GB = 10^9 bytes), one decimal
 * from 1 GB up, locale digits. Hebrew writes the unit in Latin without a space ("16.8GB",
 * ux-flows §0); the result is bidi-isolated so it never reorders surrounding Hebrew text.
 */
object ByteSize {
    private val units = listOf("B", "KB", "MB", "GB", "TB")

    /** E13: never throws; a negative size (corrupt or hostile data) shows as 0 B. */
    fun format(bytes: Long, locale: Locale): String {
        val unit = unitOf(bytes)
        val value = scaled(bytes, unit)
        val separator = if (locale.language == "iw" || locale.language == "he") "" else " "
        return Bidi.isolateLtr("${number(value, unit, locale)}$separator${units[unit]}")
    }

    /**
     * Owner brief H1 item 4: "12.9 of 31.0GB": [done] as a bare number in the unit of [total], and [total] as usual
     * ([format]). Both bidi-isolated. [done] is clamped to 0…[total].
     */
    fun progress(done: Long, total: Long, locale: Locale): Pair<String, String> {
        val unit = unitOf(total)
        val value = scaled(done.coerceIn(0, total.coerceAtLeast(0)), unit)
        return Bidi.isolateLtr(number(value, unit, locale)) to format(total, locale)
    }

    private fun unitOf(bytes: Long): Int {
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        return unit
    }

    /** [bytes] in [unit], divided step by step exactly as [unitOf] does (same rounding as before). */
    private fun scaled(bytes: Long, unit: Int): Double {
        var value = bytes.coerceAtLeast(0).toDouble()
        repeat(unit) { value /= 1000 }
        return value
    }

    private fun number(value: Double, unit: Int, locale: Locale): String {
        val decimals = when {
            unit == 0 -> 0
            unit >= 3 -> 1
            value < 10 -> 1
            else -> 0
        }
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }.format(value)
        return number
    }
}
