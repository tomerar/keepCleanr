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

    fun format(bytes: Long, locale: Locale): String {
        require(bytes >= 0) { "negative size" }
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
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
        val separator = if (locale.language == "iw" || locale.language == "he") "" else " "
        return Bidi.isolateLtr("$number$separator${units[unit]}")
    }
}
