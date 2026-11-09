// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Owner brief H3 item 1: the offline Help. The eight topics in the brief's order, in both languages, reachable from
 * the top bar's "?" and from Settings; the "How to restore files" card links to the Trash and to restoring from a
 * backup; and the facts the texts state are pinned to the code that makes them true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H3HelpTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun inLanguage(tag: String): Context {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config)
    }

    @Test
    fun `the eight topics of the brief, in its order, in Hebrew`() {
        val he = inLanguage("iw")
        assertEquals(
            listOf(
                "איך זה עובד",
                "האם זה בטוח",
                "מה נמחק לצמיתות ומה עובר לסל",
                // The brief wrote „איך מחזירים קובץ”; the terminology table (H2c) says „לשחזר” (C161).
                "איך משחזרים קובץ",
                "למה צריך גישה לכל הקבצים",
                "מה קורה לוואטסאפ",
                "הגיבוי נעצר — מה לעשות",
                "טלפון חדש",
                // PR H4: its two topics come after the brief's eight.
                "המקום של KeepClear והסרת ההתקנה",
                "מרחב פרטי",
                // G4a: after PR H4's two.
                "תמונות דומות",
            ),
            HelpTopics.all(foss = true).map { he.getString(it.title) },
        )
    }

    @Test
    fun `every topic has a body of paragraphs in English and Hebrew, and the ids are unique`() {
        for (foss in listOf(true, false)) {
            val topics = HelpTopics.all(foss)
            assertEquals(11, topics.size) // the brief's eight, PR H4's two and G4a's similar photos
            assertEquals(topics.size, topics.map { it.id }.toSet().size)
            for (lang in listOf("en", "iw")) {
                val c = inLanguage(lang)
                topics.forEach { t ->
                    val paragraphs = helpParagraphs(c.getString(t.body))
                    assertTrue("$lang ${t.id}", paragraphs.size >= 2 && paragraphs.all { it.length > 20 })
                }
            }
        }
    }

    @Test
    fun `the play build's access topic speaks of WhatsApp's folder, not of all files`() {
        val en = inLanguage("en")
        assertEquals(
            "Why KeepClear asks to see all files",
            en.getString(HelpTopics.byId("access", foss = true)!!.title),
        )
        val play = en.getString(HelpTopics.byId("access", foss = false)!!.body)
        assertTrue(play, "all files" !in play && "folder" in play)
    }

    @Test
    fun `what the texts say is what the code does`() {
        val en = inLanguage("en")
        // "Files that rebuild themselves ... are deleted right away": exactly these rules (ADR 0006 amendment).
        assertEquals(
            setOf("thumbnails", "temp_files", "os_junk", "wa_status_old"),
            io.github.tomerar.keepclear.core.safety.PermanentKinds.rebuildRules,
        )
        // "30 days in the trash".
        assertEquals(30L * 24 * 3_600_000, io.github.tomerar.keepclear.engine.trash.TrashPolicy.RETENTION_MILLIS)
        // "at 10% the backup pauses by itself" (owner decision P3).
        assertEquals(10, io.github.tomerar.keepclear.engine.move.BatteryPolicy.PAUSE_AT)
        assertTrue("10%" in en.getString(R.string.help_stopped_body))
        // Settings' switch and the intro's button are quoted with their real labels.
        assertTrue(en.getString(R.string.settings_keep_originals) in en.getString(R.string.help_permanent_body))
        assertTrue(en.getString(R.string.intro_restore) in en.getString(R.string.help_new_phone_body))
        val he = inLanguage("iw")
        assertTrue(he.getString(R.string.settings_keep_originals) in he.getString(R.string.help_permanent_body))
        assertTrue(he.getString(R.string.intro_restore) in he.getString(R.string.help_new_phone_body))
        assertTrue(he.getString(R.string.restore_start) in he.getString(R.string.help_get_back_body))
        assertTrue(he.getString(R.string.move_continue) in he.getString(R.string.help_stopped_body))
        // G4a: the similar-photos topic quotes the real entry, lane and marker labels.
        for (res in listOf(en, he)) {
            val body = res.getString(R.string.help_similar_body)
            for (label in listOf(
                R.string.gallery_entry_title,
                R.string.gallery_suggested,
                R.string.home_card_review_title,
            )) {
                assertTrue(res.getString(label), res.getString(label) in body)
            }
        }
    }

    @Test
    fun `the top bar's question mark opens Help`() {
        var help = 0
        compose.setContent {
            KeepClearTheme {
                AppScreens(
                    state = HomeUiState(),
                    onOpen = {},
                    onBack = {},
                    home = {},
                    tabs = TabsUi(onOpenHelp = { help++ }),
                )
            }
        }
        compose.onNodeWithTag("openHelp").performClick()
        assertEquals(1, help)
    }

    @Test
    fun `Help lists the topics and a topic opens`() {
        val opened = mutableListOf<Screen>()
        compose.setContent {
            KeepClearTheme {
                AppScreens(
                    state = HomeUiState(screens = listOf(Screen.Home, Screen.Settings, Screen.Help)),
                    onOpen = { opened += it },
                    onBack = {},
                    home = {},
                )
            }
        }
        HelpTopics.all(foss = true).forEach { compose.onNodeWithTag("helpTopic_${it.id}").performScrollTo() }
        compose.onNodeWithTag("helpTopic_${HelpTopics.GET_BACK}").performScrollTo().performClick()
        assertEquals(listOf<Screen>(Screen.HelpTopic(HelpTopics.GET_BACK)), opened)
    }

    @Test
    fun `a topic's links open the Trash and the restore flow`() {
        var trash = 0
        val tabs = mutableListOf<Tab>()
        var restore = 0
        compose.setContent {
            KeepClearTheme {
                AppScreens(
                    state = HomeUiState(
                        screens = listOf(Screen.Home, Screen.Help, Screen.HelpTopic(HelpTopics.GET_BACK)),
                    ),
                    onOpen = {},
                    onBack = {},
                    home = {},
                    tabs = TabsUi(onOpenTrash = { trash++ }, onSelectTab = { tabs += it }),
                    moveActions = MoveActions(restore = RestoreActions(onStart = { restore++ })),
                )
            }
        }
        compose.onNodeWithTag("helpLink_TRASH").performScrollTo().performClick()
        assertEquals(1, trash)
        compose.onNodeWithTag("helpLink_RESTORE_FROM_BACKUP").performScrollTo().performClick()
        assertEquals(listOf(Tab.BACKUP), tabs)
        assertEquals(1, restore)
    }

    @Test
    fun `Settings opens Help, history, report and About, and the restore card links to the Trash and a backup`() {
        val calls = mutableListOf<String>()
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    testModeOn = true,
                    onTestModeOn = {},
                    onTestModeOff = {},
                    onBack = {},
                    actions = SettingsActions(onReport = { calls += "report" }),
                    nav = SettingsNav(
                        onHelp = { calls += "help" },
                        onHistory = { calls += "history" },
                        onAbout = { calls += "about" },
                        onTrash = { calls += "trash" },
                        onRestoreFromBackup = { calls += "restore" },
                    ),
                )
            }
        }
        listOf("openHelpSettings", "getBackTrash", "getBackRestore", "openHistory", "openReport", "openAbout")
            .forEach { compose.onNodeWithTag(it).performScrollTo().performClick() }
        assertEquals(listOf("help", "trash", "restore", "history", "report", "about"), calls)
    }
}
