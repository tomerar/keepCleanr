// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema-7 rows in every table the gallery cache must never touch (GAL-13, T-G12): scan index, hash cache, trash
 * index, backup jobs and their per-file proofs (verified, confirmed, verification method). The same SQL is valid at
 * schema 7 and 8 because version 8 only adds gallery tables.
 */
internal object AuthoritativeRows {
    val tables = listOf("scan_run", "scan_item", "hash_cache", "trash_entry", "move_job", "move_file")

    fun insert(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO scan_run (id, startedAt, finishedAt, complete, files, bytes, mediaGeneration) " +
                "VALUES (1, 10, 20, 1, 2, 300, 42)",
        )
        db.execSQL(
            "INSERT INTO scan_item (path, refKind, refValue, size, mtimeMillis, identity, mime, mediaId, isFavorite, " +
                "isTrashed, source, scanRunId) VALUES ('/storage/emulated/0/DCIM/תמונה.jpg', 'media', " +
                "'external_primary:7', 100, 5000, NULL, 'image/jpeg', 7, 1, 0, 'MEDIA_STORE', 1)",
        )
        db.execSQL(
            "INSERT INTO hash_cache (path, size, mtimeMillis, identity, prehash, sha256) VALUES " +
                "('/storage/emulated/0/DCIM/תמונה.jpg', 100, 5000, 'inode:9', 'pre', '${"ab".repeat(32)}')",
        )
        db.execSQL(
            "INSERT INTO trash_entry (id, originalPath, trashedPath, volumeRoot, size, mtimeMillis, sha256, ruleId, " +
                "reason, deletedAt, expiresAt, deletedAtElapsed, bootId, state, trusted, verifiedCopy, " +
                "deletedAtUptime, witnessPaths) VALUES ('t1', '/storage/emulated/0/a.jpg', " +
                "'/storage/emulated/0/.KeepClearTrash/20261109/t1/a.jpg', '/storage/emulated/0', 1, 2, " +
                "'${"cd".repeat(32)}', 'camera', 'TRASH', 3, 4, 5, 'boot', 'ACTIVE', 1, 'drive:x', 6, '/w1\n/w2')",
        )
        db.execSQL(
            "INSERT INTO move_job (id, targetId, targetLabel, rootRel, createdAt, state, pauseReason, targetRef, " +
                "verifyBootId) VALUES ('j', 'usb', 'USB', 'KeepClear/Phone', 1, 'DONE', NULL, 'tree:x', 'boot')",
        )
        db.execSQL(
            "INSERT INTO move_file (jobId, fileIndex, source, sourcePath, sourceSize, sourceMtime, sourceIdentity, " +
                "relTarget, state, sourceSha256, attempts, failReason, verifiedAt, nameChanged, reusedExisting, " +
                "confirmedAt, verifiedBy, verifyBootId, verifyAttach) VALUES ('j', 0, " +
                "'local:/storage/emulated/0/a.jpg', " +
                "'/storage/emulated/0/a.jpg', 1, 2, NULL, 'KeepClear/Phone/a.jpg', 'VERIFIED', '${"ef".repeat(32)}', " +
                "1, NULL, 5, 0, 0, 7, 'READ_BACK', 'boot', 'attach')",
        )
    }

    /** Every row of every authoritative table, column by column, in a stable order. */
    fun dump(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> = tables.associateWith { table ->
        db.query("SELECT * FROM $table ORDER BY rowid").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add((0 until c.columnCount).map { c.getColumnName(it) + "=" + c.getString(it) })
                }
            }
        }
    }
}
