// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owner brief H2 item 8 (PR H2b): a one-by-one session survives leaving the screen and the app being closed
 * ("להמשיך מאיפה שעצרתם"). One small versioned text per group; the position is not stored separately because it is
 * derived: the first item of the group without a valid decision ([SwipeSession.next]).
 *
 * Format, version 1 (MIG-3: versioned; MIG-2: the choice is stored by its enum name, never renamed):
 * ```
 * keepclear-swipe
 * v=1
 * key=<GroupKey>
 * D<TAB><KEEP|DELETE|SKIP><TAB><size><TAB><mtime millis><TAB><path>
 * ```
 * The path is the last field, so a tab inside it is kept; a path with a line break is never written (its decision
 * is simply not resumed). A file with another header or version, or a line that doesn't parse, is ignored: the
 * worst case is that a decision has to be made again, never that one moves to another file.
 */
object SwipeSessionCodec {
    const val HEADER = "keepclear-swipe"
    const val VERSION = 1

    fun encode(key: String, decisions: List<SwipeDecision>): String = buildString {
        append(HEADER).append('\n')
        append("v=").append(VERSION).append('\n')
        append("key=").append(key).append('\n')
        for (d in decisions) {
            if ('\n' in d.path || '\r' in d.path) continue
            append("D\t").append(d.choice.name).append('\t').append(d.size).append('\t')
                .append(d.mtimeMillis).append('\t').append(d.path).append('\n')
        }
    }

    /** The group key and its decisions, or null when [text] isn't a version-1 session. */
    fun decode(text: String): Pair<String, List<SwipeDecision>>? {
        val lines = text.split('\n')
        if (lines.size < 3 || lines[0] != HEADER || lines[1] != "v=$VERSION" || !lines[2].startsWith("key=")) {
            return null
        }
        val key = lines[2].removePrefix("key=").takeIf { it.isNotEmpty() } ?: return null
        val seen = HashSet<String>()
        val decisions = lines.drop(3).mapNotNull { line ->
            val parts = line.split('\t', limit = 5)
            if (parts.size != 5 || parts[0] != "D") return@mapNotNull null
            val choice = SwipeChoice.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            val size = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val mtime = parts[3].toLongOrNull() ?: return@mapNotNull null
            val path = parts[4].takeIf { it.startsWith("/") } ?: return@mapNotNull null
            // One decision per file: a second line for the same path is ignored.
            if (!seen.add(path)) return@mapNotNull null
            SwipeDecision(path, choice, size, mtime)
        }
        return key to decisions
    }
}

/**
 * Where [SwipeSessionCodec] texts live: one entry per group in KeepClear's own `swipe_sessions` settings file
 * (app data is never in a cloud backup or device transfer: `data_extraction_rules.xml`). Read and written only off
 * the main thread (a long session is ~200 kB, more than C60's small settings). [SwipeSessionStore.Backend] keeps it
 * testable on the JVM; nothing here deletes or renames a file (CLAUDE.md safety rule 3).
 */
class SwipeSessionStore(private val backend: Backend) {
    /** The few operations the store needs; Android: [SharedPreferencesBackend]. */
    interface Backend {
        fun all(): Map<String, String>

        fun put(key: String, value: String)

        fun remove(key: String)
    }

    fun loadAll(): Map<String, List<SwipeDecision>> = backend.all().mapNotNull { (entry, text) ->
        val group = entry.removePrefix(PREFIX).takeIf { entry.startsWith(PREFIX) } ?: return@mapNotNull null
        // A session is read only under its own group (the key inside must be the entry's).
        runCatching { SwipeSessionCodec.decode(text) }.getOrNull()
            ?.takeIf { (key, decisions) -> key == group && decisions.isNotEmpty() }
    }.toMap()

    /** Saves [decisions] for group [key]; an empty list removes the session. */
    fun save(key: String, decisions: List<SwipeDecision>) {
        if (decisions.isEmpty()) clear(key) else backend.put(PREFIX + key, SwipeSessionCodec.encode(key, decisions))
    }

    fun clear(key: String) = backend.remove(PREFIX + key)

