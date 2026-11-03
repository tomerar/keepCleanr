// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owner decision P1 (2026-11-03): "Keep the original in the 30-day trash too (frees no space)", **off by default**.
 * Off: freeing space after a backup that passed the second check deletes the originals for good (the copy is the
 * undo). On: they go to KeepClear's 30-day trash as before. The executor asks [keepInTrash] right before each original.
 */
class FreeSettings(context: Context) {
    private val prefs = MainThreadPolicy.settings { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val _keepInTrash = MutableStateFlow(MainThreadPolicy.settings { prefs.getBoolean(KEY, false) })
    val state: StateFlow<Boolean> = _keepInTrash

    val keepInTrash: Boolean get() = _keepInTrash.value

    fun setKeepInTrash(value: Boolean) {
        // Written synchronously, like test mode: the choice must hold even if the app is killed right after (C60).
        MainThreadPolicy.settings { prefs.edit(commit = true) { putBoolean(KEY, value) } }
        _keepInTrash.value = value
    }

    companion object {
        const val PREFS = "safety"
        const val KEY = "keepOriginalsInTrash"
    }
}
