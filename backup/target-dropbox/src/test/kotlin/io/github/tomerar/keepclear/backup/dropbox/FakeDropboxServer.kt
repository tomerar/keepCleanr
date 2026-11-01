// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.dropbox

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.tomerar.keepclear.core.hash.DropboxContentHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.Locale
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-process Dropbox API v2, just enough for KeepClear (M17c): the App folder as root, case-insensitive paths,
 * `files` RPCs on JSON bodies, content endpoints with the argument in the `Dropbox-API-Arg` header, upload
 * sessions, short-lived access tokens with a refresh-token grant, and switches for the ways a real server can fail
 * or lie. One port serves both "hosts" ([DropboxEndpoints.loopback]).
 */
class FakeDropboxServer : AutoCloseable {
    enum class HashMode {
        /** `content_hash` of what is stored (honest Dropbox). */
        STORED,

        /** Of what was received, even if something else was stored (lies about a later corruption). */
        RECEIVED,

        /** A well-formed but wrong hash. */
        WRONG,

        /** No `content_hash` at all. */
        MISSING,

        /** A malformed value. */
        GARBAGE,
    }

    private sealed interface Node {
        val display: String

        class Folder(override val display: String) : Node

        class File(
            override val display: String,
            val bytes: ByteArray,
            val receivedHash: String,
            val rev: String,
            val mtime: Long,
        ) : Node
    }

    private class Session(val bytes: ByteArrayOutputStream = ByteArrayOutputStream(), var closed: Boolean = false)

    private val json = Json
    private val lock = Any()

    /** Lower-cased path ("/a/b") → node. The App folder ("") is implicit. */
    private val nodes = TreeMap<String, Node>()
    private val sessions = HashMap<String, Session>()
    private val revs = AtomicLong(0x10000000000L)

    /** Every request as "endpoint path-or-arg-summary". */
    val requests = CopyOnWriteArrayList<String>()

    val appKey = "fake-app-key"
    val refreshToken = "fake-refresh-token-1"

    /** Access tokens Dropbox accepts now. */
    private val validTokens = ConcurrentHashMap.newKeySet<String>()

    /** The refresh grant is refused (`invalid_grant`): the user revoked KeepClear. */
    @Volatile var refreshRefused = false

    @Volatile var hashMode = HashMode.STORED

    /** Changes what is stored (truncate, flip bits) after receiving it. */
    @Volatile var corruptStored: ((ByteArray) -> ByteArray)? = null

    /** Total bytes the account may hold; an upload past it gets `insufficient_space`. */
    @Volatile var quotaBytes: Long? = null

    /** Read this many bytes of the next upload body, then drop the connection without answering. */
    @Volatile var dropUploadAfter: Int? = null

    /** Answer the next N requests (after authentication) with 429 and this Retry-After. */
    @Volatile var rateLimitNext = 0

    @Volatile var retryAfterSeconds: String? = "7"

    /** Answer the next N requests with 503. */
    @Volatile var serverErrorNext = 0

    /** Add entries outside the listed folder (`..`, other folders, bad names) to every listing page. */
    @Volatile var hostileEntries = false

    /** Answer list_folder with a body of this many bytes. */
    @Volatile var hugeListBytes: Int? = null

    /** Answer get_metadata with JSON nested this deep. */
    @Volatile var deepJson: Int? = null

    /** Answer get_metadata for this lower-cased path with another path's metadata. */
    @Volatile var metadataForOtherPath = false

    /** Send the download body longer than the size in Dropbox-API-Result. */
    @Volatile var longDownload = false

    /** Entries per list_folder page (small, so `continue` is exercised). */
    @Volatile var pageSize = 3

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    val port: Int get() = server.address.port

    val endpoints: DropboxEndpoints get() = DropboxEndpoints.loopback(port)

    /** The production auth code against this server's token endpoint. */
    fun auth(): RefreshingDropboxAuth = RefreshingDropboxAuth(appKey, refreshToken, endpoints, timeoutSeconds = 5)

    init {
        server.executor = executor
        server.createContext("/") { ex ->
            handle(ex)
            ex.close()
        }
        server.start()
    }

    /** Thrown from a handler: the server closes the connection without an answer. */
    private class DropConnection : RuntimeException()

    // ------------------------------------------------------------------------------------------ test helpers

    private fun key(path: String) = path.trim('/').let { if (it.isEmpty()) "" else "/$it" }.lowercase(Locale.ROOT)

    fun bytes(path: String): ByteArray? = synchronized(lock) { (nodes[key(path)] as? Node.File)?.bytes }

    fun exists(path: String): Boolean = synchronized(lock) { nodes.containsKey(key(path)) }

