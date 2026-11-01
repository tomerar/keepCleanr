// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.DigestKind
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.backup.net.HttpLengthMismatchException
import io.github.tomerar.keepclear.backup.net.HttpProtocolException
import io.github.tomerar.keepclear.backup.net.HttpResponse
import io.github.tomerar.keepclear.backup.net.HttpStatusException
import io.github.tomerar.keepclear.backup.net.MiniHttp
import io.github.tomerar.keepclear.backup.net.UploadStream
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocketFactory

/**
 * M17 (MOVE-3), ADR 0011: a backup target on a WebDAV server: Nextcloud, or any other WebDAV server.
 *
 * - **Verification.** On Nextcloud ([WebDavLocation.nextcloud]) every upload asks the server to hash what it
 *   stores (`X-Hash: sha256`); the `X-Hash-SHA256` it answers is offered through [serverDigest] and is the proof
 *   (ADR 0011). Client-supplied checksums (`OC-Checksum`, `oc:checksums`) are never sent or read. Without that
 *   header (any other server, or a Nextcloud that didn't answer it) the copy is read back with GET; every read
 *   comes from the server, never from a cache on the phone (`readsFromServer`, FreshRead.NEW_SESSION after
 *   [close]).
 * - **Never overwrite.** PUT carries `If-None-Match: *` after a PROPFIND found nothing there; MOVE carries
 *   `Overwrite: F` after a PROPFIND found the destination free. The move engine writes to a temp name and moves
 *   it to the final one.
 * - **Untrusted server** (SEC): no redirects followed, bounded responses, hrefs outside the folder ignored,
 *   XML without DTDs or entities (see [DavXml]).
 * - Every failure becomes a [StorageException]: no answer is [StorageException.Disconnected] (a job pauses and
 *   nothing is deleted, E-MOVE-2); 401/403 is [StorageException.PermissionDenied]; 507 is
 *   [StorageException.NoSpace].
 */
