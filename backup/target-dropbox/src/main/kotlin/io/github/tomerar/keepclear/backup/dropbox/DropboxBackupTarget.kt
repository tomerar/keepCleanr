// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.dropbox

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.DigestKind
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.backup.dropbox.DropboxJson.array
import io.github.tomerar.keepclear.backup.dropbox.DropboxJson.bool
import io.github.tomerar.keepclear.backup.dropbox.DropboxJson.long
import io.github.tomerar.keepclear.backup.dropbox.DropboxJson.obj
import io.github.tomerar.keepclear.backup.dropbox.DropboxJson.string
import io.github.tomerar.keepclear.backup.net.HostHttpClient
import io.github.tomerar.keepclear.backup.net.HttpLengthMismatchException
import io.github.tomerar.keepclear.backup.net.HttpProtocolException
import io.github.tomerar.keepclear.backup.net.HttpResponse
import io.github.tomerar.keepclear.backup.net.HttpStatusException
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Dropbox answered 409 with this `error_summary` (e.g. `path/not_found/..`); mapped to a storage error. */
internal class DropboxApiError(val summary: String) : Exception(summary)

/**
 * Dropbox is busy or rate-limits KeepClear (429, `too_many_requests`). The cause of a
 * [StorageException.Disconnected]: the job pauses and the app may resume after [retryAfterSeconds] (null when
 * Dropbox didn't say). KeepClear never sleeps on it inside a request.
 */
class DropboxRetryAfterException(val retryAfterSeconds: Long?) :
    IOException("Dropbox asked to retry later" + (retryAfterSeconds?.let { " ($it s)" } ?: ""))

/**
 * M17c (MOVE-3), ADR 0011: a backup target in the user's Dropbox, App folder scope (Apps › KeepClear), over the
 * Dropbox HTTP API v2.
 *
 * - **Verification.** Dropbox computes `content_hash` itself from the stored bytes (SHA-256 of the 4 MiB block
 *   SHA-256s) and reports it with the file's metadata, so [serverDigest] returns the value `get_metadata` reports
 *   for the file now. The MOVE engine compares it with the same hash computed on the phone while copying (ADR
 *   0011). A missing or malformed value is never a proof: the engine reads the copy back instead. Every read is a
 *   download from Dropbox (`readsFromServer`); free time still downloads and re-hashes each copy.
 * - **Never overwrite.** Uploads use `mode=add`, `autorename=false`, `strict_conflict=true` after `get_metadata`
 *   found the name free; renames use `move_v2` with `autorename=false` after the destination was found free.
 *   Deletes remove only one revision of a file KeepClear saw (`parent_rev`); folders are never deleted.
 * - **Uploads** up to [singleUploadMax] go in one `upload` request with the length announced; larger ones, and
 *   files whose size isn't known up front, go through an upload session in chunks of [chunkSize]
 *   (`start`/`append_v2`/`finish`). Dropbox stores nothing under the name unless the whole upload completed.
 * - **Untrusted server** (SEC): no redirects, bounded answers, strict JSON with a nesting limit, entries outside
 *   the folder or with unsafe names ignored. Only the two API hosts in [DropboxEndpoints] are ever contacted.
 * - Every failure becomes a [StorageException]: no answer, 429 or 5xx is [StorageException.Disconnected] (429
 *   carries [DropboxRetryAfterException]); a token Dropbox refuses after one refresh is
 *   [StorageException.PermissionDenied]; `insufficient_space` is [StorageException.NoSpace].
 *
 * Paths are case-insensitive on Dropbox (`caseSensitive = false`). [folder] is a folder inside the App folder
 * ("" for the App folder itself).
 */
