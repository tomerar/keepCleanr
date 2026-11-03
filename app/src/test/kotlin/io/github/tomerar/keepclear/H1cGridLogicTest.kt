// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.engine.move.Manifest
import io.github.tomerar.keepclear.engine.restore.BackupEntry
import io.github.tomerar.keepclear.engine.restore.Where
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** Owner brief H1 item 9 (PR H1c): month sections, search and partial restore, as tables. */
class H1cGridLogicTest {
    private val israel = ZoneId.of("Asia/Jerusalem")
    private val utc = ZoneId.of("UTC")

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private data class F(val name: String, val date: Long)

    @Test
    fun `files are grouped by month, newest month and newest file first, across year boundaries`() {
        // name, time, zone it is grouped in, expected month
        val table = listOf(
            Triple("IMG_new_year.jpg", at(israel, 2026, 1, 1, 0, 30), israel) to YearMonth.of(2026, 1),
            // 00:30 on 1 January in Israel is still 31 December in UTC: the user's own clock decides.
            Triple("IMG_new_year.jpg", at(israel, 2026, 1, 1, 0, 30), utc) to YearMonth.of(2025, 12),
            Triple("IMG_eve.jpg", at(israel, 2025, 12, 31, 23, 59), israel) to YearMonth.of(2025, 12),
            Triple("תמונה של סבתא.jpg", at(israel, 2025, 12, 1, 0, 1), israel) to YearMonth.of(2025, 12),
            Triple("VID_feb.mp4", at(israel, 2024, 2, 29), israel) to YearMonth.of(2024, 2),
        )
        for ((input, expected) in table) {
            val (name, time, zone) = input
            val sections = MediaGrid.monthSections(listOf(F(name, time)), zone) { it.date }
            assertEquals("$name in $zone", expected, sections.single().month)
        }

        val files = listOf(
            F("a.jpg", at(israel, 2025, 12, 31, 23, 59)),
            F("b.jpg", at(israel, 2026, 1, 1, 0, 30)),
            F("ג.jpg", at(israel, 2025, 12, 2)),
            F("d.jpg", at(israel, 2024, 12, 31)),
            F("e.jpg", at(israel, 2026, 1, 15)),
        )
        val sections = MediaGrid.monthSections(files, israel) { it.date }
        assertEquals(
            listOf(YearMonth.of(2026, 1), YearMonth.of(2025, 12), YearMonth.of(2024, 12)),
            sections.map { it.month },
        )
        assertEquals(listOf("e.jpg", "b.jpg"), sections[0].items.map { it.name })
        assertEquals(listOf("a.jpg", "ג.jpg"), sections[1].items.map { it.name })
        assertEquals(emptyList<MonthSection<F>>(), MediaGrid.monthSections(emptyList<F>(), israel) { it.date })
    }

    @Test
    fun `month titles are the month's name and the year in the user's language, with no bidi marks`() {
        val he = Locale.forLanguageTag("he")
        assertEquals("October 2026", MediaGrid.monthTitle(YearMonth.of(2026, 10), Locale.ENGLISH))
        assertEquals("אוקטובר 2026", MediaGrid.monthTitle(YearMonth.of(2026, 10), he))
        assertEquals("דצמבר 2025", MediaGrid.monthTitle(YearMonth.of(2025, 12), he))
        assertEquals("ינואר 2026", MediaGrid.monthTitle(YearMonth.of(2026, 1), Locale.forLanguageTag("iw")))
        // RTL: the title is plain text (the header lays it out); no isolation marks that could split a word.
        val title = MediaGrid.monthTitle(YearMonth.of(2026, 10), he)
        assert(title.none { it in "⁦⁧⁨⁩‎‏" }) { title }
    }

    @Test
    fun `search matches names and dates typed in Hebrew or English`() {
        val july12 = at(israel, 2021, 7, 12)
        // query, name, expected
        val table = listOf(
            Triple("", "IMG_1.jpg", true),
            Triple("   ", "IMG_1.jpg", true),
            Triple("img", "IMG_20210712.jpg", true),
            Triple("IMG_2021", "img_20210712.JPG", true),
            Triple("סבתא", "תמונה של סבתא.jpg", true),
            Triple("סבא", "תמונה של סבתא.jpg", false),
            Triple("beach", "IMG_1.jpg", false),
            Triple("2021", "IMG_1.jpg", true),
            Triple("2020", "IMG_1.jpg", false),
            Triple("july", "IMG_1.jpg", true),
            Triple("July 2021", "IMG_1.jpg", true),
            Triple("jul", "IMG_1.jpg", true),
            Triple("ju", "IMG_1.jpg", false),
            Triple("june", "IMG_1.jpg", false),
            Triple("יולי", "IMG_1.jpg", true),
            Triple("ביולי", "IMG_1.jpg", true),
            Triple("יולי 2021", "IMG_1.jpg", true),
            Triple("יולי 2022", "IMG_1.jpg", false),
            Triple("אוגוסט", "IMG_1.jpg", false),
            Triple("2021-07", "IMG_1.jpg", true),
            Triple("7/2021", "IMG_1.jpg", true),
            Triple("07.2021", "IMG_1.jpg", true),
            Triple("8/2021", "IMG_1.jpg", false),
            Triple("12/7/2021", "IMG_1.jpg", true),
            Triple("7/12/2021", "IMG_1.jpg", true),
            Triple("2021-07-12", "IMG_1.jpg", true),
            Triple("13/7/2021", "IMG_1.jpg", false),
            Triple("img july", "IMG_1.jpg", true),
            Triple("img august", "IMG_1.jpg", false),
        )
        for ((query, name, expected) in table) {
            assertEquals("\"$query\" on $name", expected, GridSearch.matches(name, july12, query, israel))
        }
    }

