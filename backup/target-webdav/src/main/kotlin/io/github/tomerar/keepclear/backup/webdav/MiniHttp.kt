// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** The server answered with this status where KeepClear expected another (mapped to a storage error by the caller). */
internal class HttpStatusException(val status: Int) : IOException("HTTP $status")

/** The server's answer broke HTTP/1.1 or KeepClear's limits (SEC: every response is untrusted). */
internal open class HttpProtocolException(message: String) : IOException(message)

/** More or fewer body bytes than announced: the upload was abandoned before the server could complete it. */
internal class HttpLengthMismatchException(message: String) : HttpProtocolException(message)

internal class HttpResponse(
    val status: Int,
    /** Lower-case header names. */
    val headers: Map<String, List<String>>,
    val body: InputStream,
) : Closeable {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    /** Reads the whole body, failing past [max] bytes (SEC: a hostile server could send gigabytes). */
    fun readBody(max: Int): ByteArray = body.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw HttpProtocolException("response larger than $max bytes")
        }
        out.toByteArray()
    }

    override fun close() = body.close()
}

/**
 * A small, strict HTTP/1.1 client over the JDK's own sockets and TLS (M17). Android's `HttpURLConnection` refuses
 * WebDAV methods (PROPFIND, MKCOL, MOVE) and `java.net.http` doesn't exist on Android, so KeepClear speaks
 * HTTP/1.1 itself instead of adding a third-party library (PRIV-1).
 *
 * - Talks **only** to [host]:[port]; redirects are never followed (ADR 0011 network rule), so credentials can't
 *   be sent anywhere else.
 * - HTTPS checks the certificate chain against the system's trusted CAs and the host name (endpoint
 *   identification `HTTPS`). Nothing is ever "trusted all".
 * - Every response is bounded: status line and header lines (8 KiB each), header count and size, chunk-size
 *   lines and trailers. Bodies are bounded by the caller.
 * - Idle connections are reused; [close] drops them all, so the next request runs on a brand-new connection
 *   ("Check backup" over a new session, FreshRead.NEW_SESSION).
 */
