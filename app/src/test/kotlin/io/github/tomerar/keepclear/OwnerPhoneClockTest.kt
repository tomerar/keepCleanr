// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Design QA item 7: the screenshots' fixed clock comes after every date in the fixture, so no screen shows a file,
 * month or trash day that hasn't happened yet ("no future months").
 */
class OwnerPhoneClockTest {
    private val now = OwnerPhone.NOW
    private val today: LocalDate = java.time.Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()

    /** Every `yyyyMMdd` or `yyyy-MM-dd` date written in [text] that is a real calendar date. */
    private fun datesIn(text: String): List<LocalDate> {
        val compact = Regex("""(?<!\d)(20\d{2})(\d{2})(\d{2})(?!\d)""").findAll(text).map { it.groupValues }
        val dashed = Regex("""(?<!\d)(20\d{2})-(\d{2})-(\d{2})(?!\d)""").findAll(text).map { it.groupValues }
        return (compact + dashed).mapNotNull { (_, y, m, d) ->
            runCatching { LocalDate.parse("$y-$m-$d", DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
        }.toList()
    }

    @Test
    fun `no fixture file, name or trash entry is newer than the clock`() {
        val newer = OwnerPhone.items.filter { it.item.mtimeMillis > now }.map { it.item.path }
        assertEquals(emptyList<String>(), newer)
        val futureNames = OwnerPhone.items.map { it.item.path }.filter { path -> datesIn(path).any { it > today } }
        assertEquals(emptyList<String>(), futureNames)
        val trash = OwnerPhone.trash.entries
        assertEquals(emptyList<String>(), trash.filter { it.deletedAt > now || it.mtimeMillis > now }.map { it.id })
        val futureTrash = trash.filter { e ->
            datesIn(e.originalPath).any { it > today } || datesIn(e.trashedPath).any { it > today }
        }
        assertEquals(emptyList<String>(), futureTrash.map { it.id })
    }

    @Test
    fun `no month bucket is after the clock's month`() {
        val month = today.withDayOfMonth(1)
        val buckets = OwnerPhone.items.mapNotNull { it.group.bucket }.filter { it.length == 7 }
        assertTrue(buckets.isNotEmpty())
        val future = buckets.filter { LocalDate.parse("$it-01") > month }.distinct()
        assertEquals(emptyList<String>(), future)
    }

    @Test
    fun `the clock is not in the future of the review either`() {
        // 2026-11-01 12:00 UTC: the owner's review week, so the screenshots show this autumn, not next year.
        assertEquals(LocalDate.of(2026, 11, 1), today)
    }
}