    private fun file(rel: String, size: Long, mtime: Long, where: Where = Where.RESTORE_FOLDER) =
        RestoreFile(rel, rel.substringBefore('/'), "2021-07", mtime, size, where)

    @Test
    fun `when the phone is short of room, the newest files that fit come back (C136)`() {
        val files = listOf(
            file("Camera/old.jpg", 100, mtime = 1),
            file("Camera/mid.jpg", 300, mtime = 2),
            file("Camera/new.jpg", 200, mtime = 3),
            file("Camera/newest.mp4", 900, mtime = 4),
        )
        // room, expected keys, fits
        val table = listOf(
            1_500L to listOf("old.jpg", "mid.jpg", "new.jpg", "newest.mp4"),
            1_000L to listOf("newest.mp4", "old.jpg"),
            600L to listOf("new.jpg", "mid.jpg", "old.jpg"),
            250L to listOf("new.jpg"),
            99L to emptyList(),
            0L to emptyList(),
        )
        for ((room, names) in table) {
            val plan = PartialRestore.plan(files, room)
            assertEquals("room $room", names.map { RestoreFile.key("Camera/$it") }.toSet(), plan.keys)
            assertEquals(names.size, plan.fits)
            assertEquals(4, plan.of)
            assert(plan.bytes <= room)
        }
    }

    private val groups = listOf(
        RestoreGroup("Camera", "2021-07", 600, toOriginal = 0, toRestoreFolder = 3),
        RestoreGroup("WhatsApp", "2021-08", 50, toOriginal = 0, toRestoreFolder = 1),
    )
    private val files = listOf(
        file("Camera/a.jpg", 100, 1),
        file("Camera/b.jpg", 200, 2),
        file("Camera/c.jpg", 300, 3),
        RestoreFile("WhatsApp/w.jpg", "WhatsApp", "2021-08", 4, 50, Where.RESTORE_FOLDER),
    )

    @Test
    fun `one file can be chosen, and leaving a chosen month keeps the rest of it`() {
        var choice = RestoreChoice(groups, files, emptySet())
        choice = RestoreChoice(groups, files, choice.toggleFile(files[1]))
        assertEquals(setOf("/Camera/b.jpg"), choice.keys)
        assertEquals(1, choice.count)
        assertEquals(200L, choice.bytes)
        choice = RestoreChoice(groups, files, choice.toggleMonth(groups[0], on = true))
        assertEquals(setOf("Camera|2021-07"), choice.keys)
        assertEquals(600L, choice.bytes)
        choice = RestoreChoice(groups, files, choice.toggleFile(files[0]))
        assertEquals(setOf("/Camera/b.jpg", "/Camera/c.jpg"), choice.keys)
        assert(!choice.monthChecked(groups[0]))
        choice = RestoreChoice(groups, files, choice.toggleFile(files[0]))
        assert(choice.monthChecked(groups[0]))
        assertNull(choice.partial(room = 600))
        assertEquals(setOf("/Camera/c.jpg"), choice.partial(room = 350)!!.keys)
    }

    @Test
    fun `a selection names exactly the chosen backup files (single-item restore)`() {
        val line = { rel: String, mtime: Long ->
            Manifest.Line(rel, "/storage/emulated/0/DCIM/$rel", 10, mtime, "a".repeat(64), 0, false)
        }
        val july = at(utc, 2021, 7, 10)
        val entries = listOf(
            BackupEntry("KeepClear/P", line("Camera/a.jpg", july)),
            BackupEntry("KeepClear/P", line("Camera/b.jpg", july)),
            BackupEntry("KeepClear/P", line("Camera/c.jpg", at(utc, 2021, 8, 1))),
        )
        assertEquals(listOf(entries[1]), selectedEntries(entries, setOf(RestoreFile.key("Camera/b.jpg"))))
        assertEquals(entries.take(2), selectedEntries(entries, setOf("Camera|2021-07")))
        assertEquals(
            entries,
            selectedEntries(entries, setOf("Camera|2021-07", RestoreFile.key("Camera/c.jpg"))),
        )
        // A file key never matches a month and a month key never matches a file.
        assertEquals(emptyList<BackupEntry>(), selectedEntries(entries, setOf("Camera/b.jpg", "/Camera|2021-07")))
    }
}
