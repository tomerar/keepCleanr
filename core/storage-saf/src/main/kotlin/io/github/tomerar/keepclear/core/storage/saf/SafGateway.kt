// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.tomerar.keepclear.core.storage.Capability
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * M12: a [StorageGateway] over a Storage Access Framework tree the user granted (`ACTION_OPEN_DOCUMENT_TREE`),
 * e.g. the WhatsApp media folder in the `play` flavor (ONB-6). Paths are [KPath.Saf] handles.
 *
 * The [StorageGateway] contract holds here too (verified on a device by `SafGatewayContractTest`):
 * - **Never overwrite.** Providers silently rename on a name collision ("x (1).jpg"), so [create], [mkdir] and
 *   [rename] check the name first, and a document that came back under another name is removed again and
 *   reported as [StorageException.AlreadyExists].
 * - [delete] removes a file or an **empty** folder only (`deleteDocument` would delete a whole tree).
 * - SAF has no symlinks and no stable identity (document ids may change on rename), so there is no
 *   [Capability.STAT_IDENTITY]; changes are detected by size and modification time.
 * - Every provider failure becomes a [StorageException] (revoked grant → PermissionDenied).
 */
class SafGateway(private val resolver: ContentResolver) : StorageGateway {
    override val capabilities: Set<Capability> = setOf(
        Capability.LIST,
        Capability.READ,
        Capability.WRITE,
        Capability.RENAME_SAME_VOLUME,
        Capability.DELETE,
        Capability.STAT_MTIME,
    )

    private fun saf(path: KPath): KPath.Saf =
        path as? KPath.Saf ?: throw StorageException.Unsupported(path, "not a document in a granted folder")

    private fun KPath.Saf.tree(): Uri = Uri.parse(treeUri)

    private fun KPath.Saf.uri(): Uri = DocumentsContract.buildDocumentUriUsingTree(tree(), documentId)

