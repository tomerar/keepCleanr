// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import io.github.tomerar.keepclear.core.i18n.R

/**
 * Owner brief H4 item 4: every notification channel KeepClear has, with its name in the app's language. The ids are
 * stored by Android and never renamed (renaming would make a new channel and lose the user's choices for the old one).
 * [registerAll] runs at every start and whenever the language changes, so Android's settings show the names in
 * the language KeepClear speaks.
 */
object NotificationChannels {
    /** The copy in progress, the free and the checks (silent, ongoing). */
    const val BACKUP_PROGRESS = "backup"

    /** "Backup finished" and "an update paused the backup" (separate, so they can be turned off on their own). */
    const val BACKUP_RESULTS = "backup-results"

    /** SET-3: "Remind me when space is low". */
    const val SPACE = "space"

    /** Owner brief H1 item 11: "Remind me to check the backup". */
    const val CHECK_REMINDER = "check-reminder"

    data class Channel(
        val id: String,
        @param:StringRes val name: Int,
        val importance: Int,
    )

    val all: List<Channel> = listOf(
        Channel(BACKUP_PROGRESS, R.string.move_channel, NotificationManager.IMPORTANCE_LOW),
        Channel(BACKUP_RESULTS, R.string.backup_results_channel, NotificationManager.IMPORTANCE_DEFAULT),
        Channel(SPACE, R.string.reminder_channel, NotificationManager.IMPORTANCE_LOW),
        Channel(CHECK_REMINDER, R.string.check_reminder_channel, NotificationManager.IMPORTANCE_LOW),
    )

    /** Creates the channels, or renames them in [context]'s language (Android keeps the user's settings). */
    fun registerAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Review of PR #60: on Android 11–12 the application context keeps the language of process start; the names
        // come from the language the user chose now (AppLanguage), so a background worker never renames them back.
        val named = AppLanguage.wrap(context)
        all.forEach { c ->
            nm.createNotificationChannel(NotificationChannel(c.id, named.getString(c.name), c.importance))
        }
    }

    /** Android's settings page of one channel (Settings › Notifications rows). */
    fun settingsIntent(context: Context, id: String): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, id)
}
