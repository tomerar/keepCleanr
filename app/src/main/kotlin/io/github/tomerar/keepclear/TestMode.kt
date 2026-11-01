// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import io.github.tomerar.keepclear.core.safety.OpResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test mode (SAFETY-9 dry-run). **On by default in debug/test builds**, so the test APK the owner installs
 * can't delete, trash, restore, purge or clear anything until test mode is turned off on purpose
 * (Settings › Advanced, two confirmations). Every action that would change files runs through the executor in
 * dry-run, is logged, and the UI says what *would* have happened. Release builds start with it off.
 *
 * Copying to a backup drive still works in test mode: it reads the phone and writes only new files on the drive;
 * freeing the originals afterwards is simulated like every other deletion.
 */
class TestMode(context: Context, defaultOn: Boolean = BuildConfig.DEBUG) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _on = MutableStateFlow(prefs.getBoolean(KEY, defaultOn))
    val on: StateFlow<Boolean> = _on

    val isOn: Boolean get() = _on.value

    fun turnOn() = set(true)

    /** Only from Settings › Advanced after two confirmations (see SettingsScreen). */
    fun turnOff() = set(false)

    private fun set(value: Boolean) {
        // Written synchronously: the switch must survive an immediate process kill.
        prefs.edit(commit = true) { putBoolean(KEY, value) }
        _on.value = value
        Log.i(TAG, "test mode ${if (value) "on" else "off"}")
    }

    companion object {
        const val PREFS = "safety"
        const val KEY = "testMode"
        const val TAG = "KeepClear-TestMode"

        /**
         * What an executor reports in test mode: logged, never performed. Only the kind and size: logcat is
         * readable by other tools, and file names can be private (review PR B).
         */
        fun log(result: OpResult) {
            logLine(result)?.let { Log.i(TAG, it) }
        }

        fun logLine(result: OpResult): String? =
            if (result is OpResult.Simulated) "would ${result.op.kind} (${result.op.expectedSize} bytes)" else null

        /** Marker for "Android was not asked to clear caches" in [HomeUiState.trimResult]. */
        const val TRIM_SKIPPED = -1L
    }
}
