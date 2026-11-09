// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

/** Outcome of one gallery cache write. Anything but [STORED] means "not cached": the record is recomputed later. */
enum class StoreWrite {
    STORED,

    /** LOW-1/LOW-2, E-G15: below 500 MB free (LIGHT) or 100 MB (MINIMAL) no gallery state is written. */
    PAUSED_LOW_SPACE,

    /** The record belongs to an older permission epoch than the store's current one (an old run never wins). */
    STALE_EPOCH,

    /** Invalid facts or a field too long for the bounded row; nothing is written. */
    REJECTED,

    /** E-G14: the database refused the write (for example SQLiteFull/ENOSPC); only this write was rolled back. */
    FAILED,
}

/**
 * Narrow, recomputable feature cache port (GAL-11/12/13/26). The grouping engine never calls it: a later
 * coordinator reads compatible records and checkpoints only completed ones. A miss (null) always means
 * "analyze again", never "checked". Nothing here grants authority over a source file.
 *
 * - [get] returns a record only when the source key, the full source version (size, time, revision, or the
 *   observation when no revision is known; E-G07), the [FeatureVersion] (E-G10) and [permissionEpoch] all match.
 * - [put] stores one completed record atomically, or nothing.
 * - [beginEpoch] records the current permission epoch and drops every record of another epoch; [get] already
 *   hides them because the caller passes its live epoch (GAL-12: a reduced scope is hidden immediately).
 * - [clear] removes only gallery cache state (GAL-13), never trash, backup, move, scan, hash or kept data.
 */
interface GalleryFeatureStore {
    suspend fun get(source: SourceVersion, version: FeatureVersion, permissionEpoch: Long): FeatureRecord?

    suspend fun put(record: FeatureRecord, permissionEpoch: Long): StoreWrite

    suspend fun beginEpoch(permissionEpoch: Long): StoreWrite

    suspend fun invalidate(key: SourceKey): StoreWrite

    suspend fun clear(): StoreWrite
}
