// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owner brief H3 item 4: what KeepClear did ("מה KeepClear עשה"). The names are stored, so they are never renamed
 * (MIG-2/3); an unknown name in a newer file is skipped, never guessed.
 */
enum class HistoryKind {
    /** Cleaned (Clean now, a group, the swipe): to the trash, or deleted for good when the item rebuilds itself. */
    CLEANED,

    /** SAFE-5: Android trimmed other apps' temporary files (measured). */
    CACHES_TRIMMED,

    /** The user emptied (part of) KeepClear's trash or the gallery's bin: deleted for good. */
    TRASH_EMPTIED,

    /** The daily purge deleted trash items older than 30 days. */
    TRASH_EXPIRED,

    /** A backup copied and checked its files (a copy: nothing on the phone changed). */
    BACKED_UP,

    /** Owner decision P1: originals deleted for good after a backup that passed the second check. */
    FREED_AFTER_BACKUP,

    /** P1 with "keep the original in the 30-day trash too": the originals went to the trash (nothing freed yet). */
    ORIGINALS_TO_TRASH,

    /** Files restored from KeepClear's trash or the gallery's bin. */
    RESTORED_FROM_TRASH,

    /** Files restored from a backup (MOVE-17). */
    RESTORED_FROM_BACKUP,
}

/**
 * One line of the history. [freedBytes] is only space that really came back (deleted for good, a purge, a measured
 * cache trim, P1's free); a move to the trash frees nothing yet and counts when the trash is emptied.
 */
data class HistoryEvent(
    val at: Long,
    val kind: HistoryKind,
    val files: Int,
    val bytes: Long,
    val freedBytes: Long = 0,
)

data class HistoryState(val events: List<HistoryEvent> = emptyList(), val totalFreed: Long = 0)

/**
 * Owner brief H3 item 4 (C163): a small versioned text store, not a Room table: one file in KeepClear's private
 * storage (never in a cloud backup or device transfer: `allowBackup=false`), counts, sizes, times and kinds only,
 * never a path or a name. Format v1:
 *
 * ```
 * keepclear-history v=1
 * total=<bytes freed, ever>
 * <at>|<KIND>|<files>|<bytes>|<freed>
 * ```
 *
 * The newest [MAX_EVENTS] lines are kept; the running total is its own line, so dropping old lines never lowers it.
 * Not thread-safe by itself: [ActivityHistory] serialises access.
 */
object HistoryFormat {
    const val HEADER = "keepclear-history v=1"
    const val MAX_EVENTS = 500

    fun write(state: HistoryState): String = buildString {
        append(HEADER).append('\n')
        append("total=").append(state.totalFreed).append('\n')
        for (e in state.events) {
            append(e.at).append('|').append(e.kind.name).append('|').append(e.files).append('|')
                .append(e.bytes).append('|').append(e.freedBytes).append('\n')
        }
    }

    /** Reads [text]; a missing or unknown-version file is an empty history, a bad line is skipped. */
    fun read(text: String?): HistoryState {
        if (text == null) return HistoryState()
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return HistoryState()
        val total = lines.getOrNull(1)?.removePrefix("total=")?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        val events = lines.drop(2).mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 5) return@mapNotNull null
            val kind = HistoryKind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            HistoryEvent(
                at = parts[0].toLongOrNull() ?: return@mapNotNull null,
                kind = kind,
                files = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null,
                bytes = parts[3].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null,
                freedBytes = parts[4].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null,
            )
        }
        return HistoryState(events.take(MAX_EVENTS), total)
    }
}

/**
 * The history and its running total ("פיניתם עד היום 18.4GB"). [record] and [load] touch storage: call them off the
 * main thread (the app does, in its IO scope).
 */
class ActivityHistory(private val backend: Backend) {
    /** Where the text lives: a file in the app (AtomicFile), or memory in tests. */
    interface Backend {
        fun read(): String?

        fun write(text: String)
    }

    private val lock = Any()
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state

    @Volatile private var loaded = false

    fun load(): HistoryState = synchronized(lock) {
        if (!loaded) {
            _state.value = runCatching { HistoryFormat.read(backend.read()) }.getOrDefault(HistoryState())
            loaded = true
        }
        _state.value
    }

    /** Adds [events] (newest first in the list) and their freed bytes to the total. Empty: nothing is written. */
    fun record(events: List<HistoryEvent>) {
        val kept = events.filter { it.files > 0 || it.bytes > 0 || it.freedBytes > 0 }
        if (kept.isEmpty()) return
        synchronized(lock) {
            val now = load()
            val next = HistoryState(
                events = (kept.sortedByDescending { it.at } + now.events).take(HistoryFormat.MAX_EVENTS),
                totalFreed = now.totalFreed + kept.sumOf { it.freedBytes },
            )
            backend.write(HistoryFormat.write(next))
            _state.value = next
        }
    }
}

