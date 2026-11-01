// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.testing.usb

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * A directory-backed DocumentsProvider that acts like a removable drive: case-insensitive name lookups,
 * auto-renames on create collisions ("x (1).jpg", like real providers), and [ejected] makes every call
 * fail the way an unplugged OTG drive does.
 *
 * Test-only and debug-only. Until a test calls [reset] it lists no root and every call fails, so it never
 * shows up in the system folder picker on a real phone.
 */
class FakeUsbProvider : DocumentsProvider() {
    companion object {
        const val ROOT_DOC = "usb"

        fun authority(context: android.content.Context) = "${context.packageName}.testusb"

        @Volatile var enabled = false

        @Volatile var ejected = false

        /** Unplug the drive when the N-th file is created (0 = never). */
        @Volatile var ejectOnCreate = 0

        @Volatile var creates = 0

        /** Makes every move fail, like a provider that can't move documents (tests the undo of a 2-step rename). */
        @Volatile var failMoves = false

        /** Simulates a slow drive: each created document takes this long. */
        @Volatile var createDelayMs = 0L

        lateinit var base: File

        fun reset(dir: File) {
            enabled = true
            base = dir
            dir.deleteRecursively()
            dir.mkdirs()
            ejected = false
            ejectOnCreate = 0
            creates = 0
            createDelayMs = 0
            failMoves = false
        }
    }

    private val docCols = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
    )

    override fun onCreate() = true

    private fun check() {
        if (!enabled || ejected) throw FileNotFoundException("drive removed")
    }

    private fun file(docId: String): File {
        require(docId == ROOT_DOC || docId.startsWith("$ROOT_DOC/")) { "bad id $docId" }
        return File(base, docId.removePrefix(ROOT_DOC).trimStart('/'))
    }

    private fun idOf(f: File): String =
        if (f == base) ROOT_DOC else "$ROOT_DOC/" + f.relativeTo(base).path

    private fun row(c: MatrixCursor, f: File) {
        val flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_WRITE or
            Document.FLAG_SUPPORTS_MOVE or (if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(f))
            .add(Document.COLUMN_DISPLAY_NAME, if (f == base) "USB" else f.name)
            .add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_SIZE, if (f.isDirectory) 0 else f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(
            projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES),
        )
        if (!enabled || ejected) return c
        c.newRow()
            .add(Root.COLUMN_ROOT_ID, "usb")
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
            .add(Root.COLUMN_AVAILABLE_BYTES, 1_000_000_000L)
            .add(Root.COLUMN_TITLE, "Test USB")
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE)
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        check()
        val f = file(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        return MatrixCursor(docCols).also { row(it, f) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        check()
        val c = MatrixCursor(docCols)
        file(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { row(c, it) }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        check()
        val f = file(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check()
        if (createDelayMs > 0) Thread.sleep(createDelayMs)
        creates++
        if (ejectOnCreate in 1..creates) {
            ejected = true
            throw IllegalStateException("drive removed")
        }
        val dir = file(parentDocumentId)
        var name = displayName
        var n = 1
        val taken = { candidate: String ->
            dir.listFiles()?.any { it.name.equals(candidate, ignoreCase = true) } == true
        }
        while (taken(name)) {
            val dot = displayName.lastIndexOf('.')
            name = if (dot > 0) {
                "${displayName.substring(0, dot)} ($n)${displayName.substring(dot)}"
            } else {
                "$displayName ($n)"
            }
            n++
        }
        val f = File(dir, name)
        if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
        return idOf(f)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        check()
        val f = file(documentId)
        val to = File(f.parentFile, displayName)
        if (to.exists()) throw IllegalStateException("exists")
        if (!f.renameTo(to)) throw IllegalStateException("rename failed")
        return idOf(to)
    }

    /** Like real local/USB providers: the ids from [parentDocumentId] (or the root) down to the document. */
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        check()
        val top = parentDocumentId ?: ROOT_DOC
        if (!isChildDocument(top, childDocumentId) || !file(childDocumentId).exists()) {
            throw FileNotFoundException(childDocumentId)
        }
        val stop = file(top)
        val ids = ArrayDeque<String>()
        var f: File? = file(childDocumentId)
        while (f != null) {
            ids.addFirst(idOf(f))
            if (f == stop) break
            f = f.parentFile
        }
        return DocumentsContract.Path(if (parentDocumentId == null) "usb" else null, ids.toList())
    }

    /** Moves within the drive; never overwrites (fails if the name is taken in the target folder). */
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        check()
        if (failMoves) throw IllegalStateException("move failed")
        val f = file(sourceDocumentId)
        if (f.parentFile != file(sourceParentDocumentId)) throw IllegalStateException("not in that folder")
        val to = File(file(targetParentDocumentId), f.name)
        if (to.exists()) throw IllegalStateException("exists")
        if (!f.renameTo(to)) throw IllegalStateException("move failed")
        return idOf(to)
    }

    override fun deleteDocument(documentId: String) {
        check()
        if (!file(documentId).delete()) throw IllegalStateException("delete failed")
    }
}
