// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Stream helpers that work on every supported Android version. `InputStream.readNBytes(int)` exists only
 * from API 33 and `skipNBytes` only from API 34 (minSdk is 30); pure-JVM modules aren't checked by Android
 * Lint, so a Konsist rule forbids those calls and this file replaces them.
 */
object Streams {
    /** Reads up to [max] bytes; fewer only at end of stream. */
    fun InputStream.readUpTo(max: Int): ByteArray {
        require(max >= 0)
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buffer = ByteArray(minOf(max, 64 * 1024).coerceAtLeast(1))
        var left = max
        while (left > 0) {
            val n = read(buffer, 0, minOf(buffer.size, left))
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    /** Skips exactly [count] bytes or throws [EOFException]. */
    fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) {
                left -= skipped
            } else if (read() < 0) {
                throw EOFException("stream ended with $left bytes left to skip")
            } else {
                left--
            }
        }
    }
}
