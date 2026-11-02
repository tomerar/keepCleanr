// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext

/**
 * An in-process WebDAV server, just enough for KeepClear (PROPFIND, MKCOL, PUT, GET, MOVE, DELETE), with switches
 * for the ways a real server can fail or lie (M17). Paths are kept decoded; hrefs are percent-encoded.
 */
class FakeDavServer(
    val user: String = "dana",
    val password: String = "app-password-123",
    /** The account's WebDAV root, like Nextcloud's `/remote.php/dav/files/<user>`. */
    val prefix: String = "/remote.php/dav/files/dana",
    /** Serve HTTPS with this context (tests of certificate checks) instead of plain HTTP. */
    private val tls: SSLContext? = null,
) : AutoCloseable {
    enum class HashMode {
        /** Hash of what was stored (an honest Nextcloud). */
        STORED,

        /** Hash of what was received, even if something else was stored (lies about a later corruption). */
        RECEIVED,

        /** A wrong hash. */
        WRONG,

        /** A malformed value. */
        GARBAGE,
    }

    private sealed interface Node {
        var mtime: Long

        class Dir(override var mtime: Long) : Node

        class File(val bytes: ByteArray, override var mtime: Long, val etag: String) : Node
    }

    private val nodes = TreeMap<String, Node>()
    private val lock = Any()
    private var etags = 0

    /** Every request as "METHOD /decoded/path". */
    val requests = CopyOnWriteArrayList<String>()

    /** Client ports seen, in request order (a new port = a new connection). */
    val clientPorts = CopyOnWriteArrayList<Int>()

    /** Answer `X-Hash: sha256` with `X-Hash-SHA256`, like Nextcloud. */
    @Volatile var nextcloud = false

    @Volatile var hashMode = HashMode.STORED

    /** Changes what is stored (truncate, flip bits) after receiving it. */
    @Volatile var corruptStored: ((ByteArray) -> ByteArray)? = null

    /** Total bytes the account may hold; a PUT past it gets 507. */
    @Volatile var quotaBytes: Long? = null

    /** Read this many bytes of the next PUT body, then drop the connection without answering. */
    @Volatile var dropUploadAfter: Int? = null

    /** Store upload bytes as they arrive, and keep them if the client abandons the upload (a sloppy server). */
    @Volatile var keepPartialUploads = false

    /** Send a GET body shorter than its Content-Length, then drop the connection. */
    @Volatile var shortGet = false

    /** Add hrefs that point outside the folder (`..`, other hosts, other users) to every listing. */
    @Volatile var hostileHrefs = false

    /** Answer PROPFIND with an XXE payload. */
    @Volatile var xxe = false

    /** Answer PROPFIND with a body of this many bytes. */
    @Volatile var hugePropfindBytes: Int? = null

    /** Answer everything with a redirect to [redirectTo]. */
    @Volatile var redirectTo: String? = null

    private val server: HttpServer = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0).let { address ->
        if (tls == null) {
            HttpServer.create(address, 0)
        } else {
            HttpsServer.create(address, 0).apply { httpsConfigurator = HttpsConfigurator(tls) }
        }
    }
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    val port: Int get() = server.address.port

    /** Base URL for a folder under the account root. */
    fun url(folder: String = ""): String = "${if (tls == null) "http" else "https"}://127.0.0.1:$port" + DavPath.encode(
        (prefix.split('/') + folder.split('/')).filter { it.isNotEmpty() },
    )

    fun location(folder: String = "", nextcloud: Boolean = this.nextcloud, password: String = this.password) =
        WebDavLocation(url(folder), user, password, nextcloud)

    init {
        synchronized(lock) {
            nodes["/"] = Node.Dir(0)
            var p = ""
            prefix.split('/').filter { it.isNotEmpty() }.forEach {
                p += "/$it"
                nodes[p] = Node.Dir(0)
            }
        }
        server.executor = executor
        server.createContext("/") { ex ->
            // DropConnection escapes: the server then closes the connection without an answer.
            handle(ex)
            ex.close()
        }
        server.start()
    }

    /** Thrown from a handler: the server closes the connection without an answer. */
    private class DropConnection : RuntimeException()

    // ------------------------------------------------------------------------------------------ test helpers

    fun bytes(path: String): ByteArray? = synchronized(lock) { (nodes[abs(path)] as? Node.File)?.bytes }

    fun exists(path: String): Boolean = synchronized(lock) { nodes.containsKey(abs(path)) }

    fun putFile(path: String, bytes: ByteArray) = synchronized(lock) {
        nodes[abs(path)] = Node.File(bytes, System.currentTimeMillis(), "\"e${etags++}\"")
    }

    fun files(): List<String> = synchronized(lock) {
        nodes.filter { it.value is Node.File }.keys.map { it.removePrefix(prefix) }
    }

    private fun abs(path: String) = (prefix + "/" + path.trim('/')).trimEnd('/')

    // ---------------------------------------------------------------------------------------------- handling

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path.let { if (it.length > 1) it.trimEnd('/') else it }
        requests += "${ex.requestMethod} $path"
        clientPorts += ex.remoteAddress.port
        redirectTo?.let {
            ex.responseHeaders.add("Location", it)
            return reply(ex, 302)
        }
        val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        if (ex.requestHeaders.getFirst("Authorization") != expected) {
            ex.requestBody.readAllBytes()
            ex.responseHeaders.add("WWW-Authenticate", "Basic realm=\"fake\"")
            return reply(ex, 401)
        }
        when (ex.requestMethod) {
            "PROPFIND" -> propfind(ex, path)
            "MKCOL" -> mkcol(ex, path)
            "PUT" -> put(ex, path)
            "GET" -> get(ex, path)
            "MOVE" -> move(ex, path)
            "DELETE" -> delete(ex, path)
            else -> reply(ex, 405)
        }
    }

    private fun reply(ex: HttpExchange, status: Int, body: ByteArray? = null) {
        if (body == null) {
            ex.sendResponseHeaders(status, -1)
        } else {
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.write(body)
        }
    }

    private fun parentOf(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }

    private fun mkcol(ex: HttpExchange, path: String): Unit = synchronized(lock) {
        when {
            nodes.containsKey(path) -> {
                reply(ex, 405)
            }

            nodes[parentOf(path)] !is Node.Dir -> {
                reply(ex, 409)
            }

            else -> {
                nodes[path] = Node.Dir(System.currentTimeMillis())
                reply(ex, 201)
            }
        }
    }

    /** Names with an upload in progress. Like Nextcloud, other writes to them get 423 Locked meanwhile. */
    private val locked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun put(ex: HttpExchange, path: String) {
        if (!locked.add(path)) {
            ex.requestBody.readAllBytes()
            return reply(ex, 423)
        }
        try {
            putUnlocked(ex, path)
        } finally {
            locked.remove(path)
        }
    }

    private fun putUnlocked(ex: HttpExchange, path: String) {
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
        val sink = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            // A sloppy server writes through as bytes arrive and keeps them if the client goes away mid-body.
            val n = ex.requestBody.read(buf)
            if (n < 0) break
            sink.write(buf, 0, n)
            if (keepPartialUploads) putFile(path.removePrefix(prefix), sink.toByteArray())
        }
        val received = sink.toByteArray()
        synchronized(lock) {
            if (nodes[parentOf(path)] !is Node.Dir) return reply(ex, 409)
            if (nodes[path] is Node.Dir) return reply(ex, 405)
            if (ex.requestHeaders.getFirst("If-None-Match") == "*" && nodes.containsKey(path)) return reply(ex, 412)
            val used = nodes.values.filterIsInstance<Node.File>().sumOf { it.bytes.size.toLong() }
            quotaBytes?.let { if (used + received.size > it) return reply(ex, 507) }
            val stored = corruptStored?.invoke(received) ?: received
            val etag = "\"e${etags++}\""
            val existed = nodes.containsKey(path)
            nodes[path] = Node.File(stored, System.currentTimeMillis(), etag)
            // A client checksum is stored as-is, like Nextcloud does for a plain PUT; it proves nothing.
            ex.responseHeaders.add("ETag", etag)
            if (nextcloud && ex.requestHeaders.getFirst("X-Hash")?.lowercase() in setOf("sha256", "all")) {
                val hash = when (hashMode) {
                    HashMode.STORED -> sha256(stored)
                    HashMode.RECEIVED -> sha256(received)
                    HashMode.WRONG -> sha256(received + 1)
                    HashMode.GARBAGE -> "not-a-hash"
                }
                ex.responseHeaders.add("X-Hash-SHA256", hash)
            }
            reply(ex, if (existed) 204 else 201)
        }
    }

    private fun get(ex: HttpExchange, path: String) {
        val node = synchronized(lock) { nodes[path] }
        when (node) {
            null -> {
                reply(ex, 404)
            }

            is Node.Dir -> {
                reply(ex, 405)
            }

            is Node.File -> {
                if (shortGet && node.bytes.size > 10) {
                    ex.sendResponseHeaders(200, node.bytes.size.toLong())
                    ex.responseBody.write(node.bytes, 0, node.bytes.size / 2)
                    ex.responseBody.flush()
                    throw DropConnection()
                }
                reply(ex, 200, node.bytes)
            }
        }
    }

    private fun move(ex: HttpExchange, path: String): Unit = synchronized(lock) {
        val dest = ex.requestHeaders.getFirst("Destination")?.let { URI(it) }
        if (dest == null || dest.port != port) return reply(ex, 502)
        val to = dest.path.let { if (it.length > 1) it.trimEnd('/') else it }
        if (path in locked || to in locked) return reply(ex, 423)
        val overwrite = ex.requestHeaders.getFirst("Overwrite") != "F"
        when {
            !nodes.containsKey(path) -> {
                reply(ex, 404)
            }

            nodes.containsKey(to) && !overwrite -> {
                reply(ex, 412)
            }

            nodes[parentOf(to)] !is Node.Dir -> {
                reply(ex, 409)
            }

            else -> {
                val existed = nodes.containsKey(to)
                val moved = nodes.filterKeys { it == path || it.startsWith("$path/") }
                moved.keys.forEach { nodes.remove(it) }
                moved.forEach { (k, v) -> nodes[to + k.removePrefix(path)] = v }
                reply(ex, if (existed) 204 else 201)
            }
        }
    }

    private fun delete(ex: HttpExchange, path: String): Unit = synchronized(lock) {
        if (path in locked) return reply(ex, 423)
        if (!nodes.containsKey(path)) return reply(ex, 404)
        nodes.keys.filter { it == path || it.startsWith("$path/") }.forEach { nodes.remove(it) }
        reply(ex, 204)
    }

    private fun propfind(ex: HttpExchange, path: String) {
        ex.requestBody.readAllBytes()
        hugePropfindBytes?.let { size ->
            return reply(ex, 207, ByteArray(size) { ' '.code.toByte() })
        }
        if (xxe) {
            val body = """<?xml version="1.0"?>
                <!DOCTYPE d [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>$prefix/&xxe;</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                </d:response></d:multistatus>
            """.trimIndent()
            return reply(ex, 207, body.toByteArray())
        }
        val depth = ex.requestHeaders.getFirst("Depth") ?: "infinity"
        val xml = synchronized(lock) {
            val node = nodes[path] ?: return reply(ex, 404)
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="utf-8"?>""")
            sb.append("""<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">""")
            sb.append(response(path, node))
            if (depth == "1" && node is Node.Dir) {
                nodes.filterKeys { it != path && parentOf(it) == path }.forEach { (k, v) -> sb.append(response(k, v)) }
                if (hostileHrefs) {
                    val evil = listOf(
                        "$path/../../../etc/passwd",
                        "$path/%2e%2e/escape",
                        "https://evil.example$path/evil.txt",
                        "http://127.0.0.1:1$path/other-port.txt",
                        "/remote.php/dav/files/mallory/stolen.txt",
                        "$path/a%2Fb",
                        "relative.txt",
                        "//evil.example/x",
                        "$path/bad%zz",
                    )
                    evil.forEach { href ->
                        sb.append("<d:response><d:href>").append(xmlEscape(encodeKeepingMarkers(href)))
                        sb.append("</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1")
                        sb.append("</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status>")
                        sb.append("</d:propstat></d:response>")
                    }
                }
            }
            sb.append("</d:multistatus>")
            sb.toString()
        }
        reply(ex, 207, xml.toByteArray(Charsets.UTF_8))
    }

    private fun response(path: String, node: Node): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val href = DavPath.encode(segments, trailingSlash = node is Node.Dir)
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(node.mtime).atOffset(ZoneOffset.UTC),
        )
        val sb = StringBuilder("<d:response><d:href>").append(xmlEscape(href)).append("</d:href><d:propstat><d:prop>")
        sb.append("<d:getlastmodified>").append(date).append("</d:getlastmodified>")
        when (node) {
            is Node.Dir -> {
                sb.append("<d:resourcetype><d:collection/></d:resourcetype>")
                val used = nodes.values.filterIsInstance<Node.File>().sumOf { it.bytes.size.toLong() }
                val free = quotaBytes?.let { it - used } ?: -3
                sb.append("<d:quota-available-bytes>").append(free).append("</d:quota-available-bytes>")
            }

            is Node.File -> {
                sb.append("<d:resourcetype/><d:getcontentlength>").append(node.bytes.size)
                sb.append("</d:getcontentlength><d:getetag>").append(xmlEscape(node.etag)).append("</d:getetag>")
                // What other clients stored: never proof (ADR 0011), and KeepClear doesn't even ask for it.
                sb.append("<oc:checksums><oc:checksum>SHA256:").append("0".repeat(64)).append("</oc:checksum>")
                sb.append("</oc:checksums>")
            }
        }
        sb.append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        return sb.toString()
    }

    private fun encodeKeepingMarkers(href: String) = href.replace(" ", "%20")

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
