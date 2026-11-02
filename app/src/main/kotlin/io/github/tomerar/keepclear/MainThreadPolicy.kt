// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.os.StrictMode

/**
 * NFR-1, owner review E1: the main thread never reads or writes storage. Debug builds (the test APK and the
 * instrumented tests) turn on StrictMode with `penaltyDeath`, so a disk read or write on the main thread crashes
 * the test build at once instead of freezing the phone later (ANR). Release builds don't enable it.
 */
object MainThreadPolicy {
    fun enable() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .penaltyDeath()
                .build(),
        )
    }

    /**
     * The one allowance (C60): KeepClear's own small settings files (SharedPreferences, a few hundred bytes each).
     * Android reads such a file on first use and checks its folder exists, and a few switches are written
     * synchronously on purpose (test mode must survive an immediate process kill). Only settings access goes in
     * [block]: never a user file, the database, a scan or a hash.
     */
    inline fun <T> settings(block: () -> T): T {
        val old = StrictMode.allowThreadDiskWrites() // also allows reads
        try {
            return block()
        } finally {
            StrictMode.setThreadPolicy(old)
        }
    }
}