/**
 * Turns what really happened into history lines (owner brief H3 item 4). Only results that were done count: a test-mode
 * result (`OpResult.Simulated`) never reaches these functions, so test mode records nothing destructive (C164).
 */
object HistoryEvents {
    /** Ops the executor (or Android's own dialog) really carried out, grouped into one line per kind. */
    fun fromOps(done: List<PlannedOp>, at: Long): List<HistoryEvent> {
        if (done.isEmpty()) return emptyList()
        val out = mutableListOf<HistoryEvent>()
        val cleaned = done.filter { it.kind == OpKind.TRASH || it.kind == OpKind.DELETE_FOR_GOOD }
        if (cleaned.isNotEmpty()) {
            out += HistoryEvent(
                at,
                HistoryKind.CLEANED,
                cleaned.size,
                cleaned.sumOf { it.expectedSize },
                freedBytes = cleaned.filter { it.kind == OpKind.DELETE_FOR_GOOD }.sumOf { it.expectedSize },
            )
        }
        val purged = done.filter { it.kind == OpKind.PURGE }
        if (purged.isNotEmpty()) {
            val bytes = purged.sumOf { it.expectedSize }
            out += HistoryEvent(at, HistoryKind.TRASH_EMPTIED, purged.size, bytes, freedBytes = bytes)
        }
        val restored = done.filter { it.kind == OpKind.RESTORE || it.kind == OpKind.RESTORE_CHANGED }
        if (restored.isNotEmpty()) {
            out += HistoryEvent(at, HistoryKind.RESTORED_FROM_TRASH, restored.size, restored.sumOf { it.expectedSize })
        }
        return out
    }

    /** Owner decision P1: originals deleted for good free their size; moved to the trash, they free nothing yet. */
    fun fromFree(report: io.github.tomerar.keepclear.engine.move.FreeReport, at: Long): List<HistoryEvent> {
        val out = mutableListOf<HistoryEvent>()
        if (report.deletedForGood.isNotEmpty()) {
            val bytes = report.deletedForGood.sumOf { it.expectedSize }
            out += HistoryEvent(at, HistoryKind.FREED_AFTER_BACKUP, report.deletedForGood.size, bytes, bytes)
        }
        if (report.trashed.isNotEmpty()) {
            out += HistoryEvent(
                at,
                HistoryKind.ORIGINALS_TO_TRASH,
                report.trashed.size,
                report.trashed.sumOf { it.expectedSize },
            )
        }
        return out
    }

    /** MOVE-17: what was really written (a test-mode restore writes nothing and records nothing). */
    fun fromRestore(report: io.github.tomerar.keepclear.engine.restore.RestoreReport, at: Long): List<HistoryEvent> =
        if (report.simulated != null || report.restored.isEmpty()) {
            emptyList()
        } else {
            listOf(HistoryEvent(at, HistoryKind.RESTORED_FROM_BACKUP, report.restored.size, report.restoredBytes))
        }

    /** SAFE-5: the measured difference; nothing when the phone reported none. */
    fun fromTrim(freed: Long, at: Long): List<HistoryEvent> =
        if (freed > 0) listOf(HistoryEvent(at, HistoryKind.CACHES_TRIMMED, 0, freed, freed)) else emptyList()

    /** The daily purge of items older than 30 days (bytes only; the engine reports no count). */
    fun fromExpired(bytes: Long, at: Long): List<HistoryEvent> =
        if (bytes > 0) listOf(HistoryEvent(at, HistoryKind.TRASH_EXPIRED, 0, bytes, bytes)) else emptyList()

    /** A finished backup job: its checked copies (a copy changes nothing on the phone, so test mode records it too). */
    fun fromBackup(files: Int, bytes: Long, at: Long): List<HistoryEvent> =
        if (files > 0) listOf(HistoryEvent(at, HistoryKind.BACKED_UP, files, bytes)) else emptyList()
}

/** The app's history file, written atomically (a crash mid-write keeps the previous version). */
class AtomicFileBackend(file: java.io.File) : ActivityHistory.Backend {
    private val atomic = android.util.AtomicFile(file)

    override fun read(): String? = if (atomic.baseFile.exists()) {
        atomic.readFully().toString(Charsets.UTF_8)
    } else {
        null
    }

    override fun write(text: String) {
        atomic.baseFile.parentFile?.mkdirs()
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: java.io.IOException) {
            atomic.failWrite(out)
            throw e
        }
    }
}
