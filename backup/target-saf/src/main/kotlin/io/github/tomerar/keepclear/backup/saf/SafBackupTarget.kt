// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [BackupTarget] inside a Storage Access Framework tree the user picked (MOVE-2): a USB-C drive, an SD card,
 * or any provider. Paths are relative to the tree root and resolved by display name, one segment at a time.
 *
 * Never overwrites: [create] and [rename] refuse an existing name, and if a provider silently picks another
 * name ("x (1).jpg") the new document is removed again and [StorageException.AlreadyExists] is thrown.
 */
class SafBackupTarget(
    private val resolver: ContentResolver,
    val treeUri: Uri,
    override val id: String,
    override val label: String,
    override val caps: TargetCaps,
) : BackupTarget {
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun kpath(rel: String) = KPath.Saf(treeUri.toString(), rel)

    private data class Child(val docId: String, val name: String, val mime: String, val size: Long, val mtime: Long)

    private fun children(parentDocId: String, rel: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor: Cursor = resolver.query(uri, COLUMNS, null, null, null)
            ?: throw StorageException.Disconnected(kpath(rel))
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Child(
                            docId = c.getString(0),
                            name = c.getString(1) ?: continue,
                            mime = c.getString(2) ?: "",
                            size = if (c.isNull(3)) 0 else c.getLong(3),
                            mtime = if (c.isNull(4)) 0 else c.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    private fun segments(rel: String): List<String> {
        require(!rel.startsWith("/")) { "relative path expected: $rel" }
        val parts = rel.split('/').filter { it.isNotEmpty() }
        require(parts.all { KPath.isValidName(it) }) { "invalid path: $rel" }
        return parts
    }

    /** Finds the child named [name]; on case-insensitive media an exact match wins over a case variant. */
    private fun find(parentDocId: String, name: String, rel: String): Child? {
        val all = children(parentDocId, rel)
        return all.firstOrNull { it.name == name }
            ?: if (!caps.caseSensitive) all.firstOrNull { it.name.equals(name, ignoreCase = true) } else null
    }

    /** Document id of [rel], or null if any segment is missing. */
    private fun resolve(rel: String): Child? {
        var current = Child(rootDocId, "", Document.MIME_TYPE_DIR, 0, 0)
        for (segment in segments(rel)) {
            if (current.mime != Document.MIME_TYPE_DIR) throw StorageException.NotADirectory(kpath(rel))
            current = find(current.docId, segment, rel) ?: return null
        }
        return current
    }

    private fun parentAndName(rel: String): Pair<String, String> {
        val parts = segments(rel)
        require(parts.isNotEmpty()) { "root has no parent" }
        return parts.dropLast(1).joinToString("/") to parts.last()
    }

    private fun dirDocId(relDir: String): String {
        val dir = resolve(relDir) ?: throw StorageException.NotFound(kpath(relDir))
        if (dir.mime != Document.MIME_TYPE_DIR) throw StorageException.NotADirectory(kpath(relDir))
        return dir.docId
    }

    override suspend fun freeBytes(): Long? = io("") {
        val authority = treeUri.authority ?: return@io null
        resolver.query(DocumentsContract.buildRootsUri(authority), ROOT_COLUMNS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val rootDoc = c.getString(0) ?: continue
                if (rootDocId == rootDoc ||
                    rootDocId.startsWith(rootDoc)
                ) {
                    return@io if (c.isNull(1)) null else c.getLong(1)
                }
            }
            null
        }
    }

    override suspend fun mkdirs(rel: String) = io(rel) {
        var parent = rootDocId
        var soFar = ""
        for (segment in segments(rel)) {
            soFar = if (soFar.isEmpty()) segment else "$soFar/$segment"
            val existing = find(parent, segment, soFar)
            parent = when {
                existing == null -> createChecked(parent, Document.MIME_TYPE_DIR, segment, soFar)
                existing.mime != Document.MIME_TYPE_DIR -> throw StorageException.NotADirectory(kpath(soFar))
                else -> existing.docId
            }
        }
    }

    /** Creates [name] in [parentDocId]; undoes and fails if the provider chose a different name. */
    private fun createChecked(parentDocId: String, mime: String, name: String, rel: String): String {
        val uri = DocumentsContract.createDocument(resolver, docUri(parentDocId), mime, name)
            ?: throw StorageException.Io(kpath(rel), message = "Provider refused to create")
        val docId = DocumentsContract.getDocumentId(uri)
        val actual = children(parentDocId, rel).firstOrNull { it.docId == docId }?.name
        if (actual != name) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw StorageException.AlreadyExists(kpath(rel))
        }
        return docId
    }

    override suspend fun create(rel: String): OutputStream = io(rel) {
        val (parentRel, name) = parentAndName(rel)
        val parent = dirDocId(parentRel)
        if (find(parent, name, rel) != null) throw StorageException.AlreadyExists(kpath(rel))
        val docId = createChecked(parent, "application/octet-stream", name, rel)
        val pfd = resolver.openFileDescriptor(docUri(docId), "w")
            ?: throw StorageException.Io(kpath(rel), message = "No descriptor")
        val out = FileOutputStream(pfd.fileDescriptor)
        object : OutputStream() {
            private var closed = false

            override fun write(b: Int) = guardWrite(rel) { out.write(b) }

            override fun write(b: ByteArray, off: Int, len: Int) = guardWrite(rel) { out.write(b, off, len) }

            override fun flush() = guardWrite(rel) { out.flush() }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    guardWrite(rel) {
                        out.flush()
                        // MOVE-6: the bytes must be on the medium before the copy is read back and trusted.
                        // Pipe-backed providers (cloud apps) can't fsync; they are copy-only or read back
                        // with a warning (MOVE-14), so only real files must sync.
                        if (isRegularFile(out.fd)) out.fd.sync()
                    }
                } finally {
                    runCatching { out.close() }
                    runCatching { pfd.close() }
                }
            }
        }
    }

    private fun isRegularFile(fd: java.io.FileDescriptor): Boolean =
        runCatching { android.system.OsConstants.S_ISREG(android.system.Os.fstat(fd).st_mode) }.getOrDefault(true)

    private inline fun guardWrite(rel: String, block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            throw mapError(e, rel)
        }
    }

    override suspend fun rename(fromRel: String, toRel: String) = io(fromRel) {
        val (fromParent, _) = parentAndName(fromRel)
        val (toParent, toName) = parentAndName(toRel)
        if (fromParent != toParent) throw StorageException.Unsupported(kpath(fromRel), "rename across folders")
        val source = resolve(fromRel) ?: throw StorageException.NotFound(kpath(fromRel))
        val parentId = dirDocId(toParent)
        if (find(parentId, toName, toRel) != null) throw StorageException.AlreadyExists(kpath(toRel))
        val renamed = DocumentsContract.renameDocument(resolver, docUri(source.docId), toName)
            ?: throw StorageException.Io(kpath(fromRel), message = "Provider refused to rename")
        val newId = DocumentsContract.getDocumentId(renamed)
        val actual = children(parentId, toRel).firstOrNull { it.docId == newId }?.name
        if (actual != toName) {
            // The provider picked another name: put the original name back and report the collision.
            runCatching { DocumentsContract.renameDocument(resolver, renamed, source.name) }
            throw StorageException.AlreadyExists(kpath(toRel))
        }
    }

    override suspend fun stat(rel: String): Stat? = io(rel) {
        val c = resolve(rel) ?: return@io null
        Stat(
            path = kpath(rel),
            name = c.name,
            kind = if (c.mime == Document.MIME_TYPE_DIR) Stat.Kind.DIRECTORY else Stat.Kind.FILE,
            size = c.size,
            mtimeMillis = c.mtime,
            identity = c.docId,
        )
    }

    override suspend fun openRead(rel: String): InputStream = io(rel) {
        val c = resolve(rel) ?: throw StorageException.NotFound(kpath(rel))
        if (c.mime == Document.MIME_TYPE_DIR) throw StorageException.IsADirectory(kpath(rel))
        val input = resolver.openInputStream(docUri(c.docId))
            ?: throw StorageException.Io(kpath(rel), message = "No stream")
        object : InputStream() {
            override fun read(): Int = guardRead(rel) { input.read() }

            override fun read(b: ByteArray, off: Int, len: Int): Int = guardRead(rel) { input.read(b, off, len) }

            override fun close() = input.close()
        }
    }

    private inline fun <T> guardRead(rel: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw mapError(e, rel)
    }

    override suspend fun delete(rel: String) = io(rel) {
        val c = resolve(rel) ?: throw StorageException.NotFound(kpath(rel))
        if (c.mime == Document.MIME_TYPE_DIR) throw StorageException.IsADirectory(kpath(rel))
        if (!DocumentsContract.deleteDocument(resolver, docUri(c.docId))) {
            throw StorageException.Io(kpath(rel), message = "Provider refused to delete")
        }
    }

    override suspend fun list(relDir: String): List<Stat> = io(relDir) {
        children(dirDocId(relDir), relDir).map { c ->
            val rel = if (relDir.isEmpty()) c.name else "$relDir/${c.name}"
            Stat(
                kpath(rel),
                c.name,
                if (c.mime == Document.MIME_TYPE_DIR) Stat.Kind.DIRECTORY else Stat.Kind.FILE,
                c.size,
                c.mtime,
                c.docId,
            )
        }
    }

    /** True while the tree root can still be queried (the drive is plugged in and the grant is valid). */
    fun isAttached(): Boolean = runCatching {
        resolver.query(docUri(rootDocId), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.count > 0 }
            ?: false
    }.getOrDefault(false)

    private suspend fun <T> io(rel: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw mapError(e, rel)
        }
    }

    /**
     * Providers report an unplugged drive as FileNotFoundException, IllegalArgumentException or
     * IllegalStateException; a revoked grant as SecurityException. Anything while the root is gone is a
     * disconnect, so the job pauses instead of failing files (E-MOVE-2).
     */
    private fun mapError(e: Exception, rel: String): StorageException = when {
        e is StorageException -> {
            e
        }

        e is SecurityException -> {
            StorageException.PermissionDenied(kpath(rel), e)
        }

        e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true -> {
            StorageException.NoSpace(kpath(rel))
        }

        !isAttached() -> {
            StorageException.Disconnected(kpath(rel), e)
        }

        e is FileNotFoundException -> {
            StorageException.NotFound(kpath(rel))
        }

        else -> {
            StorageException.Io(kpath(rel), e)
        }
    }

    private companion object {
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        val ROOT_COLUMNS =
            arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
    }
}
