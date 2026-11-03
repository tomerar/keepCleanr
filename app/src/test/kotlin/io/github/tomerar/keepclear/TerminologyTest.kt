// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Owner brief H2 item 9 (PR H2c): one term per concept, in every string (English and Hebrew) and in the store
 * listing. Table-driven: each row is a forbidden variant and the term to use instead; [allowed] names the few
 * strings where the variant is the right word, with the reason. The table is also in CLAUDE.md (Hebrew voice rules).
 */
class TerminologyTest {
    private class Row(val forbidden: Regex, val preferred: String)

    private fun row(forbidden: String, preferred: String) = Row(Regex(forbidden), preferred)

    private val hebrew = listOf(
        // thumbnails = תמונות ממוזערות (one term)
        row("תמונות תצוגה מקדימה", "תמונות ממוזערות"),
        row("תמונות קטנות", "תמונות ממוזערות"),
        // USB drive on the default path = דיסק און קי
        row("כונן USB", "דיסק און קי"),
        row("החסן נייד|זיכרון נייד|כונן נייד", "דיסק און קי"),
        // details link = לראות פרטים
        row("לראות פירוט|להציג פרטים|פרטים נוספים", "לראות פרטים"),
        // KeepClear's trash only = סל המחזור; others: סל הגלריה, סל של אפליקציה אחרת
        row("סל המחזור של אנדרואיד", "סל הגלריה"),
        row("סל המחזור של KeepClear", "סל המחזור"),
        row("סלי מחזור|סלי המחזור", "סלים של אפליקציות אחרות"),
        row("סל המחזור שלה|סל המחזור שלהן|סל המחזור של אפליקציה", "סל של אפליקציה אחרת"),
        // move to KeepClear's trash = להעביר לסל
        row("להעביר לסל המחזור", "להעביר לסל"),
        // bring back = לשחזר (undo = חזרה)
        row("להחזיר", "לשחזר"),
        // permanent = למחוק לצמיתות
        row("סופית|לתמיד|נעלמים לגמרי|יימחקו לגמרי", "לצמיתות"),
        // copying memories = לגבות
        row("(להעביר|העברה|העברת קבצים|מועברים) לגיבוי", "לגבות"),
        row("מה להעביר", "מה לגבות"),
        row("זיכרונות מועברים", "מגבים"),
    )

    private val english = listOf(
        row("(?i)permanently", "for good"),
        row("(?i)recycle bin", "Trash (KeepClear) or bin (other apps)"),
        row("(?i)Android's trash", "the gallery's bin"),
        row("(?i)\\bput\\b[^.]{0,20}\\bback\\b", "Restore (or Undo)"),
        row("(?i)move to backup|moved to your own drive", "Back up"),
        row("^Free (now|what)\\b", "Free up"),
        row("(?i)\\bsee more\\b|\\bshow details\\b", "See details"),
    )

    /** Strings where a variant is the right word, and why. */
    private val allowed = mapOf(
        // Google Photos' own feature is called "Free up space" („פינוי מקום”); quoted as its name.
        "move_photos_note" to "Google Photos' feature name",
    )

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun strings(dir: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (name in listOf("strings.xml", "explanations.xml")) {
            val file = File(root, "core/i18n/src/main/res/$dir/$name")
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val key = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> {
                        out[key] = unescape(e.textContent)
                    }

                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        for (j in 0 until items.length) {
                            val item = items.item(j) as Element
                            out["$key#${item.getAttribute("quantity")}"] = unescape(item.textContent)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")

    private fun listing(lang: String): Map<String, String> {
        val dir = File(root, "fastlane/metadata/android/$lang")
        return dir.walkTopDown().filter { it.isFile && it.extension == "txt" }
            .associate { "fastlane/${it.relativeTo(dir)}" to it.readText() }
    }

    private fun violations(texts: Map<String, String>, table: List<Row>): List<String> =
        texts.flatMap { (key, text) ->
            if (key.substringBefore('#') in allowed) {
                emptyList()
            } else {
                table.filter { it.forbidden.containsMatchIn(text) }
                    .map { "$key: „${it.forbidden.find(text)!!.value}” → use „${it.preferred}”" }
            }
        }

    @Test
    fun `Hebrew strings and the Hebrew store listing use one term per concept`() {
        val texts = strings("values-iw") + listing("iw-IL")
        assertTrue("the scan found no strings", texts.size > 500)
        assertEquals(emptyList<String>(), violations(texts, hebrew))
    }

    @Test
    fun `English strings and the English store listing use one term per concept`() {
        val texts = strings("values") + listing("en-US")
        assertTrue("the scan found no strings", texts.size > 500)
        assertEquals(emptyList<String>(), violations(texts, english))
    }

    @Test
    fun `the preferred terms are where the owner's table puts them`() {
        val he = strings("values-iw")
        val en = strings("values")
        val expected = listOf(
            // undo = חזרה, on every undo button (the swipe's and the result banner's)
            Triple("swipe_undo", "חזרה", "Undo"),
            Triple("clean_undo", "חזרה", "Undo"),
            Triple("details", "לראות פרטים", "See details"),
            Triple("home_see_details", "לראות פרטים", "See details"),
            Triple("group_thumbnails", "תמונות ממוזערות", "Thumbnails"),
            Triple("clean_confirm_yes", "להעביר לסל", "Move to trash"),
            Triple("move_free_confirm_yes", "להעביר לסל", "Move to trash"),
            Triple("trash_confirm_delete", "למחוק לצמיתות", "Delete for good"),
            Triple("trash_title", "סל המחזור", "Trash"),
            Triple("trash_group_android", "בסל הגלריה", "In the gallery's bin"),
            Triple("trash_from_gallery_bin", "מסל הגלריה", "From the gallery's bin"),
            Triple("home_card_move_title", "לגבות", "Back up"),
            Triple("restore_start", "לשחזר מגיבוי", "Restore from a backup"),
            Triple("tab_free", "לפנות מקום", "Free up space"),
            // the hero button uses the hero sentence's verb: „אפשר לפנות בבטחה 7.9GB” → „לפנות 7.9GB”
            Triple("home_clean_button", "לפנות %1\$s", "Free up %1\$s"),
        )
        for ((key, h, e) in expected) {
            assertEquals(key, h, he[key])
            assertEquals(key, e, en[key])
        }
        assertTrue(he.getValue("home_hero_safe").startsWith("אפשר לפנות"))
        assertTrue(he.getValue("drive_connect").contains("דיסק און קי"))
        assertTrue(he.getValue("backup_places_drive").startsWith("דיסק און קי"))
    }
}