class WebDavBackupTarget(
    private val location: WebDavLocation,
    override val id: String,
    override val label: String,
    timeoutSeconds: Long = 30,
    sslSocketFactory: SSLSocketFactory? = null,
) : BackupTarget {
    override val caps = TargetCaps(
        canReadBack = true,
        // Nextcloud on Linux is case-sensitive, but other WebDAV servers (IIS, NAS on a FAT/NTFS disk) aren't.
        caseSensitive = false,
        readsFromServer = true,
        serverDigests = if (location.nextcloud) setOf(DigestKind.SHA256) else emptySet(),
    )

    private val http = MiniHttp(
        location.https,
        location.host,
        location.port,
        (timeoutSeconds * 1000).toInt(),
        sslSocketFactory,
    )
    private val scope = HrefScope(location.https, location.host, location.port, location.baseSegments)
    private val auth =
        "Basic " + Base64.getEncoder().encodeToString("${location.user}:${location.password}".toByteArray())

    /** Server-computed SHA-256 per target-relative path, for files this object uploaded (followed by rename). */
    private val digests = ConcurrentHashMap<String, String>()

    private fun segments(rel: String): List<String> {
        require(!rel.startsWith("/")) { "relative path expected" }
        val segs = rel.split('/').filter { it.isNotEmpty() }
        require(segs.all(DavPath::isSafeSegment)) { "path segment not allowed" }
        return segs
    }

    private fun key(rel: String) = segments(rel).joinToString("/")

    private fun urlPath(segs: List<String>, collection: Boolean = false) =
        DavPath.encode(location.baseSegments + segs, trailingSlash = collection)

    private fun kpath(segs: List<String>): KPath.Local = KPath.Local("/" + segs.joinToString("/"))

    private fun headers(vararg extra: Pair<String, String>) = listOf("Authorization" to auth) + extra

    /** Runs [block] on the IO dispatcher and maps every failure to a [StorageException]. */
    private suspend fun <T> io(segs: List<String>, block: () -> T): T = withContext(Dispatchers.IO) {
        mapped(kpath(segs), block)
    }

    private inline fun <T> mapped(path: KPath, block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (e: HttpStatusException) {
        throw statusError(e.status, path)
    } catch (e: HttpProtocolException) {
        throw StorageException.Io(path, e, "the server's answer was not understood")
    } catch (e: DavXmlException) {
        throw StorageException.Io(path, e, "the server's answer was not understood")
    } catch (e: IOException) {
        // Refused, timed out, dropped Wi-Fi, certificate refused: the place is gone for now (pause, never delete).
        throw StorageException.Disconnected(path, e)
    }

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

    private fun propfind(segs: List<String>, depth: Int, collection: Boolean): List<DavEntry>? {
        var path = urlPath(segs, collection)
        repeat(2) {
            val r = http.send("PROPFIND", path, headers("Depth" to depth.toString(), CONTENT_XML), PROPFIND_BODY)
            when (r.status) {
                207 -> {
                    return DavXml.parseMultistatus(r.readBody(MAX_PROPFIND_BYTES), scope)
                }

                404 -> {
                    discard(r)
                    return null
                }

                301, 302, 307, 308 -> {
                    // Some servers redirect a folder to its name with a slash. Only that one redirect is taken,
                    // on the same server; anything else is refused (ADR 0011: no other hosts).
                    val loc = r.header("location")
                    discard(r)
                    val target = loc?.let { scope.relative(it) }
                    if (it == 0 && target == segs && !path.endsWith("/")) {
                        path = urlPath(segs, collection = true)
                    } else {
                        throw HttpStatusException(r.status)
                    }
                }

                else -> {
                    discard(r)
                    throw HttpStatusException(r.status)
                }
            }
        }
        throw HttpProtocolException("redirected more than once")
    }

    /** Depth-0 PROPFIND: the entry for exactly [segs], or null when it doesn't exist. */
    private fun statEntry(segs: List<String>): DavEntry? {
        val entries = propfind(segs, 0, collection = segs.isEmpty()) ?: return null
        return entries.firstOrNull { it.rel == segs }
            ?: throw HttpProtocolException("the answer didn't describe the requested item")
    }

    private fun toStat(e: DavEntry) = Stat(
        path = kpath(e.rel),
        name = e.rel.lastOrNull() ?: "",
        kind = if (e.isCollection) Stat.Kind.DIRECTORY else Stat.Kind.FILE,
        size = e.size,
        mtimeMillis = e.mtimeMillis,
        identity = e.etag,
    )

    override suspend fun freeBytes(): Long? = io(emptyList()) {
        propfind(emptyList(), 0, collection = true)?.firstOrNull { it.rel.isEmpty() }?.quotaAvailableBytes
    }

    override suspend fun mkdirs(rel: String) {
        val segs = segments(rel)
        io(segs) {
            val here = statEntry(segs)
            if (here != null) {
                if (!here.isCollection) throw StorageException.NotADirectory(kpath(segs))
                return@io
            }
            // Walk up to the deepest folder that exists (inside or above the base folder), then create downwards.
            val all = location.baseSegments + segs
            var existing = all.size - 1
            while (existing > 0) {
                when (absoluteStat(all.subList(0, existing))) {
                    null -> existing--
                    true -> break
                    false -> throw StorageException.NotADirectory(relKPath(all.subList(0, existing)))
                }
            }
            for (end in existing + 1..all.size) mkcol(all.subList(0, end))
        }
    }

    /** True/false = collection/file at absolute [abs], null = nothing there. Only used while creating folders. */
    private fun absoluteStat(abs: List<String>): Boolean? {
        if (abs.size < location.baseSegments.size) {
            // Above the base folder: its entry can't be in scope; ask with a slash and only check it exists.
            val r = http.send(
                "PROPFIND",
                DavPath.encode(abs, trailingSlash = true),
                headers("Depth" to "0", CONTENT_XML),
                PROPFIND_BODY,
            )
            discard(r)
            return when (r.status) {
                207 -> true
                404 -> null
                else -> throw HttpStatusException(r.status)
            }
        }
        return statEntry(abs.drop(location.baseSegments.size))?.isCollection
    }

    private fun relKPath(abs: List<String>) = kpath(abs.drop(location.baseSegments.size.coerceAtMost(abs.size)))

    private fun mkcol(abs: List<String>) {
        val r = http.send("MKCOL", DavPath.encode(abs, trailingSlash = true), headers())
        discard(r)
        when (r.status) {
            201 -> Unit

            // Already there (someone else made it at the same moment): fine only if it is a folder.
            405 -> if (absoluteStat(abs) != true) throw StorageException.NotADirectory(relKPath(abs))

            409 -> throw StorageException.NotFound(relKPath(abs))

            else -> throw HttpStatusException(r.status)
        }
    }

    /**
     * Without a known size the body is sent chunked; closing the stream completes the upload, so callers that
     * fail mid-write must not keep the file (the move engine writes data with a size, and to a temp name).
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
        val k = key(rel)
        val upload = io(segs) {
            // Never overwrite (MOVE-6): checked here and again by the server through If-None-Match.
            if (statEntry(segs) != null) throw StorageException.AlreadyExists(path)
            val extra = mutableListOf("If-None-Match" to "*", "Content-Type" to "application/octet-stream")
            if (location.nextcloud) extra += "X-Hash" to "sha256"
            http.upload("PUT", urlPath(segs), headers(*extra.toTypedArray()), size)
        }
        digests.remove(k)
        return object : UploadStream(upload) {
            private var closed = false

            override fun write(b: ByteArray, off: Int, len: Int) = mapped(path) {
                try {
                    super.write(b, off, len)
                } catch (e: HttpLengthMismatchException) {
                    removeAbandonedUpload(segs, size)
                    throw e
                }
            }

            override fun flush() = mapped(path) { super.flush() }

            override fun close() {
                if (closed) return
                closed = true
                mapped(path) {
                    val r = try {
                        upload.finish()
                    } catch (e: HttpLengthMismatchException) {
                        removeAbandonedUpload(segs, size)
                        throw e
                    }
                    val hash = r.header("x-hash-sha256")
                    discard(r)
                    when (r.status) {
                        200, 201, 204 -> {
                            // ADR 0011: only the hash the server computed, and only when we asked Nextcloud for it.
                            val sha = hash?.trim()?.lowercase()?.takeIf { SHA256_HEX.matches(it) }
                            if (location.nextcloud && sha != null) digests[k] = sha
                        }

                        412 -> {
                            throw StorageException.AlreadyExists(path)
                        }

                        else -> {
                            throw HttpStatusException(r.status)
                        }
                    }
                }
            }
        }
    }

    /**
     * An upload whose length didn't match was abandoned before its last byte, so a correct server stores nothing.
     * A server that keeps partial uploads anyway may leave a file under [segs]: it can only be ours (the name was
     * free and the PUT carried `If-None-Match: *`), never larger than announced, so it is removed. Best effort:
     * the caller's error is what counts, and the move engine deletes its temp file again on resume (E-MOVE-1).
     */
    private fun removeAbandonedUpload(segs: List<String>, size: Long?) {
        runCatching {
            val e = statEntry(segs) ?: return
            if (e.isCollection || (size != null && e.size > size)) return
            discard(http.send("DELETE", urlPath(segs), headers()))
        }
    }

    override suspend fun rename(fromRel: String, toRel: String) {
        val from = segments(fromRel)
        val to = segments(toRel)
        io(from) {
            val source = statEntry(from) ?: throw StorageException.NotFound(kpath(from))
            if (statEntry(to) != null) throw StorageException.AlreadyExists(kpath(to))
            val r = http.send(
                "MOVE",
                urlPath(from, source.isCollection),
                headers(
                    "Destination" to http.absolute(urlPath(to, source.isCollection)),
                    "Overwrite" to "F",
                ),
            )
            discard(r)
            when (r.status) {
                201, 204 -> Unit
                412 -> throw StorageException.AlreadyExists(kpath(to))
                404, 409 -> throw StorageException.NotFound(kpath(from))
                else -> throw HttpStatusException(r.status)
            }
        }
        digests.remove(key(fromRel))?.let { digests[key(toRel)] = it }
    }

    override suspend fun stat(rel: String): Stat? {
        val segs = segments(rel)
        return io(segs) { statEntry(segs)?.let(::toStat) }
    }

    override suspend fun openRead(rel: String): InputStream {
        val segs = segments(rel)
        val path = kpath(segs)
        val r = io(segs) {
            val e = statEntry(segs) ?: throw StorageException.NotFound(path)
            if (e.isCollection) throw StorageException.IsADirectory(path)
            val r = http.send("GET", urlPath(segs), headers())
            when (r.status) {
                200 -> {
                    r
                }

                404 -> {
                    discard(r)
                    throw StorageException.NotFound(path)
                }

                else -> {
                    discard(r)
                    throw HttpStatusException(r.status)
                }
            }
        }
        return object : FilterInputStream(r.body) {
            override fun read(): Int = mapped(path) { `in`.read() }

            override fun read(b: ByteArray, off: Int, len: Int): Int = mapped(path) { `in`.read(b, off, len) }

            override fun close() = mapped(path) { `in`.close() }
        }
    }

    override suspend fun delete(rel: String) {
        val segs = segments(rel)
        require(segs.isNotEmpty()) { "never the base folder" }
        val path = kpath(segs)
        io(segs) {
            val e = statEntry(segs) ?: throw StorageException.NotFound(path)
            // WebDAV deletes a folder with everything in it; KeepClear only ever deletes empty ones.
            if (e.isCollection && children(segs).isNotEmpty()) throw StorageException.NotEmpty(path)
            val r = http.send("DELETE", urlPath(segs, e.isCollection), headers())
            discard(r)
            when (r.status) {
                200, 204 -> Unit
                404 -> throw StorageException.NotFound(path)
                else -> throw HttpStatusException(r.status)
            }
        }
        digests.remove(key(rel))
    }

    private fun children(segs: List<String>): List<DavEntry> {
        val entries = propfind(segs, 1, collection = true) ?: throw StorageException.NotFound(kpath(segs))
        val self = entries.firstOrNull { it.rel == segs }
        if (self != null && !self.isCollection) throw StorageException.NotADirectory(kpath(segs))
        return entries
            .filter { it.rel.size == segs.size + 1 && it.rel.subList(0, segs.size) == segs }
            .distinctBy { it.rel.last() }
    }

    override suspend fun list(relDir: String): List<Stat> {
        val segs = segments(relDir)
        return io(segs) { children(segs).map(::toStat) }
    }

    override suspend fun serverDigest(rel: String, kind: DigestKind): String? =
        if (kind == DigestKind.SHA256 && location.nextcloud) digests[key(rel)] else null

    /** Drops every connection; the next call connects again (a fresh read, FreshRead.NEW_SESSION). */
    override suspend fun close() = http.close()

    companion object {
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val CONTENT_XML = "Content-Type" to "application/xml; charset=utf-8"

        /** Folder listings of tens of thousands of files fit; anything larger is refused (SEC). */
        const val MAX_PROPFIND_BYTES = 32 * 1024 * 1024

        private val PROPFIND_BODY = (
            """<?xml version="1.0" encoding="utf-8"?>""" +
                """<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/>""" +
                """<d:getlastmodified/><d:getetag/><d:quota-available-bytes/></d:prop></d:propfind>"""
        ).toByteArray(Charsets.UTF_8)

        fun statusError(status: Int, path: KPath): StorageException = when (status) {
            401, 403 -> StorageException.PermissionDenied(path)

            404, 409, 410 -> StorageException.NotFound(path)

            412 -> StorageException.AlreadyExists(path)

            507 -> StorageException.NoSpace(path)

            // Busy, in maintenance or a proxy that can't reach it: gone for now, try later.
            408, 429, 502, 503, 504 -> StorageException.Disconnected(path)

            else -> StorageException.Io(path, message = "the server answered $status")
        }
    }
}
