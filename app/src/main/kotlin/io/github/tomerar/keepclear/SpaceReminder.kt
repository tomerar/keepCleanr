// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.i18n.R
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * SET-3: an opt-in, local reminder when space runs low. It only tells; it never deletes, moves or scans anything
 * in the background (safety rule 1).
 */
object ReminderPolicy {
    /** Low means under 10% of the storage or under 2 GB, whichever is larger. */
    const val MIN_FREE_BYTES = 2L * 1000 * 1000 * 1000
    const val WEEK_MILLIS = 7L * 24 * 3_600_000

    fun isLow(freeBytes: Long, totalBytes: Long) = freeBytes < maxOf(MIN_FREE_BYTES, totalBytes / 10)

    /** At most one reminder a week, and only when the user turned reminders on. */
    fun shouldRemind(enabled: Boolean, freeBytes: Long, totalBytes: Long, lastShownAt: Long, now: Long): Boolean =
        enabled && isLow(freeBytes, totalBytes) && (lastShownAt == 0L || now - lastShownAt >= WEEK_MILLIS)
}

/** What the reminder needs, kept on the phone: the switch, when it last showed, and the last scan's junk total. */
class ReminderPrefs(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = prefs.getBoolean(ENABLED, false)
        set(value) = prefs.edit(commit = true) { putBoolean(ENABLED, value) }

    var lastShownAt: Long
        get() = prefs.getLong(LAST, 0)
        set(value) = prefs.edit(commit = true) { putLong(LAST, value) }

    /** SAFE total of the last scan ("ready to clean"); 0 when unknown. */
    var lastJunkBytes: Long
        get() = prefs.getLong(JUNK, 0)
        set(value) = prefs.edit(commit = true) { putLong(JUNK, value) }

    private companion object {
        const val ENABLED = "enabled"
        const val LAST = "lastShownAt"
        const val JUNK = "lastJunkBytes"
    }
}

/** Daily check (WorkManager): posts the reminder when [ReminderPolicy] says so. Nothing else. */
class SpaceReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as KeepClearApplication
        val prefs = app.reminderPrefs
        val stat = StatFs(app.filesDir.absolutePath)
        val now = System.currentTimeMillis()
        if (!ReminderPolicy.shouldRemind(prefs.enabled, stat.availableBytes, stat.totalBytes, prefs.lastShownAt, now)) {
            return Result.success()
        }
        if (!MoveNotifications.allowed(app)) return Result.success()
        SpaceReminder.show(app, stat.availableBytes, prefs.lastJunkBytes)
        prefs.lastShownAt = now
        return Result.success()
    }
}

object SpaceReminder {
    private const val CHANNEL = "space"
    private const val ID = 77
    private const val WORK = "space-reminder"

    /** Turned on in Settings: check once a day. Turned off: stop checking. */
    fun schedule(context: Context, on: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (on) {
            wm.enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SpaceReminderWorker>(1, TimeUnit.DAYS).build(),
            )
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }

    /** The text, also used by tests: exact numbers, no fear words (product rules). */
    fun text(context: Context, freeBytes: Long, junkBytes: Long, locale: Locale): String {
        val free = ByteSize.format(freeBytes, locale)
        return if (junkBytes > 0) {
            context.getString(R.string.reminder_text_junk, free, ByteSize.format(junkBytes, locale))
        } else {
            context.getString(R.string.reminder_text, free)
        }
    }

    fun show(context: Context, freeBytes: Long, junkBytes: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.reminder_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val locale = context.resources.configuration.locales[0]
        nm.notify(
            ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(text(context, freeBytes, junkBytes, locale))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
