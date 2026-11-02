// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.tomerar.keepclear.core.safety.CanonicalPath
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.JunkFolders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** CLS-7, SET-2: what the user decided about specific files and folders. */
data class Choices(val kept: Set<String> = emptySet(), val junk: Set<String> = emptySet())

/**
 * CLS-7 / SET-2: "This is important to me" (kept: never offered, PROTECTED at the choke point too) and "Always
 * treat this folder as junk". Stored on the phone only.
 */
class UserChoices(private val prefs: SharedPreferences) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Choices> = _state

    private fun read() = Choices(
        kept = prefs.getStringSet(KEPT, emptySet()).orEmpty().toSet(),
        junk = prefs.getStringSet(JUNK, emptySet()).orEmpty().toSet(),
    )

    private fun write(choices: Choices) {
        prefs.edit(commit = true) {
            putStringSet(KEPT, choices.kept)
            putStringSet(JUNK, choices.junk)
        }
        _state.value = choices
    }

    /** Keeps [path] (a file or folder). Keeping also removes the same folder from "always junk". */
    fun keep(path: String): Boolean {
        if (!isValid(path)) return false
        val c = _state.value
        if (c.kept.size >= MAX) return false
        write(c.copy(kept = c.kept + path, junk = c.junk - path))
        return true
    }

    fun alwaysJunk(folder: String): Boolean {
        if (!canBeJunk(folder)) return false
        val c = _state.value
        if (c.junk.size >= MAX) return false
        write(c.copy(junk = c.junk + folder))
        return true
    }

    fun removeKept(path: String) = write(_state.value.let { it.copy(kept = it.kept - path) })

    fun removeJunk(folder: String) = write(_state.value.let { it.copy(junk = it.junk - folder) })

    companion object {
        private const val KEPT = "kept"
        private const val JUNK = "junk"
        private const val MAX = 1_000

        private fun isValid(path: String) = runCatching { KPath.Local(path) }.isSuccess &&
            CanonicalPath.of(path)?.startsWith("/storage/") == true

        /**
         * "Always junk" only for a folder at least two levels deep and outside memory, app and messenger folders
         * (C12, C51). The engine owns the rule, so the classifier ignores anything this would refuse.
         */
        fun canBeJunk(folder: String): Boolean = isValid(folder) && JunkFolders.canBeJunk(folder)
    }
}