    fun isFolder(path: String): Boolean = synchronized(lock) { nodes[key(path)] is Node.Folder }

    fun putFile(path: String, bytes: ByteArray) = synchronized(lock) {
        val display = "/" + path.trim('/')
        makeParents(key(path), display)
        nodes[key(path)] = Node.File(display, bytes, DropboxContentHash.of(bytes), newRev(), System.currentTimeMillis())
    }

    /** Lower-cased paths of all files. */
    fun files(): List<String> = synchronized(lock) { nodes.filter { it.value is Node.File }.keys.toList() }

    /** Invalidates every access token (they expired). */
    fun expireAccessTokens() = validTokens.clear()

    fun openSessions(): Int = synchronized(lock) { sessions.size }

    private fun newRev() = "%015x".format(revs.incrementAndGet())

    // ---------------------------------------------------------------------------------------------- handling

    private fun handle(ex: HttpExchange) {
        val endpoint = ex.requestURI.path
        if (endpoint == "/oauth2/token") return token(ex)
        val auth = ex.requestHeaders.getFirst("Authorization")
        if (auth == null || !auth.startsWith("Bearer ") || auth.removePrefix("Bearer ") !in validTokens) {
            ex.requestBody.readAllBytes()
            requests += "$endpoint 401"
            return error(ex, 401, "expired_access_token/")
        }
        if (rateLimitNext > 0) {
            rateLimitNext--
            ex.requestBody.readAllBytes()
            requests += "$endpoint 429"
            retryAfterSeconds?.let { ex.responseHeaders.add("Retry-After", it) }
            return error(ex, 429, "too_many_requests/")
        }
        if (serverErrorNext > 0) {
            serverErrorNext--
            ex.requestBody.readAllBytes()
            requests += "$endpoint 503"
            return reply(ex, 503, "busy".toByteArray())
        }
        val headerArg = ex.requestHeaders.getFirst("Dropbox-API-Arg")
        if (headerArg != null) {
            val arg = json.parseToJsonElement(headerArg).jsonObject
            requests += "$endpoint ${arg.str("path") ?: arg["cursor"]?.jsonObject?.str("session_id") ?: ""}"
            when (endpoint) {
                "/2/files/upload" -> upload(ex, arg)
                "/2/files/upload_session/start" -> sessionStart(ex)
                "/2/files/upload_session/append_v2" -> sessionAppend(ex, arg)
                "/2/files/upload_session/finish" -> sessionFinish(ex, arg)
                "/2/files/download" -> download(ex, arg)
                else -> reply(ex, 404, ByteArray(0))
            }
            return
        }
        val body = ex.requestBody.readAllBytes()
        if (ex.requestHeaders.getFirst("Content-Type") != "application/json") return reply(ex, 400, ByteArray(0))
        val arg = json.parseToJsonElement(body.toString(Charsets.UTF_8))
        val o = arg as? JsonObject ?: JsonObject(emptyMap())
        requests += "$endpoint ${o.str("path") ?: o.str("from_path") ?: ""}"
        when (endpoint) {
            "/2/files/get_metadata" -> getMetadata(ex, o)
            "/2/files/list_folder" -> listFolder(ex, o)
            "/2/files/list_folder/continue" -> listContinue(ex, o)
            "/2/files/create_folder_v2" -> createFolder(ex, o)
            "/2/files/move_v2" -> move(ex, o)
            "/2/files/delete_v2" -> delete(ex, o)
            "/2/users/get_space_usage" -> spaceUsage(ex)
            else -> reply(ex, 404, ByteArray(0))
        }
    }

    private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun reply(ex: HttpExchange, status: Int, body: ByteArray) {
        if (body.isEmpty()) {
            ex.sendResponseHeaders(status, -1)
        } else {
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.write(body)
        }
    }

    private fun ok(ex: HttpExchange, o: JsonElement) {
        ex.responseHeaders.add("Content-Type", "application/json")
        reply(ex, 200, o.toString().toByteArray(Charsets.UTF_8))
    }

    private fun error(ex: HttpExchange, status: Int, summary: String) {
        val o = buildJsonObject {
            put("error_summary", summary)
            put("error", buildJsonObject { put(".tag", summary.substringBefore('/')) })
        }
        ex.responseHeaders.add("Content-Type", "application/json")
        reply(ex, status, o.toString().toByteArray(Charsets.UTF_8))
    }

