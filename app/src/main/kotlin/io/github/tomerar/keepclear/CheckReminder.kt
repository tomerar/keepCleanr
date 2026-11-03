// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tomerar.keepclear.core.i18n.R
import java.util.concurrent.TimeUnit

/**
 * Owner brief H1 item 11 (Signal's pattern): an opt-in reminder, off by default, to check a backup again three months
 * after its last check ("Check backup", MOVE-15). It only reminds; tapping it opens the Backup tab with the check.
 * Nothing runs in the background besides this decision.
 */
object CheckReminderPolicy {
    private const val DAY = 86_400_000L
    const val DUE_MILLIS = 90 * DAY

    /** After the first reminder of a due period, again at most once a month until the next check. */
    const val REPEAT_MILLIS = 30 * DAY

    /**
     * True when the user turned the reminder on, a backup was checked at [lastCheckAt] (null: never), that was at
     * least three months ago, and this due period hasn't been reminded yet (or the last reminder is a month old).
     */
    fun shouldRemind(enabled: Boolean, lastCheckAt: Long?, lastShownAt: Long, now: Long): Boolean {
        if (!enabled || lastCheckAt == null || lastCheckAt <= 0) return false
        val due = lastCheckAt + DUE_MILLIS
        if (now < due) return false
        return lastShownAt < due || now - lastShownAt >= REPEAT_MILLIS
    }
}

/** The switch and when the reminder last showed, kept on the phone. */
class CheckReminderPrefs(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = MainThreadPolicy.settings { prefs.getBoolean(ENABLED, false) }
        set(value) = MainThreadPolicy.settings { prefs.edit(commit = true) { putBoolean(ENABLED, value) } }

    var lastShownAt: Long
        get() = prefs.getLong(LAST, 0)
        set(value) = prefs.edit(commit = true) { putLong(LAST, value) }

    private companion object {
        const val ENABLED = "enabled"
        const val LAST = "lastShownAt"
    }
}

/** The latest second check of any backup on this phone, and its job; null when no backup was ever checked. */
data class LastCheck(val jobId: String, val at: Long)

suspend fun lastCheck(store: io.github.tomerar.keepclear.core.database.RoomMoveJobStore): LastCheck? = store.jobs()
    .mapNotNull { job -> store.files(job.id).mapNotNull { it.confirmedAt }.maxOrNull()?.let { LastCheck(job.id, it) } }
    .maxByOrNull { it.at }

/** Daily (WorkManager): posts the reminder when [CheckReminderPolicy] says so. Nothing else. */
class CheckReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as KeepClearApplication
        val prefs = app.checkReminderPrefs
        if (!prefs.enabled) return Result.success()
        val last = runCatching { lastCheck(app.moveController.store) }.getOrNull()
        val now = System.currentTimeMillis()
        if (!CheckReminderPolicy.shouldRemind(true, last?.at, prefs.lastShownAt, now)) return Result.success()
        if (last == null || !MoveNotifications.allowed(app)) return Result.success()
        CheckReminder.show(app, last.jobId)
        prefs.lastShownAt = now
        return Result.success()
    }
}

object CheckReminder {
    private const val CHANNEL = NotificationChannels.CHECK_REMINDER
    private const val ID = 78
    private const val WORK = "check-reminder"

    /** The Backup tab opens with "check again" for this job (see [MainActivity]). */
    const val EXTRA_RECHECK = "io.github.tomerar.keepclear.RECHECK"

    /** Turned on in Settings: decide once a day. Turned off: stop. */
    fun schedule(context: Context, on: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (on) {
            wm.enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CheckReminderWorker>(1, TimeUnit.DAYS).build(),
            )
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }

    fun show(context: Context, jobId: String) {
        // Review of PR #60: the reminder's text in the language chosen in KeepClear (Android 11–12, AppLanguage).
        @Suppress("NAME_SHADOWING")
        val context = AppLanguage.wrap(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        // Owner brief H4 item 4: every channel, named in the app's language.
        NotificationChannels.registerAll(context)
        nm.notify(ID, build(context, jobId))
    }

    fun build(context: Context, jobId: String): android.app.Notification {
        val body = context.getString(R.string.check_reminder_text)
        // Explicit and immutable (SEC-5); the job id only selects which receipt the Backup tab offers to check.
        val open = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_RECHECK, jobId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(io.github.tomerar.keepclear.R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.move_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }
}
