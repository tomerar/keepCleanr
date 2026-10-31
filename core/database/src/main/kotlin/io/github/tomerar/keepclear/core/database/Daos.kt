// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface ScanDao {
    @Insert
    suspend fun insertRun(run: ScanRunEntity): Long

    @Query("UPDATE scan_run SET finishedAt = :finishedAt, complete = :complete, files = :files, bytes = :bytes, mediaGeneration = :generation WHERE id = :id")
    suspend fun finishRun(id: Long, finishedAt: Long, complete: Boolean, files: Int, bytes: Long, generation: Long?)

    @Query("SELECT * FROM scan_run WHERE complete = 1 ORDER BY id DESC LIMIT 1")
    suspend fun lastCompleteRun(): ScanRunEntity?

    @Upsert
    suspend fun upsertItems(items: List<ScanItemEntity>)

    /** After a complete scan: drop items that weren't seen in it. */
    @Query("DELETE FROM scan_item WHERE scanRunId != :runId")
    suspend fun deleteItemsNotIn(runId: Long): Int

    @Query("SELECT * FROM scan_item")
    suspend fun allItems(): List<ScanItemEntity>

    @Query("SELECT COUNT(*) FROM scan_item")
    suspend fun countItems(): Int

    @Query("SELECT COALESCE(SUM(size), 0) FROM scan_item")
    suspend fun totalBytes(): Long

    @Transaction
    suspend fun replaceStale(runId: Long): Int = deleteItemsNotIn(runId)
}

@Dao
interface HashCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: HashCacheEntity)

    @Query("SELECT * FROM hash_cache WHERE path = :path")
    suspend fun get(path: String): HashCacheEntity?

    @Query("DELETE FROM hash_cache WHERE path NOT IN (SELECT path FROM scan_item)")
    suspend fun pruneOrphans(): Int
}
