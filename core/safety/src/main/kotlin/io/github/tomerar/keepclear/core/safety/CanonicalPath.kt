// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.safety

import io.github.tomerar.keepclear.core.common.Bidi
import java.text.Normalizer
import java.util.Locale

/**
 * Canonical form used for every PROTECTED / exclusion match (SEC-6): NFC, no zero-width or bidi
 * control characters, lowercase (Android emulated storage is case-insensitive), primary-storage
 * aliases folded to `/storage/emulated/0`, no dot or empty segments.
 *
 * Returns null for anything that can't be reduced to a clean absolute path; callers treat null as
 * "refuse" (fail safe).
 */
object CanonicalPath {
    private val zeroWidth = setOf('​', '‌', '‍', '⁠', '﻿', '­')

    private val aliases = listOf(
        "/sdcard" to "/storage/emulated/0",
        "/storage/self/primary" to "/storage/emulated/0",
        "/mnt/sdcard" to "/storage/emulated/0",
        "/mnt/user/0/emulated/0" to "/storage/emulated/0",
        "/data/media/0" to "/storage/emulated/0",
    )

    fun of(raw: String): String? {
        if (raw.isEmpty() || '\u0000' in raw) return null
        var s = Normalizer.normalize(raw, Normalizer.Form.NFC)
        s = Bidi.strip(s).filterNot { it in zeroWidth || it.isISOControl() }
        s = s.replace('\\', '/')
        if (!s.startsWith("/")) return null
        val segments = s.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) return null
        s = "/" + segments.joinToString("/")
        s = s.lowercase(Locale.ROOT)
        for ((alias, real) in aliases) {
            if (s == alias || s.startsWith("$alias/")) {
                s = real + s.removePrefix(alias)
                break
            }
        }
        return s
    }
}
