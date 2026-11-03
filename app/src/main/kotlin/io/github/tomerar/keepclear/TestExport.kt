// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** PR H5: one file a test-mode run would have deleted: where, how big, and whether to the trash or for good. */
data class WouldDelete(val path: String, val bytes: Long, val forGood: Boolean)

/**
 * PR H5 (owner brief H5, "Test mode: export the would-delete list"): the list a test-mode clean produced, as a CSV
 * the user saves where they choose (Android's "create document" screen). Only offered after a test-mode run, only
 * when the user taps it, after a line that says the list has file names and folders (PRIVACY.md "Exports"). Nothing
 * is written anywhere else, and the app never reads it back.
 */
object TestExport {
    const val MIME = "text/csv"

    /** The columns, in English: the file is for the person testing (and a spreadsheet), not shown in the app. */
    const val HEADER = "path,size_bytes,would"

    fun rows(ops: List<PlannedOp>): List<WouldDelete> =
        ops.map { WouldDelete(it.displayPath, it.expectedSize, it.kind == OpKind.DELETE_FOR_GOOD) }

    /** Offered only in test mode, for a simulated run with something in it. */
    fun offered(result: CleanResult, testModeOn: Boolean): Boolean =
        testModeOn && result.simulated && result.wouldDelete.isNotEmpty()

    /** "keepclear-test-mode-2026-11-01.csv". */
    fun fileName(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "keepclear-test-mode-" + DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(now).atZone(zone)) +
            ".csv"

    /** The CSV text: [HEADER], then one line per file, newest list order kept; CRLF line ends (RFC 4180). */
    fun csv(rows: List<WouldDelete>): String = buildString {
        append(HEADER).append("\r\n")
        for (row in rows) {
            append(cell(row.path)).append(',')
            append(row.bytes).append(',')
            append(if (row.forGood) "delete_for_good" else "trash").append("\r\n")
        }
    }

    /**
     * One text cell. File names come from the phone and are untrusted (threat model): quotes are doubled, a cell
     * with a comma, quote or line break is quoted, line breaks become spaces, and a cell a spreadsheet would run as a
     * formula (`=`, `+`, `-`, `@`, tab, carriage return) starts with an apostrophe (OWASP "CSV injection").
     */
    fun cell(text: String): String {
        val flat = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        val safe = if (flat.isNotEmpty() && flat[0] in FORMULA_START) "'$flat" else flat
        val quote = safe.any { it == ',' || it == '"' } || safe != safe.trim()
        return if (quote) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')
}

/**
 * PR H5: how the result banner asks the activity to save the list (Android's "create document" screen); null when
 * nothing may be exported (release builds with test mode off, previews, tests that don't set one).
 */
class TestExporter(
    val testModeOn: () -> Boolean,
    /** The last export's outcome for the banner: true saved, false failed, null none yet. */
    val saved: MutableState<Boolean?> = mutableStateOf(null),
    val export: (List<WouldDelete>) -> Unit,
)

val LocalTestExporter = staticCompositionLocalOf<TestExporter?> { null }
