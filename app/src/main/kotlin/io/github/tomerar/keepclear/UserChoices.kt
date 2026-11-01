// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.tomerar.keepclear.core.safety.CanonicalPath
import io.github.tomerar.keepclear.core.storage.KPath
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

        /** Folders that hold memories or other apps' data: never "always junk" (decided conservatively, C12). */
        private val neverJunk = setOf("dcim", "pictures", "movies", "music", "documents", "android")

        private fun isValid(path: String) = runCatching { KPath.Local(path) }.isSuccess &&
            CanonicalPath.of(path)?.startsWith("/storage/") == true

        /** Relative to the volume: `/storage/emulated/0/Download/Memes` → [download, memes]. */
        private fun relative(path: String): List<String>? {
            val segments = CanonicalPath.of(path)?.split('/')?.filter { it.isNotEmpty() } ?: return null
            return when {
                segments.size >= 3 && segments[0] == "storage" && segments[1] == "emulated" -> segments.drop(3)
                segments.size >= 2 && segments[0] == "storage" -> segments.drop(2)
                else -> null
            }
        }

        /** "Always junk" only for a folder at least two levels deep and outside memory/app folders. */
        fun canBeJunk(folder: String): Boolean {
            if (!isValid(folder)) return false
            val rel = relative(folder) ?: return false
            return rel.size >= 2 && rel.first() !in neverJunk && rel.none { it == ".keepcleartrash" }
        }
    }
}
