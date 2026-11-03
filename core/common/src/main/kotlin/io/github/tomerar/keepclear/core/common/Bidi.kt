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

    /** RIGHT-TO-LEFT MARK. */
    const val RLM = '\u200F'

    /**
     * Wraps [value] in FSI…PDI after removing stray bidi controls, so the isolate is always balanced.
     *
     * Design QA item 8: a right-to-left value that **starts with digits**, such as the Hebrew count "4,983 קבצים", is
     * wrapped in RLM…RLM instead. Android 11's text layout draws such an isolate wrong when left-to-right text comes
     * before it on the line („7.9GB · 4,983 קבצים” showed as "קבצים4,983"; reproduced with Robolectric's API 30 text
     * stack, `BidiLayoutTest`; API 36 is right). In a right-to-left line the marks keep the number with its word just
     * like the isolate, and they are strong, so a line that starts with the count still reads right to left.
     */
    fun isolate(value: String): String {
        val clean = strip(value)
        val first = clean.indexOfFirst { Character.getDirectionality(it) in STRONG }
        val rtlStartingWithDigits = first > 0 && Character.getDirectionality(clean[first]) in RTL
        return if (rtlStartingWithDigits) "$RLM$clean$RLM" else "$FSI$clean$PDI"
    }

    private val STRONG = setOf(
        Character.DIRECTIONALITY_LEFT_TO_RIGHT,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
    )

    /** Wraps [value] in LRI…PDI (paths, URIs). */
    fun isolateLtr(value: String): String = "$LRI${strip(value)}$PDI"

    /**
     * A file name inside Hebrew text (review PR C): the trailing left-to-right token that holds the extension,
     * e.g. `2023.png`, is kept together in its own LTR isolate, so "צילום מסך 2023.png" never shows as
     * "צילום מסך png.2023".
     */
    fun fileName(name: String): String {
        val clean = strip(name)
        val dot = clean.lastIndexOf('.')
        if (dot <= 0 || dot == clean.lastIndex) return isolate(clean)
        val start = clean.lastIndexOf(' ', dot) + 1
        val tail = clean.substring(start)
        if (tail.any { Character.getDirectionality(it) in RTL }) return isolate(clean)
        return "$FSI${clean.substring(0, start)}$LRI$tail$PDI$PDI"
    }

    private val RTL = setOf(
        Character.DIRECTIONALITY_RIGHT_TO_LEFT,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
    )

    /** No-break space: keeps the words of a short name on one line. */
    const val NBSP = '\u00A0'

    /** Names up to this long are kept on one line by [name]; longer ones may still wrap between words. */
    const val KEEP_TOGETHER_MAX = 24

    /**
     * Owner brief H2 item 12: a short device or drive name („SanDisk USB”) as one unit inside a sentence: isolated
     * like [isolate], and its spaces made non-breaking so it never splits over two lines ("SanDisk / USB").
     */
    fun name(value: String): String {
        val clean = strip(value).trim()
        return isolate(if (clean.length <= KEEP_TOGETHER_MAX) clean.replace(' ', NBSP) else clean)
    }

    /** Removes bidi control characters (also used before PROTECTED-path matching, SEC-6). */
    fun strip(value: String): String = value.filterNot { it in controlCharacters }
}
