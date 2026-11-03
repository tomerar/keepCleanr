// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.designsystem.KcContrast
import io.github.tomerar.keepclear.core.designsystem.KeepClearContrastSchemes
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.engine.classify.AppInfo
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import io.github.tomerar.keepclear.core.i18n.R as S

/** PR H4b: owner brief H4 items 6–9 (accessibility, language, apps, quick checks). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H4bAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun kotlinSources(vararg dirs: String): Map<String, String> = dirs.flatMap { dir ->
        File(root, dir).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }.associate { it.relativeTo(root).path to it.readText() }

    private val appAndUi = arrayOf("app/src/main/kotlin", "core/designsystem/src/main/kotlin")

    // ---------------------------------------------------------------- item 6

    @Test
    fun `item 6 a screen's main action is 56 dp tall, every other button at least 48 dp`() {
        compose.setContent {
            KeepClearTheme {
                androidx.compose.foundation.layout.Column {
                    KcButton("Free up", {}, androidx.compose.ui.Modifier.testTag("primary"))
                    KcButton("Delete", {
                    }, androidx.compose.ui.Modifier.testTag("destructive"), KcButtonStyle.Destructive)
                    KcButton("Later", {}, androidx.compose.ui.Modifier.testTag("tonal"), KcButtonStyle.Tonal)
                    KcButton("Details", {}, androidx.compose.ui.Modifier.testTag("text"), KcButtonStyle.Text)
                    SafeConfirmButton("Move to trash", {
                    }, androidx.compose.ui.Modifier.testTag("confirm"), permanent = false)
                }
            }
        }
        compose.mainClock.advanceTimeBy(CONFIRM_READY_MILLIS)

        fun height(tag: String) = with(compose.density) {
            compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height.toDp()
        }
        listOf("primary", "destructive", "confirm").forEach { assertTrue("$it: ${height(it)}", height(it) >= 56.dp) }
        listOf("tonal", "text").forEach { assertTrue("$it: ${height(it)}", height(it) >= 48.dp) }
    }

    @Test
    fun `item 6 a sheet tells TalkBack its title when it opens`() {
        compose.setContent {
            KeepClearTheme {
                ConfirmSheet(title = "Move 3 files to the trash?", dismissText = "Close", onDismiss = {
                }, confirmButton = {
                    KcButton("OK", {})
                })
            }
        }
        val panes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle),
            useUnmergedTree = true,
        )
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.PaneTitle] }
        assertTrue(panes.toString(), "Move 3 files to the trash?" in panes)
    }

    private fun entry(id: String, verified: String? = null) = TrashEntry(
        id = id,
        originalPath = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
        trashedPath = "/storage/emulated/0/.KeepClearTrash/20261031/$id/IMG_$id.jpg",
        volumeRoot = "/storage/emulated/0",
        size = 3_000_000,
        mtimeMillis = 1,
        reason = "TRASH",
        deletedAt = 1_800_000_000_000,
        expiresAt = 1_802_592_000_000,
        deletedAtElapsed = 0,
        bootId = "b",
        state = TrashState.ACTIVE,
        verifiedCopy = verified,
    )

    @Test
    fun `item 6 a trash row offers Restore and its permanent action in the TalkBack actions menu`() {
        var restored: List<TrashEntry>? = null
        compose.setContent {
            KeepClearTheme {
                TrashScreen(
                    TrashUi(listOf(entry("a", verified = "USB:KeepClear/P/IMG_a.jpg")), releasable = setOf("a")),
                    onRestore = { e, _ -> restored = e },
                    onPurge = { _, _ -> },
                    onBack = {},
                    initialView = TrashView.LIST,
                )
            }
        }
        compose.onNodeWithTag("trash").performScrollToNode(hasTestTag("entry_a"))
        val node = compose.onNodeWithTag("entry_a", useUnmergedTree = true).fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Restore", "Free up now"), actions.map { it.label })
        actions.first().action()
        compose.waitForIdle()
        assertEquals(listOf("a"), restored?.map { it.id })
    }

    @Test
    fun `item 6 an app row is read as one item`() {
        val now = 1_800_000_000_000L
        compose.setContent {
            KeepClearTheme {
                AppsScreen(
                    listOf(AppInfo("daily.app", "Daily", 500_000_000, 200_000_000, 50_000_000, now, now)),
                    now,
                    AppsActions(),
                    onBack = {},
                )
            }
        }
        val row = compose.onNodeWithTag("app_daily.app", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(row.config.isMergingSemanticsOfDescendants)
    }

    @Test
    fun `item 6 every animation honours Remove animations`() {
        val animation = Regex(
            """\banimate\w*AsState\(|Animatable\(|AnimatedVisibility\(|AnimatedContent\(|Crossfade\(|""" +
                """rememberInfiniteTransition\(|animateContentSize\(|setWindowAnimations\(""",
        )
        val bad = kotlinSources(*appAndUi).filter { (_, code) ->
            animation.containsMatchIn(code) && !code.contains("reducedMotion") && !code.contains("reduced")
        }.keys
        assertEquals(emptySet<String>(), bad)
    }

    @Test
    fun `item 6 Android's high contrast setting gives the stronger scheme`() {
        var primary = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            KeepClearTheme(darkTheme = false, contrast = KcContrast.HIGH) {
                primary = MaterialTheme.colorScheme.primary
            }
        }
        compose.waitForIdle()
        assertEquals(KeepClearContrastSchemes.LightHigh.primary, primary)
    }

    @Test
    fun `item 6 there is no timed undo to shorten for TalkBack users`() {
        // The brief's "undo snackbar" doesn't exist: Undo is a result banner that stays until the user leaves the
        // screen (no timeout at all, which is longer than any recommended timeout). Pinned so a timed one isn't
        // added without the accessibility timeout.
        val timed = kotlinSources(*appAndUi).filter { (_, code) ->
            code.contains("SnackbarDuration") || code.contains("showSnackbar")
        }.filterValues { !it.contains("getRecommendedTimeoutMillis") }.keys
        assertEquals(emptySet<String>(), timed)
    }

    // ---------------------------------------------------------------- item 7

    @Test
    fun `item 7 Hebrew is he or iw, compared by meaning`() {
        assertTrue(Languages.isHebrew(Locale.forLanguageTag("he")))
        assertTrue(Languages.isHebrew(Locale.forLanguageTag("iw")))
        assertTrue(Languages.isHebrew(Locale.forLanguageTag("he-IL")))
        assertTrue(Languages.isHebrew("iw"))
        assertFalse(Languages.isHebrew(Locale.ENGLISH))
        assertEquals("iw", Languages.chipTag("he"))
        assertEquals("iw", Languages.chipTag("iw"))
        assertEquals("en", Languages.chipTag("en"))
        assertEquals(null, Languages.chipTag(null))
        // Nowhere else compares a language with only one of the two spellings.
        val oneSpelling = Regex("""(language|lang)\w*\s*==\s*"(he|iw)"|"(he|iw)"\s*==""")
        val bad = kotlinSources("app/src/main/kotlin", "core/common/src/main/kotlin").filter { (file, code) ->
            !file.endsWith("AppLanguage.kt") && code.lines().any { line ->
                oneSpelling.containsMatchIn(line) && !(line.contains("\"he\"") && line.contains("\"iw\""))
            }
        }.keys
        assertEquals(emptySet<String>(), bad)
    }

    @Test
    @Config(sdk = [30])
    fun `item 7 on Android 11 the in-app language applies to KeepClear's screens`() {
        AppLanguage.store(context, "iw")
        try {
            val wrapped = AppLanguage.wrap(context)
            assertTrue(Languages.isHebrew(wrapped.resources.configuration.locales[0]))
            assertEquals("סל המחזור ריק.", wrapped.getString(S.string.manage_space_trash_empty))
            AppLanguage.store(context, null)
            assertEquals("The trash is empty.", AppLanguage.wrap(context).getString(S.string.manage_space_trash_empty))
        } finally {
            AppLanguage.store(context, null)
            Locale.setDefault(Locale.ENGLISH)
        }
    }

    @Test
    fun `item 7 Android 13 and later keep the language themselves, with their own settings page`() {
        AppLanguage.store(context, "iw")
        try {
            // On 13+ KeepClear never overrides what Android applies.
            assertTrue(AppLanguage.wrap(context, sdk = 36) === context)
        } finally {
            AppLanguage.store(context, null)
        }
        val intent = AppLanguage.systemSettingsIntent(context)
        assertEquals(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
        var opened = 0
        compose.setContent {
            KeepClearTheme { SettingsScreen(false, {}, {}, {}, SettingsActions(onSystemLanguage = { opened++ })) }
        }
        compose.onNodeWithTag("systemLanguage").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `item 7 every Hebrew plural has one, two and other`() {
        val missing = mutableListOf<String>()
        File(root, "core/i18n/src/main/res/values-iw").listFiles()!!.filter { it.name.endsWith(".xml") }.forEach { f ->
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val plurals = doc.getElementsByTagName("plurals")
            for (i in 0 until plurals.length) {
                val p = plurals.item(i) as org.w3c.dom.Element
                val items = p.getElementsByTagName("item")
                val quantities = (0 until items.length).map {
                    (items.item(it) as org.w3c.dom.Element).getAttribute("quantity")
                }
                if (!quantities.containsAll(listOf("one", "two", "other"))) {
                    missing +=
                        "${p.getAttribute("name")} $quantities"
                }
            }
        }
        assertEquals(emptyList<String>(), missing)
    }

    // ---------------------------------------------------------------- item 8

    @Test
    fun `item 8 Archive is offered on Android 15 and later, only where Android says the app can be archived`() {
        assertTrue(AppArchive.offer(35) { true })
        assertFalse(AppArchive.offer(35) { false })
        assertFalse(AppArchive.offer(34) { true })
        assertFalse(AppArchive.offer(36) { error("no installer") })
    }

    @Test
    fun `item 8 only Android's own confirmation is opened from the archive answer`() {
        val confirm = Intent("android.content.pm.action.CONFIRM_ARCHIVE")
        assertEquals(
            ArchiveStatus.Next.Confirm(confirm),
            ArchiveStatus.next(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm) { true },
        )
        assertEquals(
            ArchiveStatus.Next.Nothing,
            ArchiveStatus.next(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm) {
                false
            },
        )
        assertEquals(ArchiveStatus.Next.Nothing, ArchiveStatus.next(PackageInstaller.STATUS_SUCCESS, confirm) { true })
        assertEquals(
            ArchiveStatus.Next.Nothing,
            ArchiveStatus.next(PackageInstaller.STATUS_PENDING_USER_ACTION, null) {
                true
            },
        )
    }

    @Test
    fun `item 8 an unused app offers Archive and Uninstall, both blocked in test mode, with its size split`() {
        val now = 1_800_000_000_000L
        val month = 30L * 86_400_000L
        val apps = listOf(
            AppInfo(
                "old.game",
                "Old game",
                2_000_000_000,
                300_000_000,
                100_000_000,
                now - 9 * month,
                now - 30 * month,
            ),
        )
        var archived: String? = null
        val testMode = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            KeepClearTheme {
                AppsScreen(
                    apps,
                    now,
                    AppsActions(canArchive = { true }, onArchive = { archived = it }, testModeOn = testMode.value),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("archiveNote").assertExists()
        assertEquals(
            "App 2.0 GB · Data 200 MB · Temporary files 100 MB",
            compose.onNodeWithTag(
                "split_old.game",
                useUnmergedTree = true,
            ).fetchSemanticsNode().config[SemanticsProperties.Text]
                .joinToString { io.github.tomerar.keepclear.core.common.Bidi.strip(it.text) },
        )
        compose.onNodeWithTag("archive_old.game").performClick()
        assertEquals("old.game", archived)
        archived = null
        testMode.value = true
        compose.onNodeWithTag("archive_old.game").performClick()
        assertEquals(null, archived)
        compose.onNodeWithTag("uninstallTrial_old.game").assertExists()
        assertEquals(AppSizes(2_000_000_000, 200_000_000, 100_000_000), AppSizes.of(apps.first()))
        assertEquals("לארכב", inLanguage("iw").getString(S.string.apps_archive))
    }

    @Test
    fun `item 8 without archiving the row keeps Uninstall as before`() {
        val now = 1_800_000_000_000L
        val month = 30L * 86_400_000L
        compose.setContent {
            KeepClearTheme {
                AppsScreen(
                    listOf(AppInfo("old.game", "Old game", 2_000_000_000, 0, 0, now - 9 * month, now - 30 * month)),
                    now,
                    AppsActions(),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("archive_old.game").assertDoesNotExist()
        compose.onNodeWithTag("uninstall_old.game").assertExists()
    }

    private fun inLanguage(tag: String): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
    )
}
