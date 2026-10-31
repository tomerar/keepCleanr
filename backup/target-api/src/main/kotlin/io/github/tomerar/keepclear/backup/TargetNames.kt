// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Name rules for backup targets (E-MOVE-11/12/13): NFC, characters FAT/exFAT/NTFS/SMB reject,
 * Windows reserved names, trailing dots/spaces, 255-byte components.
 */
object TargetNames {
    private val illegal = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
    private val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    const val MAX_COMPONENT_BYTES = 255

    /** A legal component for every supported target; may change the name. */
    fun sanitize(name: String): String {
        var n = Normalizer.normalize(name, Normalizer.Form.NFC)
        n = n.map { c -> if (c in illegal || c.code < 32) '_' else c }.joinToString("")
        n = n.trimEnd('.', ' ')
        if (n.isEmpty()) n = "_"
        val stem = n.substringBefore('.')
        if (stem.uppercase() in reserved) n = "_$n"
        return fitBytes(n, MAX_COMPONENT_BYTES)
    }

    /** Shortens the stem (keeping the extension) and adds a short hash so names stay unique. */
    fun fitBytes(name: String, maxBytes: Int): String {
        if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 16 }
        val ext = dot?.let { name.substring(it) } ?: ""
        var stem = dot?.let { name.substring(0, it) } ?: name
        val tag =
            "~" +
                MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).take(3).joinToString("") {
                    "%02x".format(it)
                }
        while ((stem + tag + ext).toByteArray(Charsets.UTF_8).size > maxBytes && stem.isNotEmpty()) {
            stem = stem.dropLast(1)
        }
        return stem + tag + ext
    }

    /** Comparison key for collisions on case-insensitive media. */
    fun key(rel: String, caseSensitive: Boolean): String {
        val nfc = Normalizer.normalize(rel, Normalizer.Form.NFC)
        return if (caseSensitive) nfc else nfc.lowercase()
    }

    /**
     * "name (2).ext" for the [n]-th collision. The stem is shortened so the result stays within [maxBytes]
     * UTF-8 bytes and [maxChars] characters (a long name plus its suffix must still be a legal component).
     */
    fun withSuffix(name: String, n: Int, maxBytes: Int = MAX_COMPONENT_BYTES, maxChars: Int = Int.MAX_VALUE): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        var stem = if (dot == null) name else name.substring(0, dot)
        val ext = if (dot == null) "" else name.substring(dot)
        val suffix = " ($n)"

        fun fits(candidate: String) =
            candidate.toByteArray(Charsets.UTF_8).size <= maxBytes && candidate.length <= maxChars
        while (!fits(stem + suffix + ext) && stem.isNotEmpty()) stem = stem.dropLast(1)
        return stem + suffix + ext
    }
}
