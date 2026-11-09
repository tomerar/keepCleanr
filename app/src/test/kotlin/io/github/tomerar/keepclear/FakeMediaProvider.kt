// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import org.robolectric.Robolectric

/**
 * G4b: a stand-in for Android's media provider on Robolectric, answering only what KeepClear's executor and media
 * handler read about one row: name, size, modification time, generation, the trashed flag and the media type. It
 * never changes a row; a test changes [rows] to play another app or Android's own trash.
 */
class FakeMediaProvider : ContentProvider() {
    data class Row(
        val name: String,
        val size: Long,
        val mtimeMillis: Long,
        val generation: Long = 5,
        val trashed: Boolean = false,
    )

    val rows = java.util.concurrent.ConcurrentHashMap<Long, Row>()

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, args: Bundle?, signal: CancellationSignal?): Cursor? {
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        val columns = projection ?: return null
        val cursor = MatrixCursor(columns)
        val row = rows[id] ?: return cursor
        cursor.addRow(
            columns.map<String, Any?> { c ->
                when (c) {
                    MediaStore.MediaColumns.DISPLAY_NAME -> row.name
                    MediaStore.MediaColumns.SIZE -> row.size
                    MediaStore.MediaColumns.DATE_MODIFIED -> row.mtimeMillis / 1000
                    MediaStore.MediaColumns.GENERATION_MODIFIED -> row.generation
                    MediaStore.MediaColumns.IS_TRASHED -> if (row.trashed) 1 else 0
                    MediaStore.Files.FileColumns.MEDIA_TYPE -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                    else -> null
                }
            }.toTypedArray(),
        )
        return cursor
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? =
        query(uri, p, null as Bundle?, null)

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun install(): FakeMediaProvider = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
    }
}
