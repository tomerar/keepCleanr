// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.common

/**
 * Bidi helpers (I18N-2). Every value that may run left-to-right inside Hebrew text (file names,
 * paths, sizes with units, counts) is wrapped in Unicode isolates so it can't reorder its neighbours.
 */
object Bidi {
    /** FIRST STRONG ISOLATE: direction taken from the first strong character of the value. */
    const val FSI = '⁨'

    /** LEFT-TO-RIGHT ISOLATE: for paths and other always-LTR values. */
    const val LRI = '⁦'

    /** POP DIRECTIONAL ISOLATE. */
    const val PDI = '⁩'

    private val controlCharacters = setOf(
        '‎',
        '‏',
        '؜', // LRM, RLM, ALM
        '‪',
        '‫',
        '‬',
        '‭',
        '‮', // embeddings and overrides
        '⁦',
        '⁧',
        '⁨',
        '⁩', // isolates
    )

    /** Wraps [value] in FSI…PDI after removing stray bidi controls, so the isolate is always balanced. */
    fun isolate(value: String): String = "$FSI${strip(value)}$PDI"

    /** Wraps [value] in LRI…PDI (paths, URIs). */
    fun isolateLtr(value: String): String = "$LRI${strip(value)}$PDI"

    /** Removes bidi control characters (also used before PROTECTED-path matching, SEC-6). */
    fun strip(value: String): String = value.filterNot { it in controlCharacters }
}
