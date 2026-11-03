// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Owner brief H1 item 9 ("Get one photo back"): the Trash and Restore grids. Pure logic (grouping, titles, search),
 * so it is tested with tables; the screens only lay it out.
 */
enum class FileKind { IMAGE, VIDEO, AUDIO, DOCUMENT, OTHER }

object FileKinds {
    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "dng", "tif", "tiff")
    private val videos = setOf("mp4", "m4v", "mov", "3gp", "3g2", "mkv", "webm", "avi", "mts", "m2ts")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "awb", "mid", "midi")
    private val documents = setOf(
        "pdf",
        "txt",
        "doc",
        "docx",
        "xls",
        "xlsx",
        "ppt",
        "pptx",
        "odt",
        "ods",
        "odp",
        "rtf",
        "csv",
        "epub",
        "vcf",
    )

    fun of(name: String): FileKind = when (name.trimEnd('.', ' ').substringAfterLast('.', "").lowercase()) {
        in images -> FileKind.IMAGE
        in videos -> FileKind.VIDEO
        in audio -> FileKind.AUDIO
        in documents -> FileKind.DOCUMENT
        else -> FileKind.OTHER
    }

    /** Only photos and videos get a picture; everything else shows its type icon. */
    fun hasPicture(name: String): Boolean = of(name).let { it == FileKind.IMAGE || it == FileKind.VIDEO }
}

/** One month of a grid, newest month first; [items] newest first too. */
data class MonthSection<T>(val month: YearMonth, val items: List<T>)

object MediaGrid {
    /** Groups [items] by the month of [date] in [zone]; newest month first, newest item first, ties by [tie]. */
    fun <T> monthSections(
        items: List<T>,
        zone: ZoneId,
        tie: (T) -> String = { "" },
        date: (T) -> Long,
    ): List<MonthSection<T>> = items
        .sortedWith(compareByDescending<T> { date(it) }.thenBy { tie(it) })
        .groupBy { YearMonth.from(Instant.ofEpochMilli(date(it)).atZone(zone)) }
        .map { (month, list) -> MonthSection(month, list) }
        .sortedByDescending { it.month }

    /** "October 2026" / "אוקטובר 2026": the standalone month name and the year, no bidi marks inside. */
    fun monthTitle(month: YearMonth, locale: Locale): String =
        month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))

    /** The date under a tile ("12 Jul 2021"), in the user's language. */
    fun dayText(millis: Long, zone: ZoneId, locale: Locale): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}

/**
 * H1c search: local filtering, kept simple. Every word typed must match the file: its name contains it (any case),
 * or it names the file's date: a year ("2021"), a month in Hebrew or English, whole or its first 3+ letters
 * ("July", "jul", "יולי"), a month and year ("2021-07", "7/2021", "07.2021") or a day ("12/7/2021", "2021-07-12").
 * A day is read day-first and month-first, so both "12/7/2021" and "7/12/2021" find 12 July 2021.
 */
object GridSearch {
    private val languages = listOf(Locale.ENGLISH, Locale.forLanguageTag("he"))

    private val monthNames: Map<Month, List<String>> by lazy {
        Month.entries.associateWith { m ->
            languages.flatMap { locale ->
                listOf(TextStyle.FULL, TextStyle.FULL_STANDALONE, TextStyle.SHORT, TextStyle.SHORT_STANDALONE)
                    .map { m.getDisplayName(it, locale).lowercase(locale).trimEnd('.', '׳', '\'') }
            }.filter { it.isNotEmpty() }.distinct()
        }
    }

    private val yearMonth = Regex("""^(\d{4})[-/.](\d{1,2})$""")
    private val monthYear = Regex("""^(\d{1,2})[-/.](\d{4})$""")
    private val isoDay = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$""")
    private val day = Regex("""^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})$""")

    fun matches(name: String, dateMillis: Long, query: String, zone: ZoneId): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val date = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
        val lowerName = name.lowercase()
        return words.all { word -> word in lowerName || namesDate(word, date) }
    }

    private fun namesDate(word: String, date: LocalDate): Boolean {
        if (word.length == 4 && word.all { it.isDigit() }) return word.toInt() == date.year
        yearMonth.matchEntire(word)?.let { m -> return sameMonth(m.groupValues[1], m.groupValues[2], date) }
        monthYear.matchEntire(word)?.let { m -> return sameMonth(m.groupValues[2], m.groupValues[1], date) }
        isoDay.matchEntire(word)?.let { m ->
            return sameDay(m.groupValues[1], m.groupValues[2], m.groupValues[3], date)
        }
        day.matchEntire(word)?.let { m ->
            val (a, b, y) = m.destructured
            return sameDay(y, b, a, date) || sameDay(y, a, b, date)
        }
        // Hebrew often prefixes "in" (ב): "ביולי" names July too.
        val bare = word.trimEnd('.', '׳', '\'')
        val candidates = if (bare.startsWith('ב') && bare.length > 3) listOf(bare, bare.drop(1)) else listOf(bare)
        return candidates.any { w -> w.length >= 3 && monthNames.getValue(date.month).any { it.startsWith(w) } }
    }

    private fun sameMonth(year: String, month: String, date: LocalDate) =
        year.toInt() == date.year && month.toInt() == date.monthValue

    private fun sameDay(year: String, month: String, day: String, date: LocalDate) =
        sameMonth(year, month, date) && day.toInt() == date.dayOfMonth
}

/**
 * H1c partial restore: when the phone hasn't room for everything chosen, the newest files that fit are restored now
 * (decided conservatively, progress.md C136): newest first, a file that doesn't fit is left out and smaller older
 * ones may still come in. Files that need no room (already on the phone, skipped) are not counted.
 */
data class PartialPlan(val keys: Set<String>, val fits: Int, val of: Int, val bytes: Long)

object PartialRestore {
    fun plan(files: List<RestoreFile>, room: Long): PartialPlan {
        var left = room
        val chosen = files.sortedWith(compareByDescending<RestoreFile> { it.mtime }.thenBy { it.rel })
            .filter { f -> (f.size <= left).also { fits -> if (fits) left -= f.size } }
        return PartialPlan(chosen.map { it.key }.toSet(), chosen.size, files.size, chosen.sumOf { it.size })
    }
}