    private fun token(ex: HttpExchange) {
        val form = ex.requestBody.readAllBytes().toString(Charsets.US_ASCII).split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        requests += "/oauth2/token ${form["grant_type"]}"
        val good = form["grant_type"] == "refresh_token" && form["refresh_token"] == refreshToken &&
            form["client_id"] == appKey && !refreshRefused
        if (!good) {
            val o = buildJsonObject {
                put("error", "invalid_grant")
                put("error_description", "refresh token is invalid or revoked")
            }
            return reply(ex, 400, o.toString().toByteArray())
        }
        val access = "sl.fake-" + UUID.randomUUID()
        validTokens += access
        ok(
            ex,
            buildJsonObject {
                put("access_token", access)
                put("token_type", "bearer")
                put("expires_in", 14_400)
            },
        )
    }

    // ------------------------------------------------------------------------------------------- metadata

    private fun hashFor(node: Node.File): String? = when (hashMode) {
        HashMode.STORED -> DropboxContentHash.of(node.bytes)
        HashMode.RECEIVED -> node.receivedHash
        HashMode.WRONG -> DropboxContentHash.of(node.bytes + 1)
        HashMode.MISSING -> null
        HashMode.GARBAGE -> "not-a-hash"
    }

    /**
     * Metadata as Dropbox sends it. [tagged]: a polymorphic `Metadata` (get_metadata, list_folder, move_v2,
     * delete_v2) carries `.tag`; a concrete `FileMetadata`/`FolderMetadata` (upload, upload_session/finish, the
     * download's Dropbox-API-Result, create_folder_v2) has none (files.stone).
     */
    private fun meta(lower: String, node: Node, tagged: Boolean = true): JsonObject = buildJsonObject {
        val name = node.display.substringAfterLast('/')
        when (node) {
            is Node.Folder -> {
                if (tagged) put(".tag", "folder")
                put("name", name)
                put("path_lower", lower)
                put("path_display", node.display)
                put("id", "id:" + lower.hashCode())
            }

            is Node.File -> {
                if (tagged) put(".tag", "file")
                put("name", name)
                put("path_lower", lower)
                put("path_display", node.display)
                put("id", "id:" + lower.hashCode())
                put("client_modified", Instant.ofEpochSecond(node.mtime / 1000).toString())
                put("server_modified", Instant.ofEpochSecond(node.mtime / 1000).toString())
                put("rev", node.rev)
                put("size", node.bytes.size)
                put("is_downloadable", true)
                hashFor(node)?.let { put("content_hash", it) }
            }
        }
    }

    private fun parentOf(lower: String) = lower.substringBeforeLast('/')

    /** Dropbox creates missing parent folders; a file in the way is a conflict. Returns false on a conflict. */
    private fun makeParents(lower: String, display: String): Boolean {
        val segs = lower.split('/').filter { it.isNotEmpty() }
        val displaySegs = display.split('/').filter { it.isNotEmpty() }
        var p = ""
        var d = ""
        for (i in 0 until segs.size - 1) {
            p += "/" + segs[i]
            d += "/" + displaySegs[i]
            when (nodes[p]) {
                null -> nodes[p] = Node.Folder(d)
                is Node.File -> return false
                is Node.Folder -> Unit
            }
        }
        return true
    }

    private fun getMetadata(ex: HttpExchange, o: JsonObject) {
        val path = o.str("path") ?: return error(ex, 400, "bad")
        deepJson?.let { depth -> return reply(ex, 200, ("[".repeat(depth) + "]".repeat(depth)).toByteArray()) }
        if (path.isEmpty()) return error(ex, 400, "path/unsupported_root") // like Dropbox: not for the root
        synchronized(lock) {
            val lower = key(path)
            val node = nodes[lower] ?: return error(ex, 409, "path/not_found/")
            if (metadataForOtherPath) return ok(ex, meta("/somewhere/else", node))
            ok(ex, meta(lower, node))
        }
    }

    private var cursors = HashMap<String, Pair<List<JsonObject>, Int>>()

    private fun listFolder(ex: HttpExchange, o: JsonObject) {
        hugeListBytes?.let { size -> return reply(ex, 200, ByteArray(size) { ' '.code.toByte() }) }
        val path = o.str("path") ?: return error(ex, 400, "bad")
        val entries = synchronized(lock) {
            val lower = key(path)
            if (lower.isNotEmpty()) {
                when (nodes[lower]) {
                    null -> return error(ex, 409, "path/not_found/")
                    is Node.File -> return error(ex, 409, "path/not_folder/")
                    is Node.Folder -> Unit
                }
            }
            val children = nodes.filterKeys { it != lower && parentOf(it) == lower }.map { (k, v) -> meta(k, v) }
            if (!hostileEntries) {
                children
            } else {
                children + listOf(
                    hostile("..", "$lower/.."),
                    hostile("passwd", "$lower/../../etc/passwd"),
                    hostile("stolen.txt", "/other-folder/stolen.txt"),
                    hostile("deep.txt", "$lower/sub/deep.txt"),
                    hostile("a/b", "$lower/a/b"),
                    hostile("mismatch.txt", "$lower/other-name.txt"),
                    buildJsonObject { put(".tag", "file") },
                    buildJsonObject {
                        put(".tag", "deleted")
                        put("name", "gone")
                        put("path_lower", "$lower/gone")
                    },
                )
            }
        }
        page(ex, entries, 0)
    }

