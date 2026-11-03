// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.i18n.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PR F2: the owner's Hebrew voice rules (CLAUDE.md) and rewrite table, checked on every Hebrew string. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "iw")
class HebrewVoiceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** Every string resource of the app, in Hebrew, by name (format arguments left in place). */
    private val all: Map<String, String> by lazy {
        R.string::class.java.fields.associate { it.name to context.resources.getString(it.getInt(null)) }
    }

    @Test
    fun `KeepClear is masculine everywhere`() {
        val feminine = listOf("מתחברת", "צריכה", "נוגעת", "ממתינה", "שולחת", "עצרה", "התחברה", "מציעה", "תמחק")
        val bad = all.filter { (_, v) -> feminine.any { f -> Regex("KeepClear[^.]{0,30}\\b$f").containsMatchIn(v) } }
        assertEquals(emptyMap<String, String>(), bad)
    }

    @Test
    fun `the user is addressed in plural, never singular`() {
        val singular = Regex("(שלך|לבקשתך|סימנת |ביקשת |ששלחת |בחרת )")
        assertEquals(emptyMap<String, String>(), all.filter { singular.containsMatchIn(it.value + " ") })
    }

    @Test
    fun `curly Hebrew quotes and double yod`() {
        assertEquals(emptyMap<String, String>(), all.filter { '"' in it.value })
        assertEquals(emptyMap<String, String>(), all.filter { Regex("(^|[^י])ימחקו").containsMatchIn(it.value) })
    }

    @Test
    fun `no jargon on the default path - only Details and Advanced may name it`() {
        val jargon = listOf("NAS", "smb://", ".KeepClearTrash", "F-Droid", "GitHub", "SHA-256")
        val allowed = setOf("details_check_pc", "details_nextcloud_check", "move_network_bad_host")
        val bad = all.filter { (k, v) -> k !in allowed && !k.startsWith("debug_") && jargon.any { it in v } }
        assertEquals(emptyMap<String, String>(), bad)
    }

    @Test
    fun `buttons are infinitives`() {
        val buttons = listOf(
            R.string.restore_start,
            R.string.restore_run,
            R.string.restore_ok,
            R.string.restore_pick_folder,
            R.string.restore_stop,
            R.string.move_network_connect,
            R.string.nextcloud_connect,
            R.string.nextcloud_sign_in,
            R.string.nextcloud_settings_button,
            R.string.whatsapp_card_button,
            R.string.swipe_delete,
            R.string.swipe_skip,
            R.string.swipe_close,
            R.string.swipe_finish_none,
            R.string.swipe_resume_continue,
            R.string.swipe_resume_restart,
            R.string.settings_swipe_swap,
            R.string.swipe_keep,
            R.string.rule_cancel,
            R.string.debug_device_check_button,
            R.string.details,
            R.string.settings_privacy,
            R.string.move_pick_target,
            R.string.apps_open_app_content,
            R.string.settings_network_button,
            R.string.clean_confirm_yes,
            R.string.trash_cancel,
            R.string.move_cancel,
        )
        for (id in buttons) {
            val text = context.getString(id)
            assertTrue(text, text.startsWith("ל"))
        }
    }

    @Test
    fun `undo is chazara - the owner's one exception to infinitive buttons`() {
        // Owner brief 2026-11-03, terminology: "bring back = לשחזר; undo = חזרה". Exactly this word, nothing else.
        assertEquals("חזרה", context.getString(R.string.swipe_undo))
        assertEquals("לסיים ולבדוק · %1\$s", context.getString(R.string.swipe_finish, "%1\$s"))
        assertEquals("להמשיך מאיפה שעצרתם", context.getString(R.string.swipe_resume_continue))
        assertEquals("להחליף את כיווני ההחלקה", context.getString(R.string.settings_swipe_swap))
    }

    @Test
    fun `the owner's rewrites, exactly`() {
        assertEquals(
            "הקבצים נמצאים ב-%1\$s, בתיקייה %2\$s. כדי לשחזר אותם לטלפון: „לשחזר מגיבוי” בלשונית גיבוי.",
            context.getString(R.string.move_receipt_how_back, "%1\$s", "%2\$s"),
        )
        assertEquals("לא להציע את זה שוב", context.getString(R.string.choice_keep))
        assertEquals("מה שבחרתם לשמור", context.getString(R.string.group_user_kept))
        assertEquals("לשחזר מגיבוי", context.getString(R.string.restore_start))
        assertEquals("לגבות למחשב בבית (דרך ה-Wi-Fi)", context.getString(R.string.move_network_option))
        assertEquals("להציע תמיד לנקות את התיקייה הזו", context.getString(R.string.choice_always_junk))
        assertEquals("לבחור את התיקייה של וואטסאפ", context.getString(R.string.whatsapp_card_button))
        assertFalse(context.getString(R.string.trash_summary, "x").contains("יפוגו"))
        assertFalse(context.getString(R.string.partial_banner).contains("אמיתיים"))
        assertFalse(context.getString(R.string.trash_uninstall_note, "1GB").contains("KeepClearTrash"))
        assertFalse(context.getString(R.string.one_by_one_sizes_later).contains("מאוחרת"))
    }

    @Test
    fun `one file is kovetz echad, never kovetz 1`() {
        val r = context.resources
        assertEquals(
            "קובץ אחד שוחזר",
            r.getQuantityString(io.github.tomerar.keepclear.core.i18n.R.plurals.restore_progress, 1, "1"),
        )
        assertEquals(
            "קובץ אחד שוחזר ונבדק",
            r.getQuantityString(io.github.tomerar.keepclear.core.i18n.R.plurals.restore_done, 1, "1"),
        )
    }
}