class DropboxBackupTarget(
    private val auth: DropboxAuth,
    folder: String,
    override val id: String,
    override val label: String,
    endpoints: DropboxEndpoints = DropboxEndpoints.PRODUCTION,
    timeoutSeconds: Long = 30,
    private val singleUploadMax: Long = SINGLE_UPLOAD_MAX,
    private val chunkSize: Int = CHUNK_SIZE,
) : BackupTarget {
    override val caps = TargetCaps(
        canReadBack = true,
        maxFileSize = MAX_FILE_SIZE,
        caseSensitive = false,
        readsFromServer = true,
        serverDigests = setOf(DigestKind.DROPBOX_CONTENT_HASH),
        canDeleteFolders = false,
    )

    private val base: List<String> = folder.split('/').filter { it.isNotEmpty() }.also { segs ->
        require(segs.all(DropboxPaths::isSafeSegment)) { "folder segment not allowed" }
    }

    init {
        require(singleUploadMax in 0..SINGLE_UPLOAD_MAX) { "Dropbox takes at most 150 MB in one upload request" }
        require(chunkSize in 1..CHUNK_SIZE) { "upload chunks are at most $CHUNK_SIZE bytes here" }
    }

    private val timeout = (timeoutSeconds * 1000).toInt()
    private val api = HostHttpClient(endpoints.https, endpoints.apiHost, endpoints.port, timeout)
    private val content = HostHttpClient(endpoints.https, endpoints.contentHost, endpoints.port, timeout)

    // ------------------------------------------------------------------------------------------------- paths

    private fun segments(rel: String): List<String> {
        require(!rel.startsWith("/")) { "relative path expected" }
        val segs = rel.split('/').filter { it.isNotEmpty() }
        require(segs.all(DropboxPaths::isSafeSegment)) { "path segment not allowed" }
        return segs
    }

    private fun dbx(segs: List<String>) = DropboxPaths.path(base + segs)

    private fun kpath(segs: List<String>): KPath.Local = KPath.Local("/" + segs.joinToString("/"))

    // ------------------------------------------------------------------------------------------------ errors

    /** Runs [block] on the IO dispatcher and maps every failure to a [StorageException]. */
    private suspend fun <T> io(path: KPath, block: () -> T): T = withContext(Dispatchers.IO) { mapped(path, block) }

    private inline fun <T> mapped(path: KPath, block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (e: DropboxApiError) {
        throw apiError(e.summary, path)
    } catch (e: DropboxSignInRequiredException) {
        throw StorageException.PermissionDenied(path, e)
    } catch (e: HttpStatusException) {
        throw statusError(e.status, path)
    } catch (e: HttpProtocolException) {
        throw StorageException.Io(path, e, "Dropbox's answer was not understood")
    } catch (e: DropboxJsonException) {
        throw StorageException.Io(path, e, "Dropbox's answer was not understood")
    } catch (e: IOException) {
        // Refused, timed out, dropped Wi-Fi, certificate refused: Dropbox is gone for now (pause, never delete).
        throw StorageException.Disconnected(path, e)
    }

    /**
     * Throws the right exception for a non-200 answer. A 401 here is final: RPCs already refreshed once
     * ([call]); for a streamed upload the token is refreshed for the next try and the upload is reported as
     * interrupted (the job pauses and resumes, nothing is lost).
     */
    private fun failure(r: HttpResponse, path: KPath, token: String?): Nothing {
        val body = runCatching { r.readBody(MAX_ERROR_BYTES) }.getOrNull()
        when (r.status) {
            409 -> {
                throw DropboxApiError(DropboxJson.errorSummary(body))
            }

            429 -> {
                throw StorageException.Disconnected(path, DropboxRetryAfterException(retryAfter(r)))
            }

            401 -> {
                if (token != null && !DropboxJson.errorSummary(body).startsWith("missing_scope")) {
                    val fresh = runCatching { auth.refresh(token) }.getOrNull()
                    if (fresh != null) throw StorageException.Disconnected(path, HttpStatusException(401))
                }
                throw StorageException.PermissionDenied(path)
            }

            else -> {
                throw HttpStatusException(r.status)
            }
        }
    }

    private fun retryAfter(r: HttpResponse): Long? =
        r.header("retry-after")?.trim()?.takeIf { v -> v.length in 1..6 && v.all { it.isDigit() } }?.toLong()

    /** Reads at most 64 KiB of an answer we don't need, so the connection can be reused. */
    private fun discard(r: HttpResponse) {
        runCatching {
            r.body.use { input ->
                val buf = ByteArray(8 * 1024)
                var total = 0
                while (total < 64 * 1024) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- calls

    /**
     * Sends one request whose body is in memory, with the access token. A 401 refreshes the token once and sends
     * the request again (Dropbox refused it before doing anything). Other statuses go back to the caller.
     */
    private fun call(
        http: HostHttpClient,
        endpoint: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
        readOnly: Boolean,
        path: KPath,
    ): HttpResponse {
        var token = auth.accessToken()
        var refreshed = false
        while (true) {
            val r = http.send("POST", endpoint, listOf("Authorization" to "Bearer $token") + headers, body, readOnly)
            if (r.status != 401 || refreshed) return r
            val summary = DropboxJson.errorSummary(runCatching { r.readBody(MAX_ERROR_BYTES) }.getOrNull())
            if (summary.startsWith("missing_scope")) throw HttpStatusException(403)
            token = auth.refresh(token) ?: throw StorageException.PermissionDenied(path)
            refreshed = true
        }
    }

    /** An RPC on the API host: JSON in, JSON out. */
    private fun rpc(endpoint: String, arg: JsonElement, path: KPath, readOnly: Boolean): JsonObject {
        val r = call(api, endpoint, listOf(JSON), DropboxJson.encode(arg), readOnly, path)
        if (r.status != 200) failure(r, path, null)
        return DropboxJson.parse(r.readBody(MAX_RPC_BYTES))
    }

    /** `get_metadata` for [segs] (relative to [base]); null when nothing is there. */
    private fun metadata(segs: List<String>): DbxEntry? = metadataAbs(base + segs)

    private fun toStat(segs: List<String>, e: DbxEntry) = Stat(
        path = kpath(segs),
        name = segs.lastOrNull() ?: "",
        kind = if (e.isFolder) Stat.Kind.DIRECTORY else Stat.Kind.FILE,
        size = e.size,
        mtimeMillis = e.mtimeMillis,
        identity = e.rev,
    )

    // ------------------------------------------------------------------------------------------- BackupTarget

    /** Free space in the account from `users/get_space_usage`, or null when Dropbox doesn't say. */
    override suspend fun freeBytes(): Long? = io(kpath(emptyList())) {
        val o = try {
            rpc("/2/users/get_space_usage", JsonNull, kpath(emptyList()), readOnly = true)
        } catch (e: HttpStatusException) {
            // E.g. the account_info.read permission isn't granted: unknown, not an error.
            if (e.status in 400..403) return@io null
            throw e
        }
        val used = o.long("used")?.takeIf { it >= 0 } ?: return@io null
        val allocated = o.obj("allocation")?.long("allocated")?.takeIf { it > 0 } ?: return@io null
        (allocated - used).coerceAtLeast(0)
    }

    override suspend fun mkdirs(rel: String) {
        val segs = segments(rel)
        io(kpath(segs)) {
            val all = base + segs
            if (all.isEmpty()) return@io
            // Find the deepest level that exists (refusing a file on the way), then create downwards.
            var existing = all.size
            while (existing > 0) {
                val here = metadataAbs(all.subList(0, existing))
                if (here != null) {
                    if (!here.isFolder) throw StorageException.NotADirectory(relKPath(all.subList(0, existing)))
                    break
                }
                existing--
            }
            for (end in existing + 1..all.size) createFolder(all.subList(0, end))
        }
    }

    /** `get_metadata` for [abs] (inside the App folder); null when nothing is there. The App folder is a folder. */
    private fun metadataAbs(abs: List<String>): DbxEntry? {
        val p = DropboxPaths.path(abs)
        if (p.isEmpty()) return DbxEntry(true, "", "", 0, 0, null, null)
        val o = try {
            rpc("/2/files/get_metadata", buildJsonObject { put("path", p) }, relKPath(abs), readOnly = true)
        } catch (e: DropboxApiError) {
            if (e.summary.startsWith("path/not_found")) return null
            throw e
        }
        val entry = DropboxJson.entry(o) ?: throw DropboxJsonException("not a file or folder")
        // The answer must describe what was asked (SEC: never act on another path's metadata).
        if (entry.pathLower != DropboxJson.fold(p)) throw DropboxJsonException("the answer describes another path")
        return entry
    }

    private fun relKPath(abs: List<String>) = kpath(abs.drop(base.size.coerceAtMost(abs.size)))

    private fun createFolder(abs: List<String>) {
        try {
            rpc(
                "/2/files/create_folder_v2",
                buildJsonObject {
                    put("path", DropboxPaths.path(abs))
                    put("autorename", false)
                },
                relKPath(abs),
                readOnly = false,
            )
        } catch (e: DropboxApiError) {
            // Already there (made at the same moment elsewhere): fine only if it is a folder.
            if (!e.summary.startsWith("path/conflict")) throw e
            if (metadataAbs(abs)?.isFolder != true) throw StorageException.NotADirectory(relKPath(abs))
        }
    }

    /**
     * Without a known size the bytes are kept in memory up to [chunkSize] and then go through an upload
     * session; closing the stream completes the upload.
     */
    override suspend fun create(rel: String): OutputStream = upload(rel, null)

    override suspend fun create(rel: String, size: Long): OutputStream {
        require(size >= 0)
        return upload(rel, size)
    }

    private suspend fun upload(rel: String, size: Long?): OutputStream {
        val segs = segments(rel)
        require(segs.isNotEmpty()) { "a file name is needed" }
        val path = kpath(segs)
        val target = dbx(segs)
        // Never overwrite (MOVE-6): checked here and again by Dropbox through mode=add, strict_conflict.
        io(path) { if (metadata(segs) != null) throw StorageException.AlreadyExists(path) }
        if (size != null && size > MAX_FILE_SIZE) throw StorageException.Io(path, message = "too large for Dropbox")
        return if (size != null && size <= singleUploadMax) {
            var token = ""
            val up = io(path) {
                token = auth.accessToken()
                content.upload(
                    "POST",
                    "/2/files/upload",
                    listOf("Authorization" to "Bearer $token", argHeader(commitArg(target)), OCTETS),
                    size,
                )
            }
            SingleUpload(up, path, target, size, token)
        } else {
            SessionUpload(path, target, size)
        }
    }

    private fun commitArg(target: String) = buildJsonObject {
        put("path", target)
        put("mode", "add")
        put("autorename", false)
        put("mute", true)
        put("strict_conflict", true)
    }

    private fun argHeader(arg: JsonElement) = "Dropbox-API-Arg" to DropboxJson.header(arg)

    /** Checks the metadata Dropbox answered for a finished upload: the right path and every byte. */
    private fun checkCommitted(r: HttpResponse, path: KPath, target: String, size: Long) {
        // `upload` and `upload_session/finish` return a concrete FileMetadata (no `.tag`).
        val entry = DropboxJson.fileEntry(DropboxJson.parse(r.readBody(MAX_RPC_BYTES)))
            ?: throw DropboxJsonException("not a file")
        if (entry.isFolder || entry.pathLower != DropboxJson.fold(target) || entry.size != size) {
            throw StorageException.Io(path, message = "Dropbox stored something else than was sent")
        }
    }

    /** One `upload` request, streamed with the length announced (the last byte is held back by HostHttpClient). */
    private inner class SingleUpload(
        private val up: HostHttpClient.Upload,
        private val path: KPath,
        private val target: String,
        private val size: Long,
        private val token: String,
    ) : OutputStream() {
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) =
            mapped(path) { lengthChecked { up.write(b, off, len) } }

        override fun flush() = mapped(path) { up.flush() }

        override fun close() {
            if (closed) return
            closed = true
            mapped(path) {
                val r = lengthChecked { up.finish() }
                if (r.status != 200) failure(r, path, token)
                checkCommitted(r, path, target, size)
            }
        }

        private inline fun <T> lengthChecked(block: () -> T): T = try {
            block()
        } catch (e: HttpLengthMismatchException) {
            // Abandoned before the last byte: Dropbox completes nothing, so no file is left under the name.
            throw StorageException.Io(path, e, "the file's length changed while it was sent")
        }
    }

    /**
     * An upload session: bytes are collected into chunks of [chunkSize]; each chunk is one request
     * (`start`, then `append_v2`), and [close] sends the rest with `finish`, which creates the file. A session
     * that is never finished leaves nothing visible; Dropbox drops it after 48 hours. With an unknown size and
     * fewer than [chunkSize] bytes, [close] sends one plain `upload` instead.
     */
    private inner class SessionUpload(
        private val path: KPath,
        private val target: String,
        private val size: Long?,
    ) : OutputStream() {
        private var buffer =
            ByteArray(minOf(chunkSize.toLong(), size ?: INITIAL_BUFFER.toLong()).toInt().coerceAtLeast(1))
        private var fill = 0
        private var written = 0L
        private var offset = 0L
        private var sessionId: String? = null
        private var failed = false
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed || failed) throw StorageException.Io(path, message = "the upload has already ended")
            if (size != null && written + len > size) {
                failed = true
                throw StorageException.Io(path, message = "the file's length changed while it was sent")
            }
            var o = off
            var left = len
            while (left > 0) {
                if (fill == buffer.size) {
                    if (buffer.size < chunkSize) {
                        buffer = buffer.copyOf(minOf(chunkSize, buffer.size * 2))
                    } else {
                        sendChunk()
                    }
                }
                val n = minOf(left, buffer.size - fill)
                System.arraycopy(b, o, buffer, fill, n)
                fill += n
                o += n
                left -= n
            }
            written += len
        }

        private fun sendChunk() = guard {
            val bytes = buffer.copyOf(fill)
            val id = sessionId
            val r = if (id == null) {
                send("/2/files/upload_session/start", sessionStart(), bytes)
            } else {
                send("/2/files/upload_session/append_v2", buildJsonObject { put("cursor", cursor(id)) }, bytes)
            }
            if (r.status != 200) failure(r, path, null)
            if (id == null) {
                val newId = DropboxJson.parse(r.readBody(MAX_RPC_BYTES)).string("session_id")
                    ?.takeIf { it.length in 1..256 && it.all { c -> c in '!'..'~' } }
                    ?: throw DropboxJsonException("no upload session id")
                sessionId = newId
            } else {
                discard(r)
            }
            offset += fill
            fill = 0
        }

        private fun send(endpoint: String, arg: JsonElement, bytes: ByteArray) =
            call(content, endpoint, listOf(argHeader(arg), OCTETS), bytes, readOnly = false, path = path)

        private fun sessionStart() = buildJsonObject { put("close", false) }

        private fun cursor(id: String) = buildJsonObject {
            put("session_id", id)
            put("offset", offset)
        }

        override fun close() {
            if (closed) return
            closed = true
            if (failed) throw StorageException.Io(path, message = "the upload has already ended")
            if (size != null && written != size) {
                failed = true
                throw StorageException.Io(path, message = "the file's length changed while it was sent")
            }
            guard {
                val bytes = buffer.copyOf(fill)
                val id = sessionId
                val r = if (id == null) {
                    send("/2/files/upload", commitArg(target), bytes)
                } else {
                    val arg = buildJsonObject {
                        put("cursor", cursor(id))
                        put("commit", commitArg(target))
                    }
                    send("/2/files/upload_session/finish", arg, bytes)
                }
                if (r.status != 200) failure(r, path, null)
                checkCommitted(r, path, target, written)
            }
        }

        private inline fun guard(block: () -> Unit) {
            try {
                mapped(path, block)
            } catch (e: StorageException) {
                failed = true
                throw e
            }
        }
    }

    override suspend fun rename(fromRel: String, toRel: String) {
        val from = segments(fromRel)
        val to = segments(toRel)
        require(from.isNotEmpty() && to.isNotEmpty())
        io(kpath(from)) {
            if (metadata(from) == null) throw StorageException.NotFound(kpath(from))
            if (metadata(to) != null) throw StorageException.AlreadyExists(kpath(to))
            try {
                rpc(
                    "/2/files/move_v2",
                    buildJsonObject {
                        put("from_path", dbx(from))
                        put("to_path", dbx(to))
                        put("autorename", false)
                        put("allow_ownership_transfer", false)
                    },
                    kpath(from),
                    readOnly = false,
                )
            } catch (e: DropboxApiError) {
                throw when {
                    e.summary.startsWith("to/") && "conflict" in e.summary -> {
                        StorageException.AlreadyExists(kpath(to))
                    }

                    e.summary.startsWith("from_lookup/not_found") -> {
                        StorageException.NotFound(kpath(from))
                    }

                    else -> {
                        e
                    }
                }
            }
        }
    }

    override suspend fun stat(rel: String): Stat? {
        val segs = segments(rel)
        return io(kpath(segs)) { metadata(segs)?.let { toStat(segs, it) } }
    }

    override suspend fun openRead(rel: String): InputStream {
        val segs = segments(rel)
        val path = kpath(segs)
        require(segs.isNotEmpty() || base.isNotEmpty()) { "the App folder is not a file" }
        val (r, size) = io(path) {
            val r = call(
                content,
                "/2/files/download",
                listOf(argHeader(buildJsonObject { put("path", dbx(segs)) })),
                ByteArray(0),
                readOnly = true,
                path = path,
            )
            if (r.status != 200) {
                try {
                    failure(r, path, null)
                } catch (e: DropboxApiError) {
                    throw when {
                        e.summary.startsWith("path/not_found") -> StorageException.NotFound(path)
                        e.summary.startsWith("path/not_file") -> StorageException.IsADirectory(path)
                        else -> e
                    }
                }
            }
            // The file's metadata comes in a header; it must describe this file, and its size bounds the body.
            val meta = try {
                r.header("dropbox-api-result")
                    ?.let { DropboxJson.fileEntry(DropboxJson.parse(it.toByteArray(Charsets.ISO_8859_1))) }
            } catch (e: DropboxJsonException) {
                null
            }
            if (meta == null || meta.isFolder || meta.pathLower != DropboxJson.fold(dbx(segs))) {
                r.close()
                throw DropboxJsonException("the download didn't describe the requested file")
            }
            r to meta.size
        }
        return object : FilterInputStream(r.body) {
            private var left = size

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = mapped(path) {
                if (len == 0) return@mapped 0
                // Never more than the size Dropbox announced (SEC: an endless body can't stall a hash forever).
                val n = `in`.read(b, off, if (left == 0L) 1 else minOf(len.toLong(), left).toInt())
                if (n > 0 && left == 0L) throw HttpProtocolException("more bytes than the file has")
                if (n < 0 && left > 0) throw HttpProtocolException("the download ended early")
                if (n > 0) left -= n
                n
            }

            override fun close() = mapped(path) { `in`.close() }
        }
    }

    override suspend fun delete(rel: String) {
        val segs = segments(rel)
        require(segs.isNotEmpty()) { "never the folder itself" }
        val path = kpath(segs)
        io(path) {
            val e = metadata(segs) ?: throw StorageException.NotFound(path)
            // Dropbox deletes a folder with everything in it, and `parent_rev` doesn't apply to folders: a file
            // another device adds between an emptiness check and the delete would go too. So folders are never
            // deleted here (caps.canDeleteFolders = false). A file is deleted only in the revision just seen
            // (parent_rev), never a newer one written meanwhile.
            if (e.isFolder) throw StorageException.Unsupported(path, "folders are never deleted on Dropbox")
            val rev = e.rev ?: throw StorageException.Io(path, message = "no revision to delete")
            try {
                rpc(
                    "/2/files/delete_v2",
                    buildJsonObject {
                        put("path", dbx(segs))
                        put("parent_rev", rev)
                    },
                    path,
                    readOnly = false,
                )
            } catch (err: DropboxApiError) {
                if (err.summary.startsWith("path_lookup/not_found")) throw StorageException.NotFound(path)
                throw err
            }
        }
    }

    /** Direct children of [segs], following `list_folder/continue`; entries outside the folder are ignored. */
    private fun children(segs: List<String>): List<DbxEntry> {
        val p = dbx(segs)
        val parent = DropboxJson.fold(p)
        val path = kpath(segs)
        val out = LinkedHashMap<String, DbxEntry>()
        var o = try {
            rpc(
                "/2/files/list_folder",
                buildJsonObject {
                    put("path", p)
                    put("recursive", false)
                    put("include_deleted", false)
                    put("limit", PAGE_LIMIT)
                },
                path,
                readOnly = true,
            )
        } catch (e: DropboxApiError) {
            throw when {
                e.summary.startsWith("path/not_found") -> StorageException.NotFound(path)
                e.summary.startsWith("path/not_folder") -> StorageException.NotADirectory(path)
                else -> e
            }
        }
        var pages = 1
        while (true) {
            val entries = o.array("entries") ?: throw DropboxJsonException("no entries")
            for (element in entries) {
                val entry = (element as? JsonObject)?.let(DropboxJson::entry) ?: continue
                // Only direct children of this folder (SEC: Dropbox's answer is untrusted).
                if (entry.pathLower != parent + "/" + DropboxJson.fold(entry.name)) continue
                out.putIfAbsent(DropboxJson.fold(entry.name), entry)
                if (out.size > MAX_ENTRIES) throw DropboxJsonException("too many entries")
            }
            if (o.bool("has_more") != true) return out.values.toList()
            val cursor =
                o.string("cursor")?.takeIf { it.length in 1..MAX_CURSOR } ?: throw DropboxJsonException("no cursor")
            if (++pages > MAX_PAGES) throw DropboxJsonException("too many pages")
            o = rpc("/2/files/list_folder/continue", buildJsonObject { put("cursor", cursor) }, path, readOnly = true)
        }
    }

    override suspend fun list(relDir: String): List<Stat> {
        val segs = segments(relDir)
        return io(kpath(segs)) { children(segs).map { toStat(segs + it.name, it) } }
    }

    /**
     * Dropbox's `content_hash` of the file now at [rel], as `get_metadata` reports it (computed by Dropbox from
     * the stored bytes; never a value KeepClear sent). Null for another kind, a folder, or a missing/malformed
     * value.
     */
    override suspend fun serverDigest(rel: String, kind: DigestKind): String? {
        if (kind != DigestKind.DROPBOX_CONTENT_HASH) return null
        val segs = segments(rel)
        return io(kpath(segs)) { metadata(segs)?.takeIf { !it.isFolder }?.contentHash }
    }

    /** Drops every connection; the next call connects again (a fresh read, FreshRead.NEW_SESSION). */
    override suspend fun close() {
        api.close()
        content.close()
    }

    override fun toString() = "DropboxBackupTarget($label)"

    companion object {
        /** Dropbox: "Do not use this to upload a file larger than 150 MB" (decimal, to be safe either way). */
        const val SINGLE_UPLOAD_MAX = 150_000_000L

        /** Upload session chunk: a multiple of Dropbox's 4 MiB block, small enough to keep in memory. */
        const val CHUNK_SIZE = 8 * 1024 * 1024

        /** Largest file the API accepts through upload sessions (350 GB). */
        const val MAX_FILE_SIZE = 350_000_000_000L

        private const val INITIAL_BUFFER = 64 * 1024
        private const val MAX_ERROR_BYTES = 64 * 1024

        /** Metadata and listing pages; a page of 2,000 entries is far smaller. Anything larger is refused (SEC). */
        const val MAX_RPC_BYTES = 4 * 1024 * 1024
        private const val PAGE_LIMIT = 2_000
        private const val MAX_PAGES = 1_000
        private const val MAX_ENTRIES = 500_000
        private const val MAX_CURSOR = 4_096
        private val JSON = "Content-Type" to "application/json"
        private val OCTETS = "Content-Type" to "application/octet-stream"

        fun statusError(status: Int, path: KPath): StorageException = when (status) {
            401, 403 -> StorageException.PermissionDenied(path)

            // Busy, rate-limited, in maintenance: gone for now, try later.
            408, 429, 500, 502, 503, 504 -> StorageException.Disconnected(path)

            else -> StorageException.Io(path, message = "Dropbox answered $status")
        }

        /** Maps a 409 `error_summary` (e.g. `path/conflict/file/..`) to a storage error. */
        fun apiError(summary: String, path: KPath): StorageException = when {
            "insufficient_space" in summary -> {
                StorageException.NoSpace(path)
            }

            "too_many_write_operations" in summary -> {
                StorageException.Disconnected(path, DropboxRetryAfterException(null))
            }

            "conflict" in summary -> {
                StorageException.AlreadyExists(path)
            }

            "not_found" in summary -> {
                StorageException.NotFound(path)
            }

            "not_folder" in summary -> {
                StorageException.NotADirectory(path)
            }

            "not_file" in summary -> {
                StorageException.IsADirectory(path)
            }

            "no_write_permission" in summary -> {
                StorageException.PermissionDenied(path)
            }

            else -> {
                StorageException.Io(path, message = "Dropbox refused: ${summary.take(80)}")
            }
        }
    }
}