    private fun hostile(name: String, pathLower: String) = buildJsonObject {
        put(".tag", "file")
        put("name", name)
        put("path_lower", pathLower)
        put("size", 1)
        put("rev", "0123456789a")
        put("server_modified", "2026-01-01T00:00:00Z")
    }

    private fun page(ex: HttpExchange, entries: List<JsonObject>, from: Int) {
        val to = minOf(entries.size, from + pageSize)
        val more = to < entries.size
        val cursor = "cursor-" + UUID.randomUUID()
        if (more) synchronized(lock) { cursors[cursor] = entries to to }
        ok(
            ex,
            buildJsonObject {
                put("entries", buildJsonArray { entries.subList(from, to).forEach { add(it) } })
                put("cursor", cursor)
                put("has_more", more)
            },
        )
    }

    private fun listContinue(ex: HttpExchange, o: JsonObject) {
        val (entries, from) = synchronized(lock) { cursors.remove(o.str("cursor")) } ?: return error(ex, 409, "reset/")
        page(ex, entries, from)
    }

    private fun createFolder(ex: HttpExchange, o: JsonObject): Unit = synchronized(lock) {
        val path = o.str("path") ?: return error(ex, 400, "bad")
        val lower = key(path)
        when (nodes[lower]) {
            is Node.Folder -> return error(ex, 409, "path/conflict/folder/")
            is Node.File -> return error(ex, 409, "path/conflict/file/")
            null -> Unit
        }
        if (!makeParents(lower, "/" + path.trim('/'))) return error(ex, 409, "path/conflict/file/")
        val node = Node.Folder("/" + path.trim('/'))
        nodes[lower] = node
        ok(ex, buildJsonObject { put("metadata", meta(lower, node, tagged = false)) })
    }

    private fun move(ex: HttpExchange, o: JsonObject): Unit = synchronized(lock) {
        val from = key(o.str("from_path") ?: return error(ex, 400, "bad"))
        val toPath = o.str("to_path") ?: return error(ex, 400, "bad")
        val to = key(toPath)
        if (o["autorename"]?.let { (it as JsonPrimitive).booleanOrNull } != false) return error(ex, 400, "autorename")
        val node = nodes[from] ?: return error(ex, 409, "from_lookup/not_found/")
        if (nodes.containsKey(to)) return error(ex, 409, "to/conflict/file/")
        if (!makeParents(to, "/" + toPath.trim('/'))) return error(ex, 409, "to/conflict/file/")
        val moved = nodes.filterKeys { it == from || it.startsWith("$from/") }
        moved.keys.forEach { nodes.remove(it) }
        val toDisplay = "/" + toPath.trim('/')
        moved.forEach { (k, v) ->
            val display = toDisplay + v.display.substring(node.display.length)
            nodes[to + k.removePrefix(from)] = when (v) {
                is Node.Folder -> Node.Folder(display)
                is Node.File -> Node.File(display, v.bytes, v.receivedHash, v.rev, v.mtime)
            }
        }
        ok(ex, buildJsonObject { put("metadata", meta(to, nodes.getValue(to))) })
    }

    private fun delete(ex: HttpExchange, o: JsonObject): Unit = synchronized(lock) {
        val lower = key(o.str("path") ?: return error(ex, 400, "bad"))
        val node = nodes[lower] ?: return error(ex, 409, "path_lookup/not_found/")
        val parentRev = o.str("parent_rev")
        if (parentRev != null && (node !is Node.File || node.rev != parentRev)) {
            return error(ex, 409, "path_write/conflict/file/")
        }
        val meta = meta(lower, node)
        nodes.keys.filter { it == lower || it.startsWith("$lower/") }.forEach { nodes.remove(it) }
        ok(ex, buildJsonObject { put("metadata", meta) })
    }

    private fun used() = nodes.values.filterIsInstance<Node.File>().sumOf { it.bytes.size.toLong() }