internal class MiniHttp(
    private val https: Boolean,
    private val host: String,
    private val port: Int,
    private val timeoutMillis: Int,
    private val sslSocketFactory: SSLSocketFactory? = null,
) : Closeable {
    internal inner class Conn(val socket: Socket, val generation: Int) {
        var reused = false
        val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

        fun kill() {
            synchronized(lock) {
                active.remove(this)
                idle.remove(this)
            }
            runCatching { socket.close() }
        }

        /**
         * Runs a socket write with a deadline. `soTimeout` covers reads only: a server that accepts a request but
         * stops reading would block a write forever. The watchdog closes the socket instead, so the write fails
         * with an IOException (Disconnected) and the job pauses.
         */
        fun <T> timed(block: () -> T): T {
            val task = WATCHDOG.schedule({ kill() }, timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            try {
                return block()
            } finally {
                task.cancel(false)
            }
        }
    }

    private val lock = Any()
    private val idle = ArrayDeque<Conn>()

    /** Connections in use by a request or a response body; [close] closes them too. */
    private val active = HashSet<Conn>()
    private var generation = 0

    val hostHeader: String =
        (if (':' in host) "[$host]" else host) + if (port == (if (https) 443 else 80)) "" else ":$port"

    /** The absolute URL of [path] on this server (for `Destination`). */
    fun absolute(path: String): String = "${if (https) "https" else "http"}://$hostHeader$path"

    private fun take(fresh: Boolean = false): Conn {
        while (!fresh) {
            val c = synchronized(lock) { idle.removeFirstOrNull()?.also { active.add(it) } } ?: break
            if (healthy(c)) return c.also { it.reused = true }
            c.kill()
        }
        return connect().also { c -> synchronized(lock) { active.add(c) } }
    }

    /**
     * Servers close idle keep-alive connections after a few seconds. A 1 ms read tells a closed one (end of
     * stream) from a live one (timeout) before a request is sent on it.
     */
    private fun healthy(c: Conn): Boolean {
        if (c.socket.isClosed || c.socket.isInputShutdown || c.socket.isOutputShutdown) return false
        val timeout = c.socket.soTimeout
        return try {
            c.socket.soTimeout = 1
            c.input.read() // end of stream, or bytes nobody asked for: either way not reusable
            false
        } catch (_: SocketTimeoutException) {
            true
        } catch (_: IOException) {
            false
        } finally {
            runCatching { c.socket.soTimeout = timeout }
        }
    }

    private fun release(c: Conn) {
        synchronized(lock) {
            active.remove(c)
            if (c.generation == generation && idle.size < MAX_IDLE && !c.socket.isClosed) {
                idle.addLast(c)
                return
            }
        }
        c.kill()
    }

    private fun connect(): Conn {
        val gen = synchronized(lock) { generation }
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), timeoutMillis)
            raw.soTimeout = timeoutMillis
            raw.tcpNoDelay = true
            if (!https) return Conn(raw, gen)
            val factory = sslSocketFactory ?: SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(raw, host, port, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.soTimeout = timeoutMillis
            tls.startHandshake()
            return Conn(tls, gen)
        } catch (e: IOException) {
            runCatching { raw.close() }
            throw e
        }
    }

    private fun writeHead(c: Conn, method: String, path: String, headers: List<Pair<String, String>>) {
        require(method.all { it in 'A'..'Z' })
        require(path.startsWith("/") && path.none { it <= ' ' || it.code >= 0x7f }) { "bad request path" }
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(hostHeader).append("\r\n")
        sb.append("User-Agent: KeepClear\r\n")
        sb.append("Accept-Encoding: identity\r\n")
        for ((name, value) in headers) {
            require(name.all { it.isLetterOrDigit() || it == '-' })
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "bad header value" }
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("\r\n")
        c.output.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    /** One request with an optional small body; the response body must be read or closed by the caller. */
    fun send(
        method: String,
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
    ): HttpResponse {
        var fresh = false
        while (true) {
            val c = take(fresh)
            try {
                val all = if (body != null) headers + ("Content-Length" to body.size.toString()) else headers
                c.timed {
                    writeHead(c, method, path, all)
                    if (body != null) c.output.write(body)
                    c.output.flush()
                }
                return readResponse(c, method)
            } catch (e: IOException) {
                c.kill()
                // A kept-alive connection the server closed just now: a read-only request is simply sent again,
                // once, on a new connection. Requests that change something are never repeated.
                if (c.reused && !fresh && method in SAFE_TO_REPEAT && e !is HttpProtocolException) {
                    fresh = true
                    continue
                }
                throw e
            } catch (e: RuntimeException) {
                c.kill()
                throw e
            }
        }
    }

    /**
     * Starts a request whose body is streamed: with `Content-Length` when [length] is known, else chunked.
     * [Upload.finish] ends the body and reads the response.
     */
    fun upload(method: String, path: String, headers: List<Pair<String, String>>, length: Long?): Upload {
        val c = take()
        try {
            val framing = if (length != null) {
                "Content-Length" to length.toString()
            } else {
                "Transfer-Encoding" to "chunked"
            }
            // Only buffered, not flushed: with nothing written yet the server hasn't even seen the request.
            c.timed { writeHead(c, method, path, headers + framing) }
            return Upload(c, method, length)
        } catch (e: IOException) {
            c.kill()
            throw e
        }
    }

    inner class Upload internal constructor(
        private val c: Conn,
        private val method: String,
        private val length: Long?,
    ) {
        private var written = 0L
        private val chunk = ByteArray(if (length == null) 64 * 1024 else 0)
        private var chunkFill = 0
        private var done = false

        /**
         * With a known length, the last byte is held back until [finish]: until then the server has fewer bytes
         * than announced and can't complete the request, so a length mismatch found later (one byte too many)
         * never leaves a stored file behind.
         */
        private var held: Byte? = null

        fun write(b: ByteArray, off: Int, len: Int) {
            if (done) throw HttpProtocolException("the upload has already ended")
            if (length != null && written + len > length) {
                c.kill()
                done = true
                throw HttpLengthMismatchException("more bytes than announced")
            }
            guard {
                if (length != null) {
                    val now = minOf(len.toLong(), length - 1 - written).coerceAtLeast(0).toInt()
                    c.output.write(b, off, now)
                    if (now < len) held = b[off + len - 1] // the announced length's very last byte
                } else {
                    var o = off
                    var left = len
                    while (left > 0) {
                        val n = minOf(left, chunk.size - chunkFill)
                        System.arraycopy(b, o, chunk, chunkFill, n)
                        chunkFill += n
                        o += n
                        left -= n
                        if (chunkFill == chunk.size) flushChunk()
                    }
                }
            }
            written += len
        }

        /** Sends what is buffered (never the held-back last byte). */
        fun flush() {
            if (done) throw HttpProtocolException("the upload has already ended")
            guard { c.output.flush() }
        }

        private fun flushChunk() {
            if (chunkFill == 0) return
            c.output.write("${Integer.toHexString(chunkFill)}\r\n".toByteArray(Charsets.US_ASCII))
            c.output.write(chunk, 0, chunkFill)
            c.output.write(CRLF)
            chunkFill = 0
        }

        /** Ends the body and returns the server's answer. */
        fun finish(): HttpResponse {
            // A write already failed: the connection is gone and nothing was completed on the server.
            if (done) throw HttpProtocolException("the upload has already ended")
            done = true
            if (length != null && written != length) {
                c.kill()
                throw HttpLengthMismatchException("fewer bytes than announced")
            }
            guard {
                held?.let { c.output.write(it.toInt()) }
                if (length == null) {
                    flushChunk()
                    c.output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                }
                c.output.flush()
            }
            return try {
                readResponse(c, method)
            } catch (e: IOException) {
                c.kill()
                throw e
            }
        }

        /**
         * A write that fails may mean the server already answered and closed (401, 413, 507 before reading the
         * whole body): read that answer if there is one, so the caller learns why.
         */
        private inline fun guard(crossinline block: () -> Unit) {
            try {
                c.timed { block() }
            } catch (e: IOException) {
                done = true
                val early = runCatching {
                    c.socket.soTimeout = EARLY_ANSWER_MILLIS
                    readResponse(c, method).also { it.body.close() }
                }.getOrNull()
                c.kill()
                if (early != null && early.status >= 300) throw HttpStatusException(early.status)
                throw e
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- responses

    private fun readResponse(c: Conn, method: String): HttpResponse {
        while (true) {
            val statusLine = readLine(c.input)
            val m = STATUS_LINE.matchEntire(statusLine) ?: throw HttpProtocolException("bad status line")
            val http11 = m.groupValues[1] == "1"
            val status = m.groupValues[2].toInt()
            val headers = readHeaders(c.input)
            if (status in 100..199) continue // 100 Continue and friends carry no body
            val close = !http11 || headers["connection"].orEmpty().any { v ->
                v.split(',').any { it.trim().equals("close", ignoreCase = true) }
            }
            val body: InputStream = when {
                method == "HEAD" || status == 204 || status == 304 -> {
                    if (close) c.kill() else release(c)
                    ByteArrayInputStream(ByteArray(0))
                }

                headers.containsKey("transfer-encoding") -> {
                    val te = headers.getValue("transfer-encoding").joinToString(",") { it.trim().lowercase() }
                    if (te != "chunked") throw HttpProtocolException("unsupported transfer encoding")
                    ChunkedBody(c, close)
                }

                headers.containsKey("content-length") -> {
                    val values = headers.getValue("content-length").flatMap { it.split(',') }.map { it.trim() }.toSet()
                    val len =
                        values.singleOrNull()?.takeIf { v ->
                            v.isNotEmpty() && v.length <= 18 && v.all { it.isDigit() }
                        }
                            ?.toLong() ?: throw HttpProtocolException("bad content length")
                    FixedBody(c, len, close)
                }

                else -> {
                    EofBody(c)
                }
            }
            return HttpResponse(status, headers, body)
        }
    }

    private fun readHeaders(input: InputStream): Map<String, List<String>> {
        val headers = LinkedHashMap<String, MutableList<String>>()
        var total = 0
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) return headers
            total += line.length
            if (total > MAX_HEADER_BYTES || headers.values.sumOf { it.size } >= MAX_HEADERS) {
                throw HttpProtocolException("too many headers")
            }
            if (line[0] == ' ' || line[0] == '\t') throw HttpProtocolException("folded header")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpProtocolException("bad header")
            val name = line.substring(0, colon)
            if (!name.all { it.isLetterOrDigit() || it in "-_" }) throw HttpProtocolException("bad header name")
            headers.getOrPut(name.lowercase()) { mutableListOf() } += line.substring(colon + 1).trim()
        }
    }

    /** Reads one CRLF (or LF) terminated line of at most [MAX_LINE] bytes, as ISO-8859-1. */
    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("connection closed by the server")
            if (b == '\n'.code) return sb.toString().removeSuffix("\r")
            if (sb.length >= MAX_LINE) throw HttpProtocolException("line too long")
            sb.append(b.toChar())
        }
    }

    /** Base for response bodies: hands the connection back once the body is fully read, else drops it. */
    private abstract inner class Body(protected val c: Conn) : InputStream() {
        private var finished = false
        private var closed = false

        protected fun finished(reuse: Boolean) {
            if (finished) return
            finished = true
            if (reuse) release(c) else c.kill()
        }

        protected fun checkOpen() {
            if (closed) throw IOException("stream closed")
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun close() {
            if (closed) return
            closed = true
            // Not read to the end: the rest of the body is still on the wire, so the connection can't be reused.
            if (!finished) {
                finished = true
                c.kill()
            }
        }

        protected fun <T> io(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            finished(reuse = false)
            throw e
        }
    }

    private inner class FixedBody(c: Conn, private var left: Long, private val closeAfter: Boolean) : Body(c) {
        init {
            if (left == 0L) finished(reuse = !closeAfter)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkOpen()
            if (len == 0) return 0
            if (left == 0L) return -1
            val n = io { c.input.read(b, off, minOf(len.toLong(), left).toInt()) }
            if (n < 0) {
                finished(reuse = false)
                throw EOFException("connection closed before the end of the body")
            }
            left -= n
            if (left == 0L) finished(reuse = !closeAfter)
            return n
        }
    }

    private inner class ChunkedBody(c: Conn, private val closeAfter: Boolean) : Body(c) {
        private var chunkLeft = 0L
        private var eof = false

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkOpen()
            if (len == 0) return 0
            if (eof) return -1
            return io {
                if (chunkLeft == 0L) {
                    val sizeLine = readLine(c.input).substringBefore(';').trim()
                    if (sizeLine.isEmpty() || sizeLine.length > 15 || !sizeLine.all { Character.digit(it, 16) >= 0 }) {
                        throw HttpProtocolException("bad chunk size")
                    }
                    chunkLeft = sizeLine.toLong(16)
                    if (chunkLeft == 0L) {
                        readHeaders(c.input) // trailers, bounded like headers; ignored
                        eof = true
                        finished(reuse = !closeAfter)
                        return@io -1
                    }
                }
                val n = c.input.read(b, off, minOf(len.toLong(), chunkLeft).toInt())
                if (n < 0) throw EOFException("connection closed inside a chunk")
                chunkLeft -= n
                if (chunkLeft == 0L && readLine(c.input).isNotEmpty()) throw HttpProtocolException("bad chunk end")
                n
            }
        }
    }

    /** No length given: the body ends when the server closes the connection (never reused). */
    private inner class EofBody(c: Conn) : Body(c) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkOpen()
            if (len == 0) return 0
            val n = io { c.input.read(b, off, len) }
            if (n < 0) finished(reuse = false)
            return n
        }
    }

    /**
     * Closes every connection, idle or in use: a blocked upload or read fails at once with an IOException
     * (Disconnected), and the next request runs on a new connection.
     */
    override fun close() {
        val drop = synchronized(lock) {
            generation++
            (idle + active).also {
                idle.clear()
                active.clear()
            }
        }
        drop.forEach { it.kill() }
    }

    companion object {
        private val STATUS_LINE = Regex("""^HTTP/1\.([01]) (\d{3})(?: .*)?$""")
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 128
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_IDLE = 4
        private val SAFE_TO_REPEAT = setOf("GET", "PROPFIND")

        /** Closes sockets whose write has blocked past the timeout (one daemon thread for all clients). */
        private val WATCHDOG = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "keepclear-http-write-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private const val EARLY_ANSWER_MILLIS = 3_000
    }
}

/** An [OutputStream] that feeds an [MiniHttp.Upload]. */
internal open class UploadStream(private val upload: MiniHttp.Upload) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) = upload.write(b, off, len)

    override fun flush() = upload.flush()
}
