// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App database. MIG-1: explicit migrations only; never `fallbackToDestructiveMigration`.
 * Version history: 1 = M3 (scan index, hash cache); 2 = M6 (trash_entry, auto-migration 1→2).
 */
@Database(
    entities = [ScanItemEntity::class, ScanRunEntity::class, HashCacheEntity::class, TrashEntryEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class KeepClearDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    abstract fun hashCacheDao(): HashCacheDao

    abstract fun trashDao(): TrashDao

    companion object {
        const val NAME = "keepclear.db"

        /** Every migration ever shipped, in order. Tested by `MigrationTest`. */
        val MIGRATIONS = arrayOf<androidx.room.migration.Migration>()

        fun build(context: Context): KeepClearDatabase =
            Room.databaseBuilder(context, KeepClearDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