    private fun spaceUsage(ex: HttpExchange): Unit = synchronized(lock) {
        val quota = quotaBytes ?: 2_000_000_000_000L
        ok(
            ex,
            buildJsonObject {
                put("used", used())
                put(
                    "allocation",
                    buildJsonObject {
                        put(".tag", "individual")
                        put("allocated", quota)
                    },
                )
            },
        )
    }

    // ------------------------------------------------------------------------------------------- content

    private fun readBody(ex: HttpExchange): ByteArray {
        dropUploadAfter?.let { limit ->
            dropUploadAfter = null
            val buf = ByteArray(limit)
            var read = 0
            while (read < limit) {
                val n = ex.requestBody.read(buf, read, limit - read)
                if (n < 0) break
                read += n
            }
            throw DropConnection()
        }
        return ex.requestBody.readAllBytes()
    }

    /** Stores [received] under the commit argument's path, like `upload` and `upload_session/finish`. */
    private fun commit(ex: HttpExchange, commit: JsonObject, received: ByteArray): Unit = synchronized(lock) {
        val path = commit.str("path") ?: return error(ex, 400, "bad")
        if (commit.str("mode") != "add" || commit["autorename"]?.let { (it as JsonPrimitive).booleanOrNull } != false) {
            // KeepClear must never ask to overwrite or to pick another name.
            return error(ex, 400, "mode")
        }
        val lower = key(path)
        if (nodes.containsKey(lower)) return error(ex, 409, "path/conflict/file/")
        quotaBytes?.let { if (used() + received.size > it) return error(ex, 409, "path/insufficient_space/") }
        if (!makeParents(lower, "/" + path.trim('/'))) return error(ex, 409, "path/conflict/file/")
        val stored = corruptStored?.invoke(received) ?: received
        val node = Node.File(
            "/" + path.trim('/'),
            stored,
            DropboxContentHash.of(received),
            newRev(),
            System.currentTimeMillis(),
        )
        nodes[lower] = node
        ok(ex, meta(lower, node, tagged = false))
    }

    private fun upload(ex: HttpExchange, arg: JsonObject) {
        val received = readBody(ex)
        commit(ex, arg, received)
    }

    private fun sessionStart(ex: HttpExchange) {
        val received = readBody(ex)
        val id = "session-" + UUID.randomUUID()
        synchronized(lock) { sessions[id] = Session().also { it.bytes.write(received) } }
        ok(ex, buildJsonObject { put("session_id", id) })
    }

    /** Checks the cursor; returns the session or null after answering an error. */
    private fun cursorSession(ex: HttpExchange, cursor: JsonObject?): Session? {
        val id = cursor?.str("session_id")
        val offset = (cursor?.get("offset") as? JsonPrimitive)?.longOrNull
        val s = synchronized(lock) { sessions[id] }
        if (s == null) {
            error(ex, 409, "lookup_failed/not_found/")
            return null
        }
        if (offset != s.bytes.size().toLong()) {
            error(ex, 409, "lookup_failed/incorrect_offset/")
            return null
        }
        return s
    }

    private fun sessionAppend(ex: HttpExchange, arg: JsonObject) {
        val received = readBody(ex)
        val s = cursorSession(ex, arg["cursor"] as? JsonObject) ?: return
        synchronized(lock) { s.bytes.write(received) }
        ok(ex, JsonNull)
    }

    private fun sessionFinish(ex: HttpExchange, arg: JsonObject) {
        val received = readBody(ex)
        val cursor = arg["cursor"] as? JsonObject
        val s = cursorSession(ex, cursor) ?: return
        val all = synchronized(lock) {
            s.bytes.write(received)
            sessions.remove(cursor?.str("session_id"))
            s.bytes.toByteArray()
        }
        commit(ex, arg["commit"] as? JsonObject ?: return error(ex, 400, "bad"), all)
    }

    private fun download(ex: HttpExchange, arg: JsonObject) {
        ex.requestBody.readAllBytes()
        val path = arg.str("path") ?: return error(ex, 400, "bad")
        val (lower, node) = synchronized(lock) {
            val lower = key(path)
            lower to (nodes[lower] ?: return error(ex, 409, "path/not_found/"))
        }
        if (node !is Node.File) return error(ex, 409, "path/not_file/")
        // Dropbox escapes non-ASCII characters in this header.
        ex.responseHeaders.add("Dropbox-API-Result", DropboxJson.header(meta(lower, node, tagged = false)))
        ex.responseHeaders.add("Content-Type", "application/octet-stream")
        val body = if (longDownload) node.bytes + ByteArray(1_000) else node.bytes
        reply(ex, 200, body)
    }

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        server.stop(0)
        executor.shutdownNow()
    }
}
