// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashIndex
import io.github.tomerar.keepclear.engine.trash.TrashState

/** Trash index (ADR 0006): the source of truth; sidecars are only a recovery aid (SEC-1). */
@Entity(tableName = "trash_entry", indices = [Index(value = ["trashedPath"], unique = true)])
data class TrashEntryEntity(
    @PrimaryKey val id: String,
    val originalPath: String,
    val trashedPath: String,
    val volumeRoot: String,
    val size: Long,
    val mtimeMillis: Long,
    val sha256: String?,
    val ruleId: String?,
    val reason: String,
    val deletedAt: Long,
    val expiresAt: Long,
    val deletedAtElapsed: Long,
    val bootId: String,
    val state: String,
    val trusted: Boolean,
    val verifiedCopy: String?,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val deletedAtUptime: Long? = null,
    /** Witness paths, one per line (owner decision 1.3). */
    @androidx.room.ColumnInfo(defaultValue = "NULL") val witnessPaths: String? = null,
)

@Dao
interface TrashDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: TrashEntryEntity)

    @Query("SELECT * FROM trash_entry WHERE id = :id")
    suspend fun get(id: String): TrashEntryEntity?

    @Query("SELECT * FROM trash_entry WHERE trashedPath = :path")
    suspend fun byTrashedPath(path: String): TrashEntryEntity?

    @Query("SELECT * FROM trash_entry ORDER BY deletedAt DESC")
    suspend fun all(): List<TrashEntryEntity>

    @Query("DELETE FROM trash_entry WHERE id = :id")
    suspend fun remove(id: String)
}

class RoomTrashIndex(private val dao: TrashDao) : TrashIndex {
    override suspend fun put(entry: TrashEntry) = dao.put(entry.toEntity())

    override suspend fun get(id: String) = dao.get(id)?.toEntry()

    override suspend fun byTrashedPath(path: String) = dao.byTrashedPath(path)?.toEntry()

    override suspend fun all() = dao.all().map { it.toEntry() }

    override suspend fun remove(id: String) = dao.remove(id)

    private fun TrashEntry.toEntity() = TrashEntryEntity(
        id,
        originalPath,
        trashedPath,
        volumeRoot,
        size,
        mtimeMillis,
        sha256,
        ruleId,
        reason,
        deletedAt,
        expiresAt,
        deletedAtElapsed,
        bootId,
        state.name,
        trusted,
        verifiedCopy,
        deletedAtUptime,
        witnessPaths.takeIf { it.isNotEmpty() }?.joinToString("\n"),
    )

    private fun TrashEntryEntity.toEntry() = TrashEntry(
        id,
        originalPath,
        trashedPath,
        volumeRoot,
        size,
        mtimeMillis,
        sha256,
        ruleId,
        reason,
        deletedAt,
        expiresAt,
        deletedAtElapsed,
        bootId,
        TrashState.valueOf(state),
        trusted,
        verifiedCopy,
        deletedAtUptime,
        witnessPaths?.split('\n')?.filter { it.isNotEmpty() }.orEmpty(),
    )
}
