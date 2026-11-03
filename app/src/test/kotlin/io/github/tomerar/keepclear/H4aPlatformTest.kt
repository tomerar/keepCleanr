// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.SHEET_ANIMATION
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.classify.Total
import io.github.tomerar.keepclear.engine.move.JobState
import io.github.tomerar.keepclear.engine.move.MoveJob
import io.github.tomerar.keepclear.engine.move.PauseReason
import io.github.tomerar.keepclear.engine.move.Receipt
import io.github.tomerar.keepclear.engine.scan.ScanItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/** PR H4a: owner brief H4 items 1–5 (manage space, predictive back, insets, notifications, kill detection). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H4aPlatformTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var t: TestApp? = null

    @After
    fun tearDown() {
        t?.close()
    }

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun source(name: String) = File(root, "app/src/main/kotlin/io/github/tomerar/keepclear/$name").readText()

    private fun mainSources(): Map<String, String> = File(root, "app/src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .associate { it.relativeTo(root).path to it.readText() }

    private fun inLanguage(tag: String): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
    )

    private fun text(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString { Bidi.strip(it.text) }

    // ---------------------------------------------------------------- item 1: manage space

    @Test
    fun `item 1 Settings' Manage space opens KeepClear's own screen, which only opens the trash`() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        // Fully qualified in the manifest: a relative name would point into the test build's `.test` package.
        assertEquals(ManageSpaceActivity::class.java.name, info.manageSpaceActivityName)
        var opened = 0
        compose.setContent {
            KeepClearTheme {
                ManageSpaceScreen(ManageSpaceUi(trash = Total(12, 3_400_000_000), onOpenTrash = { opened++ }))
            }
        }
        assertEquals("In the trash: 12 files · 3.4 GB", text("manageSpaceTrash"))
        compose.onNodeWithTag("manageSpaceOpenTrash").performScrollTo().performClick()
        assertEquals(1, opened)
        // The screen has no way to clear KeepClear's data or the trash's list: its one action is navigation.
        val intent = ManageSpace.openTrashIntent(context)
        assertEquals(ShortcutRoute.TRASH, ShortcutRoute.of(intent))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        val screen = source("ManageSpace.kt")
        listOf("clearApplicationUserData", "deleteDatabase", "purge", "emptyNow", ".delete(").forEach {
            assertFalse("ManageSpace.kt must not $it", screen.contains(it))
        }
    }

    @Test
    fun `item 1 the manage-space screen says the trash is empty, in Hebrew too`() {
        compose.setContent { KeepClearTheme { ManageSpaceScreen(ManageSpaceUi(trash = Total(0, 0))) } }
        assertEquals("The trash is empty.", text("manageSpaceTrash"))
        assertEquals("סל המחזור ריק.", inLanguage("iw").getString(R.string.manage_space_trash_empty))
    }

    // ---------------------------------------------------------------- item 2: predictive back

    @Test
    fun `item 2 no onBackPressed or KEYCODE_BACK, and BackHandler is never always on`() {
        val sources = mainSources()
        val bad = sources.filter { (_, code) ->
            Regex("""\bonBackPressed\s*\(\s*\)\s*\{|override fun onBackPressed|KEYCODE_BACK|onKeyDown""")
                .containsMatchIn(code)
        }.keys
        assertEquals(emptySet<String>(), bad)
        val handlers = sources.flatMap { (file, code) ->
            Regex("""(?:Predictive)?BackHandler\(([^)]*)""").findAll(code).map { file to it.groupValues[1] }.toList()
        }
        assertTrue(handlers.isNotEmpty())
        handlers.forEach { (file, args) ->
            assertTrue("$file: BackHandler without enabled = …: $args", args.contains("enabled ="))
            assertFalse("$file: BackHandler always on", args.contains("enabled = true"))
        }
    }

    @Test
    fun `item 2 Back first leaves the selection, then the group`() {
        val c = Classifier()
        val now = 1_800_000_000_000L
        val items = (1..3).map { i ->
            val p = "/storage/emulated/0/Pictures/Screenshots/Screenshot_$i.png"
            c.classify(ScanItem(KPath.Local(p), p, 1_000_000, now - 100L * 86_400_000L), now = now)
        }
        val group = ClassificationSummary.of(items).groups.first { it.category == Category.REVIEW }
        val selected = mutableStateOf(setOf(items.first().item.path))
        var left = 0
        compose.setContent {
            KeepClearTheme {
                GroupScreen(
                    group,
                    onOpenItem = {},
                    onBack = { left++ },
                    selection = GroupSelection(selected.value, onSelect = { selected.value = it }, null, {}),
                )
            }
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(emptySet<String>(), selected.value)
        assertEquals(0, left)
        // With nothing selected the handler is off: Back goes to whoever owns it (the app's navigation).
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `item 2 sheets slide up and down, and not at all with Remove animations`() {
        val reduced = mutableStateOf(false)
        val show = mutableStateOf(true)
        compose.setContent {
            KeepClearTheme(reducedMotion = reduced.value) {
                if (show.value) {
                    ConfirmSheet(title = "Title", dismissText = "Close", onDismiss = {}, confirmButton = {
                        KcButton("OK", {})
                    })
                }
            }
        }
        for (r in listOf(false, true)) {
            show.value = false
            compose.waitForIdle()
            reduced.value = r
            show.value = true
            compose.waitForIdle()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertEquals(if (r) 0 else SHEET_ANIMATION, dialog.window!!.attributes.windowAnimations)
        }
    }

    // ---------------------------------------------------------------- item 3: keyboard

    @Test
    fun `item 3 a sub-screen moves up above the keyboard`() {
        var height = 0
        compose.setContent {
            SubScreenFrame {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().onSizeChanged { height = it.height },
                )
            }
        }
        compose.waitForIdle()
        val before = height
        val view = compose.activity.window.decorView
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, 200))
            .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnUiThread {
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()
        assertTrue("before $before", before > 300)
        assertEquals(before - 200, height)
    }

    // ---------------------------------------------------------------- item 4: notifications

    @Test
    fun `item 4 the channels have localized names and follow a language change`() {
        val nm = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.registerAll(inLanguage("iw"))
        assertEquals(
            listOf("התקדמות הגיבוי", "תוצאות גיבוי", "תזכורות על מקום", "תזכורות גיבוי"),
            NotificationChannels.all.map { nm.getNotificationChannel(it.id).name.toString() },
        )
        NotificationChannels.registerAll(inLanguage("en"))
        assertEquals("Backup progress", nm.getNotificationChannel(NotificationChannels.BACKUP_PROGRESS).name)
        assertEquals("Backup results", nm.getNotificationChannel(NotificationChannels.BACKUP_RESULTS).name)
        // The application re-registers when the configuration (language) changes.
        assertTrue(source("KeepClearApplication.kt").contains("onConfigurationChanged"))
        val intent = NotificationChannels.settingsIntent(context, NotificationChannels.SPACE)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(NotificationChannels.SPACE, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `item 4 Settings lists every channel and opens its page`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(false, {}, {}, {}, SettingsActions(onOpenChannel = { opened += it }))
            }
        }
        NotificationChannels.all.forEach { c ->
            compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("channel_${c.id}"))
            compose.onNodeWithTag("channel_${c.id}").performClick()
        }
        assertEquals(NotificationChannels.all.map { it.id }, opened)
    }

    @Test
    fun `item 4 the notification permission is asked only from the first backup and the reminders`() {
        val main = source("MainActivity.kt")
        val asks = Regex("""launch\(Manifest\.permission\.POST_NOTIFICATIONS\)""").findAll(main).toList()
        assertEquals(2, asks.size)
        // Each ask sits in a handler the user triggers: the backup's explanation dialog, Settings' reminder switch.
        val before = asks.map { main.substring(maxOf(0, it.range.first - 700), it.range.first) }
        assertTrue(before[0].contains("explainNotifications"))
        assertTrue(before[1].contains("onAskNotifications"))
        // Nothing else in the app asks (no first-launch request, no other screen).
        val elsewhere = mainSources().filterKeys { !it.endsWith("MainActivity.kt") }
            .filterValues { it.contains("POST_NOTIFICATIONS") && it.contains("RequestPermission") }
        assertEquals(emptyMap<String, String>(), elsewhere)
        // No request before the user acts: onCreate's first lines (up to setContent) don't ask.
        val onCreate = main.substringAfter("override fun onCreate").substringBefore("setContent {")
        assertFalse(onCreate.contains("POST_NOTIFICATIONS"))
    }

    // ---------------------------------------------------------------- item 5: kill detection

    @Test
    fun `item 5 exit reasons map to the phone, a crash, an update or other`() {
        val cases = mapOf(
            KillReasons.LOW_MEMORY to StopCause.SYSTEM,
            KillReasons.EXCESSIVE_RESOURCE_USAGE to StopCause.SYSTEM,
            KillReasons.FREEZER to StopCause.SYSTEM,
            KillReasons.OTHER to StopCause.SYSTEM,
            KillReasons.USER_REQUESTED to StopCause.SYSTEM,
            KillReasons.SIGNALED to StopCause.SYSTEM,
            KillReasons.CRASH to StopCause.CRASH,
            KillReasons.CRASH_NATIVE to StopCause.CRASH,
            KillReasons.ANR to StopCause.CRASH,
            KillReasons.PACKAGE_UPDATED to StopCause.UPDATE,
            KillReasons.EXIT_SELF to StopCause.OTHER,
            0 to StopCause.OTHER,
        )
        cases.forEach { (reason, cause) -> assertEquals("reason $reason", cause, KillReasons.cause(reason)) }
        // The constants are Android's own values.
        assertEquals(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, KillReasons.LOW_MEMORY)
        assertEquals(android.app.ApplicationExitInfo.REASON_FREEZER, KillReasons.FREEZER)
        assertEquals(android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED, KillReasons.PACKAGE_UPDATED)
        assertEquals(android.app.ApplicationExitInfo.REASON_USER_REQUESTED, KillReasons.USER_REQUESTED)
        assertEquals(android.app.ApplicationExitInfo.REASON_OTHER, KillReasons.OTHER)
    }

    private fun job(id: String, createdAt: Long) =
        MoveJob(id, "d", "Drive", "KeepClear/P", createdAt, JobState.RUNNING)

    @Test
    fun `item 5 a running job found after the system ended KeepClear is stopped by the phone, a crash is not`() {
        val app = TestApp(tmp.root).also { t = it }
        val store = app.app.moveController.store
        var exits = listOf(ProcessExit(5_000, KillReasons.LOW_MEMORY))
        app.app.moveController.processExits = ProcessExitSource { exits }
        runBlocking { store.putJob(job("killed", 1_000)) }
        runBlocking { app.app.moveController.markFoundStopped() }
        assertEquals(PauseReason.STOPPED_BY_PHONE, runBlocking { store.job("killed") }!!.pauseReason)

        exits = listOf(ProcessExit(5_000, KillReasons.LOW_MEMORY), ProcessExit(6_000, KillReasons.CRASH))
        runBlocking { store.putJob(job("crashed", 1_000)) }
        runBlocking { app.app.moveController.markFoundStopped() }
        assertEquals(PauseReason.INTERRUPTED, runBlocking { store.job("crashed") }!!.pauseReason)

        // An exit from before the job started says nothing about it; no record at all is "interrupted" too.
        exits = listOf(ProcessExit(500, KillReasons.LOW_MEMORY))
        runBlocking { store.putJob(job("old", 1_000)) }
        runBlocking { app.app.moveController.markFoundStopped() }
        assertEquals(PauseReason.INTERRUPTED, runBlocking { store.job("old") }!!.pauseReason)
        exits = emptyList()
        runBlocking { store.putJob(job("none", 1_000)) }
        runBlocking { app.app.moveController.markFoundStopped() }
        assertEquals(PauseReason.INTERRUPTED, runBlocking { store.job("none") }!!.pauseReason)
    }

    @Test
    fun `item 5 the kill card offers background running and this maker's steps, and the screens it opens`() {
        var allowed = 0
        var guide = 0
        val steps = BatterySteps.forMaker("OnePlus")
        compose.setContent {
            KeepClearTheme {
                MoveScreen(
                    summary = ClassificationSummary.of(emptyList()),
                    state = MoveUiState(
                        jobs = listOf(
                            JobView(
                                MoveJob(
                                    "j",
                                    "t",
                                    "SanDisk",
                                    "KeepClear/P",
                                    1,
                                    JobState.PAUSED,
                                    PauseReason.STOPPED_BY_PHONE,
                                ),
                                Receipt("j", "SanDisk", "KeepClear/P", 1, 1_000, emptyMap(), 0, 2, 0),
                                true,
                            ),
                        ),
                    ),
                    now = 2_000,
                    notificationsOff = false,
                    actions = MoveActions(
                        battery = BatteryActions(steps, onAllowBackground = { allowed++ }, onOpenGuide = { guide++ }),
                    ),
                    onBack = null,
                )
            }
        }
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("allowBackground"))
        assertEquals("Allow the backup to run in the background", text("allowBackground"))
        compose.onNodeWithTag("allowBackground").performClick()
        compose.onNodeWithTag("moveScreen").performScrollToNode(hasTestTag("makerGuide"))
        compose.onNodeWithTag("makerGuide").performClick()
        assertEquals(1, allowed)
        assertEquals(1, guide)
        assertEquals("https://dontkillmyapp.com/oneplus", steps.guideUrl)
        assertTrue(context.getString(steps.steps).contains("Deep optimization"))
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, BatterySteps.settingsIntent().action)
    }

    @Test
    fun `item 5 every maker has steps in both languages, unknown makers get the general ones`() {
        mapOf(
            "OnePlus" to "oneplus",
            "samsung" to "samsung",
            "Xiaomi" to "xiaomi",
            "Redmi" to "xiaomi",
            "HUAWEI" to "huawei",
            "OPPO" to "oppo",
            "realme" to "realme",
            "vivo" to "vivo",
            "Google" to "generic",
            null to "generic",
        ).forEach { (maker, slug) ->
            val s = BatterySteps.forMaker(maker)
            assertEquals(slug, s.maker)
            assertTrue(inLanguage("iw").getString(s.steps).isNotBlank())
            assertTrue(s.guideUrl.startsWith("https://dontkillmyapp.com/"))
        }
        // No restricted permission: never the one-app "ignore battery optimisations" request.
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertFalse(mainSources().values.any { it.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS") })
    }

    @Test
    fun `item 5 Troubleshooting shows when the backup last ran, and the run is recorded`() {
        val app = TestApp(tmp.root).also { t = it }
        assertNull(app.app.moveController.runLog.lastRunAt)
        app.app.moveController.setRunning("j")
        app.app.moveController.setRunning(null)
        val at = app.app.moveController.runLog.lastRunAt
        assertTrue(at != null && at > 0)
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    false,
                    {},
                    {},
                    {},
                    SettingsActions(
                        troubleshooting = TroubleshootingUi(lastRunAt = 1_800_000_000_000, stoppedByPhone = true),
                    ),
                )
            }
        }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("lastBackupRun"))
        assertTrue(text("lastBackupRun").startsWith("The backup last ran: "))
        assertEquals("פתרון בעיות", inLanguage("iw").getString(R.string.settings_troubleshooting))
    }

    @Test
    fun `Help has the H4 topics and the backup-stopped topic ends with this maker's steps`() {
        val ids = HelpTopics.all(foss = true).map { it.id }
        assertTrue(ids.containsAll(listOf(HelpTopics.MANAGE_SPACE, "private_space")))
        val stopped = HelpTopics.byId("stopped", foss = true)!!
        assertTrue(stopped.batterySteps)
        compose.setContent { KeepClearTheme { HelpTopicScreen(stopped, onLink = {}, onBack = {}) } }
        compose.onNodeWithTag("helpTopic").performScrollToNode(hasTestTag("helpBattery"))
        assertEquals(
            "מרחב פרטי",
            inLanguage("iw").getString(HelpTopics.byId("private_space", foss = true)!!.title),
        )
    }
}
