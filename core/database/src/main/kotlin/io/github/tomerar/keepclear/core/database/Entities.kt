// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Index of the last scan (design.md §4). Enum-like columns are stored by name (MIG-2).
 * [refKind]/[refValue] persist the KPath: "local" + absolute path, or "media" + "volume:id".
 */
@Entity(tableName = "scan_item", indices = [Index("scanRunId")])
data class ScanItemEntity(
    @PrimaryKey val path: String,
    val refKind: String,
    val refValue: String,
    val size: Long,
    val mtimeMillis: Long,
    val identity: String?,
    val mime: String?,
    val mediaId: Long?,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
    val source: String,
    val scanRunId: Long,
)

@Entity(tableName = "scan_run")
data class ScanRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val complete: Boolean = false,
    val files: Int = 0,
    val bytes: Long = 0,
    val mediaGeneration: Long? = null,
)

/** SHA-256 / prehash cache keyed by path and invalidated by size, mtime or identity (design.md §4). */
@Entity(tableName = "hash_cache")
data class HashCacheEntity(
    @PrimaryKey val path: String,
    val size: Long,
    val mtimeMillis: Long,
    val identity: String?,
    val prehash: String?,
    val sha256: String?,
)
