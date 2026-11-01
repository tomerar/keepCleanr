// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.classify

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One installed app as Android reports it (M13, usage access). Sizes from `StorageStatsManager`. */
data class AppInfo(
    val pkg: String,
    val label: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    /** Last time the user had it in the foreground, or null when Android has no record. */
    val lastUsedMillis: Long?,
    val installedMillis: Long,
) {
    val totalBytes: Long get() = appBytes + dataBytes
}

/**
 * M13: apps by size, and the "not opened in about 6 months" bucket. Months are calendar months in the phone's
 * time zone ("about 7 months ago"), not 30-day blocks. An app installed less than [UNUSED_MONTHS] ago is never
 * "unused", even without a usage record: Android keeps usage history for a limited time.
 */
object AppsReport {
    const val UNUSED_MONTHS = 6L

    fun monthsSince(millis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (millis >= now) return 0
        val from = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().withDayOfMonth(1)
        val to = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
        return ChronoUnit.MONTHS.between(from, to)
    }

    fun isUnused(app: AppInfo, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (monthsSince(app.installedMillis, now, zone) < UNUSED_MONTHS) return false
        val last = app.lastUsedMillis ?: return true
        return monthsSince(last, now, zone) >= UNUSED_MONTHS
    }

    fun bySize(apps: List<AppInfo>): List<AppInfo> = apps.sortedByDescending { it.totalBytes }

    fun byCache(apps: List<AppInfo>): List<AppInfo> = apps.sortedByDescending { it.cacheBytes }

    fun unused(apps: List<AppInfo>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<AppInfo> =
        bySize(apps.filter { isUnused(it, now, zone) })
}