    /** Android: KeepClear's own settings file, written with `commit` (this runs on a background thread). */
    class SharedPreferencesBackend(private val prefs: android.content.SharedPreferences) : Backend {
        override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) ->
            (v as? String)?.let { k to it }
        }.toMap()

        override fun put(key: String, value: String) {
            prefs.edit().putString(key, value).commit()
        }

        override fun remove(key: String) {
            prefs.edit().remove(key).commit()
        }
    }

    /** Tests: in memory. */
    class MapBackend(val map: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()) : Backend {
        override fun all(): Map<String, String> = HashMap(map)

        override fun put(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    companion object {
        const val PREFS = "swipe_sessions"
        private const val PREFIX = "session:"
    }
}

/**
 * Owner brief H2 item 8: "להחליף את כיווני ההחלקה" (off: a swipe to the left deletes, C148) and whether the
 * one-time direction hint was seen. Two booleans in KeepClear's own settings file (C60).
 */
class SwipeSettings(context: Context) {
    private val prefs = MainThreadPolicy.settings { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val _swapped = MutableStateFlow(MainThreadPolicy.settings { prefs.getBoolean(KEY_SWAPPED, false) })
    private val _hintSeen = MutableStateFlow(MainThreadPolicy.settings { prefs.getBoolean(KEY_HINT_SEEN, false) })
    val swapped: StateFlow<Boolean> = _swapped
    val hintSeen: StateFlow<Boolean> = _hintSeen

    fun setSwapped(value: Boolean) {
        MainThreadPolicy.settings { prefs.edit { putBoolean(KEY_SWAPPED, value) } }
        _swapped.value = value
    }

    fun markHintSeen() {
        if (_hintSeen.value) return
        MainThreadPolicy.settings { prefs.edit { putBoolean(KEY_HINT_SEEN, true) } }
        _hintSeen.value = true
    }

    companion object {
        const val PREFS = "swipe"
        const val KEY_SWAPPED = "swapDirections"
        const val KEY_HINT_SEEN = "directionHintSeen"
    }
}

/** What the swipe screen needs from Settings. The default (previews, tests): not swapped, hint already seen. */
data class SwipePrefs(
    val swapped: Boolean = false,
    val hintSeen: Boolean = true,
    val onHintSeen: () -> Unit = {},
)

val LocalSwipePrefs = staticCompositionLocalOf { SwipePrefs() }

/** Owner brief H2 item 8: the feedback the hand feels. Keep → confirm, Delete → reject, the threshold → a tick. */
enum class SwipeHaptic { KEEP, DELETE, THRESHOLD }

/** The haptics hook; tests provide a fake through [LocalSwipeHaptics]. */
fun interface SwipeHaptics {
    fun perform(kind: SwipeHaptic)

    companion object {
        /**
         * Android's constant for [kind] on API [sdk]. CONFIRM and REJECT exist from API 30 (our minSdk);
         * GESTURE_THRESHOLD_ACTIVATE only from API 34, so older phones get the clock tick.
         */
        fun constantFor(kind: SwipeHaptic, sdk: Int = Build.VERSION.SDK_INT): Int = when (kind) {
            SwipeHaptic.KEEP -> HapticFeedbackConstants.CONFIRM

            SwipeHaptic.DELETE -> HapticFeedbackConstants.REJECT

            SwipeHaptic.THRESHOLD -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
        }

        /**
         * Through the view, without `FLAG_IGNORE_GLOBAL_SETTING`: when "Touch feedback" is off in Android's settings
         * (or the view has haptics off) nothing is felt.
         */
        fun of(view: View) = SwipeHaptics { kind ->
            if (view.isHapticFeedbackEnabled) view.performHapticFeedback(constantFor(kind))
        }
    }
}

val LocalSwipeHaptics = staticCompositionLocalOf<SwipeHaptics?> { null }

@Composable
internal fun rememberSwipeHaptics(): SwipeHaptics {
    val provided = LocalSwipeHaptics.current
    val view = LocalView.current
    return provided ?: remember(view) { SwipeHaptics.of(view) }
}
