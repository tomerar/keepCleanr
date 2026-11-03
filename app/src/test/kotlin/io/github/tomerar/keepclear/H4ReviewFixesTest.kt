// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.i18n.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Review of PR #60 (H4): StrictMode-safe settings files, and background notifications in the chosen language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class H4ReviewFixesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        AppLanguage.store(context, null)
    }

    @Test
    fun `on Android 12 background reminders and channel names use the language chosen in KeepClear`() {
        // The application context keeps the language of process start (English here); the user chose Hebrew since.
        assertEquals("Backup progress", context.getString(R.string.move_channel))
        AppLanguage.store(context, "iw")
        val nm = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.registerAll(context)
        assertEquals("התקדמות הגיבוי", nm.getNotificationChannel(NotificationChannels.BACKUP_PROGRESS).name)
        SpaceReminder.show(context, 1_000_000_000L, 0L)
        val posted = shadowOf(nm).allNotifications.single()
        val text = posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        val hebrew = AppLanguage.wrap(context)
        assertEquals(SpaceReminder.text(hebrew, 1_000_000_000L, 0L, java.util.Locale.forLanguageTag("iw")), text)
        // A later worker run doesn't rename the channels back to the process's language.
        NotificationChannels.registerAll(context)
        assertEquals("התקדמות הגיבוי", nm.getNotificationChannel(NotificationChannels.BACKUP_PROGRESS).name)
    }

    @Test
    fun `on Android 12 going back to the system language undoes the language the process started with`() {
        // The process started with Hebrew chosen (the application context carries it); the phone itself is English.
        AppLanguage.store(context, "iw")
        val startedInHebrew = AppLanguage.wrap(context)
        assertEquals("התקדמות הגיבוי", startedInHebrew.getString(R.string.move_channel))
        // The user picks "System language": the stored choice is removed.
        AppLanguage.store(context, null)
        assertEquals("Backup progress", AppLanguage.wrap(startedInHebrew).getString(R.string.move_channel))
        val nm = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.registerAll(startedInHebrew)
        assertEquals("Backup progress", nm.getNotificationChannel(NotificationChannels.BACKUP_PROGRESS).name)
    }

    @Test
    fun `every settings file is opened inside the main-thread settings allowance, or off the main thread`() {
        // StrictMode in test builds kills the app on a main-thread disk read; opening a SharedPreferences file is one.
        // Allowed without MainThreadPolicy.settings only where the open happens off the main thread or the value is
        // only read inside the allowance at its use site (each listed with why).
        val allowed = listOf(
            "getSharedPreferences(SwipeSessionStore.PREFS", // read and written off the main thread (H2b)
            "getSharedPreferences(CrashCheck.PREFS", // in moveController.scope (background)
            "getSharedPreferences(WhatsNew.PREFS", // in moveController.scope (background)
            "private val prefs get() = getSharedPreferences(\"move\"", // a getter, only called inside the allowance
            // SystemTrashClock: constructed inside the allowance.
            "private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)",
        )
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val lines = file.readLines()
                lines.mapIndexedNotNull { i, line ->
                    if ("getSharedPreferences(" !in line || allowed.any { it in line }) return@mapIndexedNotNull null
                    val around = lines.subList(maxOf(0, i - 2), i + 1).joinToString("\n")
                    "${file.name}:${i + 1}".takeIf { "MainThreadPolicy.settings" !in around }
                }
            }.toList()
        assertTrue("settings files opened outside the allowance: $offenders", offenders.isEmpty())
    }
}
