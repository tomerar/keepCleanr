// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.net

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.Pipe
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.ProtocolException
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/** The server answered with this status where KeepClear expected another (mapped to a storage error by the caller). */
class HttpStatusException(val status: Int) : IOException("HTTP $status")

/** The server's answer broke HTTP/1.1 or KeepClear's limits (SEC: every response is untrusted). */
open class HttpProtocolException(message: String) : IOException(message)

/** More or fewer body bytes than announced: the upload was abandoned before the server could complete it. */
class HttpLengthMismatchException(message: String) : HttpProtocolException(message)

class HttpResponse(
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
 * The one HTTP client of the network backup targets (`backup:target-webdav`, `backup:target-dropbox`): OkHttp
 * (Apache-2.0), set up strictly and talking to exactly one server (W3, owner 2026-11-02; ADR 0011). OkHttp
 * types never leave this module, so the targets only see this small API and OkHttp stays confined here
 * (dependency confinement check in build-logic, Konsist import rule).
 *
 * - Talks **only** to [host]:[port]; redirects are never followed (`followRedirects(false)`,
 *   `followSslRedirects(false)`), so credentials can't be sent anywhere else. No proxy either
 *   (`Proxy.NO_PROXY`): the bytes go straight to the server the user typed, never through a system or Wi-Fi
 *   proxy (ADR 0011 host allowlist). Plain http only to this device itself.
 * - HTTPS: OkHttp's defaults, i.e. the system's trusted CAs and OkHttp's host-name verifier. Nothing is ever
 *   "trusted all"; [trustManager] only *replaces* the CA set (tests; later a pinned certificate).
 * - HTTP/1.1 only. HTTP/2 multiplexes streams and resets them instead of closing a connection; the
 *   "a length mismatch never leaves a stored file" guarantee (below) and [close] aborting in-flight transfers
 *   are proven here on HTTP/1.1, which every WebDAV server and Dropbox speak.
 * - No cookies, no cache, no authenticator (a 401 goes back to the caller), no automatic retries
 *   (`retryOnConnectionFailure(false)`, and a 503 `Retry-After: 0` is not repeated either). Only requests that
 *   just read ([send] `repeatable`) are sent once more on a new connection after a dropped kept-alive one.
 * - Timeouts: connect, read and write each [timeoutMillis] (a server that stops reading an upload can't block
 *   it forever). No whole-call timeout: it would also cut a long upload or download that is making progress.
 * - Every response is bounded: header count, header line and total header size, one plain `Content-Length`,
 *   only `chunked` as transfer coding. Bodies are bounded by the caller ([HttpResponse.readBody]).
 * - [close] cancels every request in flight (a blocked upload or read fails at once with an IOException) and
 *   drops idle connections, so the next request runs on a new connection (FreshRead.NEW_SESSION).
 */
class HostHttpClient(
    private val https: Boolean,
    private val host: String,
    private val port: Int,
    private val timeoutMillis: Int,
    trustManager: X509TrustManager? = null,
) : Closeable {
    init {
        require(host.isNotEmpty() && host.none { it <= ' ' || it == '/' || it == '@' }) { "host not allowed" }
        require(port in 1..65535)
        require(timeoutMillis > 0)
        // The password never travels unencrypted over a network (ADR 0011).
        require(https || host in LOOPBACK) { "plain http only to this device itself" }
    }

    private val pool = ConnectionPool(MAX_IDLE, 5, TimeUnit.MINUTES)

    private val client: OkHttpClient = BASE.newBuilder()
        .connectionPool(pool)
        .connectTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .apply {
            if (trustManager != null) {
                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
                sslSocketFactory(context.socketFactory, trustManager)
            }
        }
        .build()

    /** Requests whose answer isn't closed yet, and uploads in progress: [close] cancels them. */
    private val calls: MutableSet<Call> = Collections.newSetFromMap(ConcurrentHashMap())
    private val uploads: MutableSet<Upload> = Collections.newSetFromMap(ConcurrentHashMap())

    val hostHeader: String =
        (if (':' in host) "[$host]" else host) + if (port == (if (https) 443 else 80)) "" else ":$port"

    /** The absolute URL of [path] on this server (for `Destination`). */
    fun absolute(path: String): String = "${if (https) "https" else "http"}://$hostHeader$path"

    private fun request(method: String, path: String, headers: List<Pair<String, String>>, body: RequestBody?) =
        Request.Builder().run {
            require(method.isNotEmpty() && method.all { it in 'A'..'Z' })
            require(path.startsWith("/") && path.none { it <= ' ' || it.code >= 0x7f }) { "bad request path" }
            url(
                HttpUrl.Builder()
                    .scheme(if (https) "https" else "http")
                    .host(host)
                    .port(port)
                    .encodedPath(path)
                    .build(),
            )
            header("User-Agent", "KeepClear")
            // Never compressed: sizes and hashes are of the bytes as stored (OkHttp would otherwise ask for gzip).
            header("Accept-Encoding", "identity")
            // OkHttp refuses names and values with CR, LF or other control characters (header injection).
            for ((name, value) in headers) addHeader(name, value)
            method(method, body)
            build()
        }

    /**
     * One request with an optional small body; the response body must be read or closed by the caller.
     * [repeatable]: the request only reads (GET, PROPFIND, or an RPC like Dropbox's `get_metadata` that is sent
     * as POST), so it may be sent once more on a new connection when a kept-alive one turns out to be closed.
     */
    fun send(
        method: String,
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        repeatable: Boolean = method in SAFE_TO_REPEAT,
    ): HttpResponse {
        val requestBody = body?.toRequestBody() ?: if (method in NEEDS_BODY) ByteArray(0).toRequestBody() else null
        val request = request(method, path, headers, requestBody)
        var repeated = false
        while (true) {
            val call = client.newCall(request)
            calls += call
            val response = try {
                call.execute()
            } catch (e: IOException) {
                calls -= call
                // A kept-alive connection the server closed just now: a read-only request is simply sent again,
                // once, on a new connection. Requests that change something are never repeated, and neither are
                // timeouts, cancels, refused certificates or broken answers.
                val transient = e !is InterruptedIOException && e !is ProtocolException && e !is SSLException
                if (repeatable && !repeated && transient && !call.isCanceled()) {
                    repeated = true
                    pool.evictAll()
                    continue
                }
                throw e
            } catch (e: RuntimeException) {
                calls -= call
                throw e
            }
            return wrap(call, response)
        }
    }

    /** Checks the answer against KeepClear's limits and hands it out; closing it releases the request. */
    private fun wrap(call: Call, response: Response): HttpResponse {
        try {
            check(response)
        } catch (e: IOException) {
            response.close()
            calls -= call
            throw e
        }
        val headers = LinkedHashMap<String, MutableList<String>>()
        for ((name, value) in response.headers) headers.getOrPut(name.lowercase()) { mutableListOf() } += value.trim()
        val body = object : FilterInputStream(response.body!!.byteStream()) {
            private var closed = false

            override fun close() {
                if (closed) return
                closed = true
                try {
                    response.close()
                } finally {
                    calls -= call
                }
            }
        }
        return HttpResponse(response.code, headers, body)
    }

    private fun check(response: Response) {
        val headers = response.headers
        if (headers.size > MAX_HEADERS) throw HttpProtocolException("too many headers")
        var total = 0
        for ((name, value) in headers) {
            if (name.isEmpty() || !name.all { (it.isLetterOrDigit() && it.code < 0x80) || it in TOKEN_CHARS }) {
                throw HttpProtocolException("bad header name")
            }
            val line = name.length + 2 + value.length
            if (line > MAX_LINE) throw HttpProtocolException("header line too long")
            total += line
            if (total > MAX_HEADER_BYTES) throw HttpProtocolException("headers too large")
        }
        val te = headers.values("Transfer-Encoding")
        if (te.isNotEmpty() && te.joinToString(",") { it.trim().lowercase() } != "chunked") {
            throw HttpProtocolException("unsupported transfer encoding")
        }
        val lengths = headers.values("Content-Length").flatMap { it.split(',') }.map { it.trim() }.toSet()
        if (lengths.isNotEmpty()) {
            val v = lengths.singleOrNull()
            if (v == null || v.isEmpty() || v.length > 18 || !v.all { it in '0'..'9' }) {
                throw HttpProtocolException("bad content length")
            }
        }
    }

    /**
     * Starts a request whose body is streamed: with `Content-Length` when [length] is known, else chunked.
     * [Upload.finish] ends the body and reads the response. The request runs on a background thread that pulls
     * what the caller writes through a bounded pipe, so a caller can't run ahead of the server by more than
     * [PIPE_BYTES].
     */
    fun upload(method: String, path: String, headers: List<Pair<String, String>>, length: Long?): Upload {
        require(length == null || length >= 0)
        val pipe = Pipe(PIPE_BYTES)
        pipe.sink.timeout().timeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        pipe.source.timeout().timeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        val call = client.newCall(request(method, path, headers, PipeBody(pipe, length)))
        val upload = Upload(call, pipe, length)
        calls += call
        uploads += upload
        upload.start()
        return upload
    }

    /**
     * The streamed request body. With a known length the announced length's **last byte is held back** until
     * the caller has ended the body ([Upload.finish]) with exactly that many bytes: until then the server has
     * fewer bytes than announced and can't complete the request, so a length mismatch found later (one byte too
     * many, or the caller giving up) never leaves a stored file behind.
     */
    private class PipeBody(private val pipe: Pipe, private val length: Long?) : RequestBody() {
        private var used = false

        override fun contentType() = null

        override fun contentLength(): Long = length ?: -1L

        override fun isOneShot() = true

        override fun writeTo(sink: BufferedSink) {
            // OkHttp never repeats a one-shot body; if it ever tried, the bytes are gone, so refuse.
            if (used) throw HttpProtocolException("an upload can't be sent twice")
            used = true
            val pending = Buffer()
            var sent = 0L
            while (true) {
                if (pipe.source.read(pending, CHUNK_BYTES) == -1L) break
                val now = if (length == null) pending.size else minOf(pending.size, length - 1 - sent).coerceAtLeast(0)
                sink.write(pending, now)
                sent += now
                if (length != null && sent + pending.size > length) {
                    throw HttpLengthMismatchException("more bytes than announced")
                }
                sink.flush()
            }
            if (length != null) {
                if (sent + pending.size != length) throw HttpLengthMismatchException("fewer bytes than announced")
                sink.write(pending, pending.size) // the held-back last byte: only now can the server complete
            }
        }
    }

    inner class Upload internal constructor(
        private val call: Call,
        private val pipe: Pipe,
        private val length: Long?,
    ) {
        private val result = CompletableFuture<Response>()
        private val staging = Buffer()
        private var written = 0L

        @Volatile private var done = false

        internal fun start() {
            UPLOAD_THREADS.execute {
                try {
                    result.complete(call.execute())
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                } finally {
                    // The request is over: a caller still writing (or waiting for room) fails at once.
                    pipe.cancel()
                }
            }
        }

        fun write(b: ByteArray, off: Int, len: Int) {
            if (done) throw HttpProtocolException("the upload has already ended")
            if (length != null && written + len > length) {
                abort()
                throw HttpLengthMismatchException("more bytes than announced")
            }
            guard {
                staging.write(b, off, len)
                pipe.sink.write(staging, staging.size)
            }
            written += len
        }

        /** The bytes written so far are on their way (never the held-back last byte). */
        fun flush() {
            if (done) throw HttpProtocolException("the upload has already ended")
            guard { pipe.sink.flush() }
        }

        /** Ends the body and returns the server's answer. */
        fun finish(): HttpResponse {
            // A write already failed: the request is gone and nothing was completed on the server.
            if (done) throw HttpProtocolException("the upload has already ended")
            done = true
            if (length != null && written != length) {
                abort()
                throw HttpLengthMismatchException("fewer bytes than announced")
            }
            guard { pipe.sink.close() }
            val response = try {
                result.get()
            } catch (e: InterruptedException) {
                abort()
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted")
            } catch (e: ExecutionException) {
                ended()
                throw failure(e.cause)
            }
            uploads -= this
            return wrap(call, response)
        }

        /** Gives up: the connection is closed before the last byte, so the server completes nothing. */
        internal fun abort() {
            done = true
            call.cancel()
            pipe.cancel()
            // An answer that already arrived (an early 4xx) is released too.
            result.thenAccept { runCatching { it.close() } }
            ended()
        }

        private fun ended() {
            calls -= call
            uploads -= this
        }

        /**
         * A write that fails may mean the server already answered and closed (401, 413, 507 before reading the
         * whole body): wait briefly for that answer, so the caller learns why.
         */
        private inline fun guard(block: () -> Unit) {
            try {
                block()
            } catch (e: IOException) {
                done = true
                val early = try {
                    result.get(EARLY_ANSWER_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    null
                } catch (_: ExecutionException) {
                    null
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                }
                val failed = if (result.isCompletedExceptionally) {
                    runCatching { result.get() }.exceptionOrNull()?.let { (it as? ExecutionException)?.cause ?: it }
                } else {
                    null
                }
                early?.close()
                abort()
                if (early != null && early.code >= 300) throw HttpStatusException(early.code)
                throw (failed as? IOException) ?: e
            }
        }

        private fun failure(cause: Throwable?): Throwable = when (cause) {
            is IOException, is RuntimeException, is Error -> cause
            else -> IOException("upload failed", cause)
        }
    }

    /**
     * Cancels every request in flight, uploads included (a blocked upload or read fails at once with an
     * IOException, Disconnected), and drops idle connections, so the next request runs on a new connection.
     * The client stays usable.
     */
    override fun close() {
        uploads.toList().forEach { it.abort() }
        calls.toList().forEach { it.cancel() }
        calls.clear()
        pool.evictAll()
    }

    companion object {
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1")
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 128
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_IDLE = 4
        private const val PIPE_BYTES = 256L * 1024
        private const val CHUNK_BYTES = 64L * 1024
        private const val EARLY_ANSWER_MILLIS = 3_000L
        private const val TOKEN_CHARS = "!#$%&'*+-.^_`|~"
        private val SAFE_TO_REPEAT = setOf("GET", "PROPFIND")

        /** Methods OkHttp sends only with a body; an empty one is sent when the caller has none. */
        private val NEEDS_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

        /** The strict defaults every client shares; each client adds its own pool, timeouts and trust. */
        private val BASE: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .protocols(listOf(Protocol.HTTP_1_1))
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .dns(Dns.SYSTEM)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            // OkHttp repeats any request on a 503 with `Retry-After: 0`; KeepClear decides that itself.
            .addNetworkInterceptor(
                Interceptor { chain ->
                    val r = chain.proceed(chain.request())
                    if (r.code == 503 && r.header("Retry-After") != null) {
                        r.newBuilder().removeHeader("Retry-After").build()
                    } else {
                        r
                    }
                },
            )
            .build()

        /** Runs streamed uploads (one daemon thread per upload in progress). */
        private val UPLOAD_THREADS = Executors.newCachedThreadPool { r ->
            Thread(r, "keepclear-http-upload").apply { isDaemon = true }
        }
    }
}

/** An [OutputStream] that feeds an [HostHttpClient.Upload]. */
open class UploadStream(private val upload: HostHttpClient.Upload) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) = upload.write(b, off, len)

    override fun flush() = upload.flush()
}
