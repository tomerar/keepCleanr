// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.engine.move.PauseReason
import java.util.Locale

/** One past end of KeepClear's process, as Android recorded it ([android.app.ApplicationExitInfo]). */
data class ProcessExit(val timestamp: Long, val reason: Int)

/** Where past process ends come from: Android in the app, a fake in tests. */
fun interface ProcessExitSource {
    fun exits(): List<ProcessExit>
}

/** Android's record of KeepClear's own past exits (API 30, no permission). */
class AndroidProcessExitSource(private val context: Context) : ProcessExitSource {
    override fun exits(): List<ProcessExit> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching { am.getHistoricalProcessExitReasons(null, 0, MAX) }.getOrDefault(emptyList())
            .map { ProcessExit(it.timestamp, it.reason) }
    }

    private companion object {
        const val MAX = 16
    }
}

/** Why a backup found unfinished stopped. */
enum class StopCause {
    /** Android (or the phone maker's battery saver) ended KeepClear in the background. */
    SYSTEM,

    /** KeepClear crashed or froze: Home's crash card (PR H3) says so. */
    CRASH,

    /** An update replaced KeepClear. */
    UPDATE,

    /** Anything else, or no record. */
    OTHER,
}

/**
 * Owner brief H4 item 5 (replaces C125's guess): a backup found still marked running with no service gets its pause
 * reason from Android's own record of how the process ended. Only a stop by the system is "the phone stopped the
 * backup to save battery"; a crash (Home's crash card says so) or an update is "interrupted".
 */
object KillReasons {
    // ApplicationExitInfo.REASON_* (the newer ones are named here so the mapping reads the same on every API level).
    const val EXIT_SELF = 1
    const val SIGNALED = 2
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val EXCESSIVE_RESOURCE_USAGE = 9
    const val USER_REQUESTED = 10
    const val OTHER = 13
    const val FREEZER = 14
    const val PACKAGE_UPDATED = 16

    /**
     * REASON_USER_REQUESTED counts as the system: phone makers' battery savers (OnePlus, Xiaomi, Oppo …) end apps by
     * a force stop that Android records as "user requested"; a stop the user chose in KeepClear is recorded by
     * KeepClear itself (PAUSED_BY_USER, CANCELLED) and never reaches this (C172).
     */
    fun cause(reason: Int): StopCause = when (reason) {
        LOW_MEMORY, EXCESSIVE_RESOURCE_USAGE, FREEZER, OTHER, SIGNALED, USER_REQUESTED -> StopCause.SYSTEM
        CRASH, CRASH_NATIVE, ANR -> StopCause.CRASH
        PACKAGE_UPDATED -> StopCause.UPDATE
        else -> StopCause.OTHER
    }

    /** The newest process end at or after [since] (the job's start), or null when Android kept none. */
    fun latest(exits: List<ProcessExit>, since: Long): ProcessExit? =
        exits.filter { it.timestamp >= since }.maxByOrNull { it.timestamp }

    fun pauseReason(exits: List<ProcessExit>, since: Long): PauseReason =
        when (latest(exits, since)?.let { cause(it.reason) }) {
            StopCause.SYSTEM -> PauseReason.STOPPED_BY_PHONE
            else -> PauseReason.INTERRUPTED
        }
}

/**
 * Owner brief H4 item 5: what to change on this phone so the backup keeps running. Data: one row per maker
 * (matched on Build.MANUFACTURER, lower case), its steps and its page on dontkillmyapp.com (opened in the browser;
 * KeepClear itself has no internet access).
 */
data class MakerSteps(
    val maker: String,
    @param:StringRes val steps: Int,
    val guideUrl: String,
)

object BatterySteps {
    private const val GUIDE = "https://dontkillmyapp.com/"

    private class Row(
        val names: Set<String>,
        @param:StringRes val steps: Int,
        val slug: String,
    )

    private val rows = listOf(
        Row(setOf("oneplus"), R.string.battery_steps_oneplus, "oneplus"),
        Row(setOf("samsung"), R.string.battery_steps_samsung, "samsung"),
        Row(setOf("xiaomi", "redmi", "poco"), R.string.battery_steps_xiaomi, "xiaomi"),
        Row(setOf("huawei", "honor"), R.string.battery_steps_huawei, "huawei"),
        Row(setOf("oppo"), R.string.battery_steps_oppo, "oppo"),
        Row(setOf("realme"), R.string.battery_steps_oppo, "realme"),
        Row(setOf("vivo", "iqoo"), R.string.battery_steps_vivo, "vivo"),
    )

    fun forMaker(manufacturer: String?): MakerSteps {
        val name = manufacturer.orEmpty().trim().lowercase(Locale.ROOT)
        val row = rows.firstOrNull { name in it.names }
        return if (row == null) {
            MakerSteps("generic", R.string.battery_steps_generic, GUIDE)
        } else {
            MakerSteps(row.slug, row.steps, GUIDE + row.slug)
        }
    }

    /**
     * Android's list of battery-optimised apps, where the user turns it off for KeepClear. Not the one-app request
     * (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS): that needs a permission Play restricts.
     */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun guideIntent(steps: MakerSteps): Intent =
        Intent(Intent.ACTION_VIEW, steps.guideUrl.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
}

/** Owner brief H4 item 5: "last backup run" for Settings › Troubleshooting (one number in KeepClear's settings). */
class BackupRunLog(private val prefs: android.content.SharedPreferences) {
    val lastRunAt: Long? get() = MainThreadPolicy.settings { prefs.getLong(KEY, 0L).takeIf { it > 0 } }

    fun ran(at: Long) = MainThreadPolicy.settings { prefs.edit { putLong(KEY, at) } }

    companion object {
        const val PREFS = "backup-runs"
        private const val KEY = "lastRunAt"
    }
}