    private fun KPath.Saf.childrenUri(): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree(), documentId)

    private fun KPath.Saf.with(documentId: String) = KPath.Saf(treeUri, documentId)

    /** Runs a provider call and maps whatever it throws to a [StorageException]. */
    private inline fun <T> guard(path: KPath, block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (_: FileNotFoundException) {
        throw StorageException.NotFound(path)
    } catch (e: SecurityException) {
        throw StorageException.PermissionDenied(path, e)
    } catch (e: java.io.IOException) {
        throw StorageException.Io(path, e)
    } catch (e: RuntimeException) {
        // Providers report failures as IllegalStateException / IllegalArgumentException / ParcelableException.
        throw StorageException.Io(path, e)
    }

    private fun stat(dir: KPath.Saf, c: Cursor): Stat {
        val id = c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID))
        val mime = c.getString(c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE))
        val sizeCol = c.getColumnIndexOrThrow(Document.COLUMN_SIZE)
        val mtimeCol = c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
        return Stat(
            path = dir.with(id),
            name = c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)) ?: id,
            kind = if (mime == Document.MIME_TYPE_DIR) Stat.Kind.DIRECTORY else Stat.Kind.FILE,
            size = if (c.isNull(sizeCol)) 0 else c.getLong(sizeCol),
            mtimeMillis = if (c.isNull(mtimeCol)) 0 else c.getLong(mtimeCol),
        )
    }

    private fun children(dir: KPath.Saf): List<Stat> = guard(dir) {
        resolver.query(dir.childrenUri(), COLUMNS, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(stat(dir, c)) }
        } ?: throw StorageException.Disconnected(dir)
    }

    private fun statNow(path: KPath.Saf): Stat? = guard(path) {
        // DocumentsProvider.query returns null (it logs FileNotFoundException) when the document is gone.
        resolver.query(path.uri(), COLUMNS, null, null, null)?.use { c -> if (c.moveToFirst()) stat(path, c) else null }
    }

    private fun existing(dir: KPath.Saf, name: String): Stat? = children(dir).firstOrNull { it.name == name }

    private fun requireDir(dir: KPath.Saf): Stat {
        val s = statNow(dir) ?: throw StorageException.NotFound(dir)
        if (!s.isDirectory) throw StorageException.NotADirectory(dir)
        return s
    }

    private fun requireName(dir: KPath, name: String) {
        if (!KPath.isValidName(name)) throw StorageException.Unsupported(dir, "invalid name")
    }

    override fun list(dir: KPath): Flow<Stat> = flow {
        val d = saf(dir)
        requireDir(d)
        children(d).asFlow().collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    override suspend fun stat(path: KPath): Stat? = withContext(Dispatchers.IO) { statNow(saf(path)) }

    override suspend fun openRead(path: KPath): InputStream = withContext(Dispatchers.IO) {
        val p = saf(path)
        val s = statNow(p) ?: throw StorageException.NotFound(p)
        if (s.isDirectory) throw StorageException.IsADirectory(p)
        guard(p) { resolver.openInputStream(p.uri()) ?: throw StorageException.NotFound(p) }
    }

    /** Creates [name] in [dir] and returns it, or fails without leaving a renamed copy behind. */
    private fun createExact(dir: KPath.Saf, name: String, mime: String): KPath.Saf {
        requireName(dir, name)
        requireDir(dir)
        if (existing(dir, name) != null) throw StorageException.AlreadyExists(dir)
        val created = guard(dir) {
            DocumentsContract.createDocument(resolver, dir.uri(), mime, name)
                ?: throw StorageException.Io(dir, message = "provider created nothing")
        }
        val child = dir.with(DocumentsContract.getDocumentId(created))
        val actual = statNow(child)
        if (actual?.name != name) {
            // Someone took the name in between and the provider renamed ours: undo, never keep "x (1)".
            if (actual != null) guard(child) { DocumentsContract.deleteDocument(resolver, child.uri()) }
            throw StorageException.AlreadyExists(dir)
        }
        return child
    }

    override suspend fun create(dir: KPath, name: String): OutputStream = withContext(Dispatchers.IO) {
        val child = createExact(saf(dir), name, OCTET_STREAM)
        guard(child) { resolver.openOutputStream(child.uri(), "w") ?: throw StorageException.Io(child) }
    }

    override suspend fun mkdir(dir: KPath, name: String): KPath = withContext(Dispatchers.IO) {
        createExact(saf(dir), name, Document.MIME_TYPE_DIR)
    }

    /** The folder that holds [path] (providers must support `findDocumentPath`; local and USB providers do). */
    private fun parentOf(path: KPath.Saf): KPath.Saf {
        val ids = guard(path) { DocumentsContract.findDocumentPath(resolver, path.uri())?.path }
            ?: throw StorageException.Unsupported(path, "provider can't tell the parent folder")
        if (ids.size < 2) throw StorageException.Unsupported(path, "the granted folder itself")
        return path.with(ids[ids.size - 2])
    }

    private fun renameInPlace(path: KPath.Saf, newName: String): KPath.Saf = guard(path) {
        val uri = DocumentsContract.renameDocument(resolver, path.uri(), newName)
            ?: throw StorageException.Io(path, message = "provider did not rename")
        path.with(DocumentsContract.getDocumentId(uri))
    }

    private fun move(path: KPath.Saf, from: KPath.Saf, to: KPath.Saf): KPath.Saf = guard(path) {
        val uri = DocumentsContract.moveDocument(resolver, path.uri(), from.uri(), to.uri())
            ?: throw StorageException.Unsupported(path, "provider can't move documents")
        path.with(DocumentsContract.getDocumentId(uri))
    }

    override suspend fun rename(path: KPath, newParent: KPath, newName: String): KPath = withContext(Dispatchers.IO) {
        val p = saf(path)
        val dest = saf(newParent)
        requireName(dest, newName)
        if (p.treeUri != dest.treeUri) throw StorageException.Unsupported(p, "moving between granted folders")
        val source = statNow(p) ?: throw StorageException.NotFound(p)
        requireDir(dest)
        if (existing(dest, newName) != null) throw StorageException.AlreadyExists(dest)
        val parent = parentOf(p)
        val result = when {
            parent.documentId == dest.documentId -> renameInPlace(p, newName)

            source.name == newName -> move(p, parent, dest)

            // Two steps; pick the order whose intermediate name is free, so nothing is ever overwritten.
            existing(parent, newName) == null -> move(renameInPlace(p, newName), parent, dest)

            existing(dest, source.name) == null -> renameInPlace(move(p, parent, dest), newName)

            else -> throw StorageException.Unsupported(p, "no free name to move through")
        }
        if (statNow(result)?.name != newName) {
            throw StorageException.Io(result, message = "provider stored a different name")
        }
        result
    }

    override suspend fun delete(path: KPath) = withContext(Dispatchers.IO) {
        val p = saf(path)
        val s = statNow(p) ?: throw StorageException.NotFound(p)
        // deleteDocument removes a whole tree; the contract allows only files and empty folders.
        if (s.isDirectory && children(p).isNotEmpty()) throw StorageException.NotEmpty(p)
        val deleted = guard(p) { DocumentsContract.deleteDocument(resolver, p.uri()) }
        if (!deleted) throw StorageException.Io(p, message = "provider did not delete")
    }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
