// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.storage.StorageManager
import io.github.tomerar.keepclear.engine.classify.AppInfo

/**
 * M13: installed apps with sizes and last use, read on this phone only (ONB-7). Needs Usage access; without it
 * [load] returns null and the UI explains how to turn it on. Launchable apps only (package visibility).
 */
class AppsSource(private val context: Context) {
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (android.os.Build.VERSION.SDK_INT >= 35) {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun load(now: Long = System.currentTimeMillis()): List<AppInfo>? {
        if (!hasUsageAccess()) return null
        val pm = context.packageManager
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val usage = context.getSystemService(UsageStatsManager::class.java)
            .queryAndAggregateUsageStats(now - YEAR_MILLIS, now)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != context.packageName }
            .mapNotNull { pkg ->
                runCatching {
                    val info = pm.getApplicationInfo(pkg, 0)
                    val s = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                    AppInfo(
                        pkg = pkg,
                        label = pm.getApplicationLabel(info).toString(),
                        appBytes = s.appBytes,
                        dataBytes = s.dataBytes,
                        cacheBytes = s.cacheBytes,
                        lastUsedMillis = usage[pkg]?.lastTimeUsed?.takeIf { it > 0 },
                        installedMillis = pm.getPackageInfo(pkg, 0).firstInstallTime,
                    )
                }.getOrNull()
            }
    }

    private companion object {
        const val YEAR_MILLIS = 366L * 24 * 3_600_000
    }
}
