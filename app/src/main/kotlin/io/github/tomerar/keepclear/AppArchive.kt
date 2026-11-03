// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Owner brief H4 item 8: on Android 15+ an app can be archived instead of uninstalled: Android removes the app but
 * keeps its data and icon, and the store that installed it brings it back on a tap. KeepClear only asks Android
 * ([PackageInstaller.requestArchive]); Android shows its own confirmation. Offered only where the app's installer
 * supports it ([android.content.pm.PackageManager.isAppArchivable]); otherwise the usual uninstall stays.
 */
object AppArchive {
    /** Whether to offer "Archive" for [pkg] ([archivable] asks Android; tests pass a fake). */
    fun offer(sdk: Int, archivable: () -> Boolean): Boolean =
        sdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM && runCatching(archivable).getOrDefault(false)

    fun canArchive(context: Context, pkg: String): Boolean = offer(Build.VERSION.SDK_INT) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && archivable(context, pkg)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun archivable(context: Context, pkg: String): Boolean = context.packageManager.isAppArchivable(pkg)

    /** Asks Android to archive [pkg]; Android answers to [ArchiveStatusReceiver] (its confirmation, or a failure). */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun request(context: Context, pkg: String) {
        // Mutable: Android adds the status and its confirmation to it. Explicit to our own, not exported receiver,
        // so nothing else can receive or send it (SEC-5 note in docs/progress.md, C178).
        val status = PendingIntent.getBroadcast(
            context,
            pkg.hashCode(),
            Intent(context, ArchiveStatusReceiver::class.java).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.packageManager.packageInstaller.requestArchive(pkg, status.intentSender)
    }
}

/** What to do with Android's answer to an archive request. */
object ArchiveStatus {
    sealed interface Next {
        /** Show Android's own confirmation. */
        data class Confirm(val intent: Intent) : Next

        data object Nothing : Next
    }

    /**
     * Only Android's own confirmation is opened: an activity of a system app. Anything else in the extras is
     * ignored (no intent redirection).
     */
    fun next(status: Int, confirm: Intent?, isSystemActivity: (Intent) -> Boolean): Next =
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && confirm != null && isSystemActivity(confirm)) {
            Next.Confirm(confirm)
        } else {
            Next.Nothing
        }
}

/** Receives Android's answer to [AppArchive.request] (not exported: only our own PendingIntent reaches it). */
class ArchiveStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        val next = ArchiveStatus.next(status, confirm) { candidate ->
            val resolved = context.packageManager.resolveActivity(candidate, 0)?.activityInfo?.applicationInfo
            resolved != null && (resolved.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }
        if (next is ArchiveStatus.Next.Confirm) {
            runCatching { context.startActivity(next.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
