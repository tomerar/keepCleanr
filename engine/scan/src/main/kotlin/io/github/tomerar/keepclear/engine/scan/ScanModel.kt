// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.scan

import io.github.tomerar.keepclear.core.storage.KPath

/** Where an item was seen. Stored by name (MIG-2). */
enum class Source { FILE_WALK, MEDIA_STORE }

/** One file seen by a scan (design.md §3.2). Directories are not items; they're implied by paths. */
data class ScanItem(
    val ref: KPath,
    /** Absolute path for display and rule matching. */
    val path: String,
    val size: Long,
    val mtimeMillis: Long,
    val identity: String? = null,
    val mime: String? = null,
    val mediaId: Long? = null,
    val isFavorite: Boolean = false,
    /** Already in a MediaStore trash (another app's "Recently deleted"). */
    val isTrashed: Boolean = false,
    val source: Source = Source.FILE_WALK,
)

/** A MediaStore row (port implemented by `core:storage-media` on Android). */
data class MediaRow(
    val id: Long,
    val volume: String,
    val path: String,
    val size: Long,
    val mtimeMillis: Long,
    val mime: String?,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
    val generation: Long,
)

/** Reads MediaStore. [sinceGeneration] enables incremental queries (SCAN-5). */
fun interface MediaSource {
    suspend fun query(sinceGeneration: Long?): List<MediaRow>
}

sealed interface ScanEvent {
    data class Batch(val items: List<ScanItem>) : ScanEvent

    data class Progress(val directories: Int, val files: Int, val bytes: Long) : ScanEvent

    /** A directory or file that couldn't be read; never classified (E-STO-6). */
    data class Unreadable(val path: String, val reason: String) : ScanEvent

    /** Volume went away mid-scan (E-STO-1): its items are missing from this scan. */
    data class VolumeLost(val root: String) : ScanEvent

    /**
     * Terminal event. [complete] is false when access was lost (E-PERM-1); results so far must be
     * shown as incomplete.
     */
    data class Done(
        val complete: Boolean,
        val files: Int,
        val bytes: Long,
        val skippedSymlinks: Int,
        val skippedRestricted: List<String>,
        val maxGeneration: Long?,
    ) : ScanEvent
}
