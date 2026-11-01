// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.media

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import io.github.tomerar.keepclear.core.storage.Capability
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Read-only gateway for MediaStore rows (M12): what the executor needs to re-check an item right before acting
 * (size, mtime, generation, whether it is in Android's trash). Writing goes through Android's consent requests
 * ([MediaConsentHandler]), never through this gateway.
 */
class MediaGateway(private val resolver: ContentResolver) : StorageGateway {
    override val capabilities: Set<Capability> = setOf(Capability.READ, Capability.STAT_MTIME, Capability.STAT_IDENTITY)

    private fun uri(row: KPath.Media) = ContentUris.withAppendedId(MediaStore.Files.getContentUri(row.volume), row.id)

    private fun row(path: KPath): KPath.Media =
        path as? KPath.Media ?: throw StorageException.Unsupported(path, "not a MediaStore row")

    override suspend fun stat(path: KPath): Stat? = withContext(Dispatchers.IO) {
        val row = row(path)
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.IS_TRASHED,
        )
        resolver.query(uri(row), projection, args, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            Stat(
                path = row,
                name = c.getString(0) ?: row.id.toString(),
                kind = Stat.Kind.FILE,
                size = c.getLong(1),
                mtimeMillis = c.getLong(2) * 1000,
                identity = identity(c.getLong(3), c.getInt(4) == 1),
            )
        }
    }

    // The stream is returned to the caller, which closes it (StorageGateway contract).
    @android.annotation.SuppressLint("Recycle")
    override suspend fun openRead(path: KPath): InputStream = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri(row(path))) ?: throw StorageException.NotFound(path)
    }

    override fun list(dir: KPath): Flow<Stat> = throw StorageException.Unsupported(
        dir,
        "MediaStore rows have no children",
    )

    override suspend fun create(dir: KPath, name: String): OutputStream =
        throw StorageException.Unsupported(dir, "read-only")

    override suspend fun mkdir(dir: KPath, name: String): KPath = throw StorageException.Unsupported(dir, "read-only")

    override suspend fun rename(path: KPath, newParent: KPath, newName: String): KPath =
        throw StorageException.Unsupported(path, "read-only")

    override suspend fun delete(path: KPath) = throw StorageException.Unsupported(path, "read-only")

    companion object {
        /** Generation changes on every edit; the trashed flag tells trash from restore/purge (SEC-4). */
        fun identity(generation: Long, trashed: Boolean) = "gen:$generation;trashed:${if (trashed) 1 else 0}"

        fun isTrashed(identity: String) = identity.endsWith(";trashed:1")
    }
}
