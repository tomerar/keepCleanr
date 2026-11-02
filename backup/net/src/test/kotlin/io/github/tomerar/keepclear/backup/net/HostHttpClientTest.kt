// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.net

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * M17, W3: the HTTP client (OkHttp underneath) against a raw socket server that sends exactly the bytes a test
 * wants. Ported from the hand-written client's tests; every case is behavioural, so it holds for any client.
 */
class HostHttpClientTest {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val accepted = AtomicInteger()

    /** The request lines and headers the server received, one list per request. */
    private val heads = CopyOnWriteArrayList<List<String>>()

    @AfterEach
    fun stop() = socket.close()

    /** Answers every request on a connection with [answer]; closes the connection after [perConnection] answers. */
    private fun serve(answer: String, perConnection: Int = Int.MAX_VALUE) = thread(isDaemon = true) {
        while (!socket.isClosed) {
            val s = runCatching { socket.accept() }.getOrNull() ?: break
            accepted.incrementAndGet()
            thread(isDaemon = true) {
                s.use {
                    val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    repeat(perConnection) {
                        val head = mutableListOf<String>()
                        while (true) {
                            val line = input.readLine() ?: return@thread
                            if (line.isEmpty()) break
                            head += line
                        }
                        heads += head
                        s.getOutputStream().write(answer.toByteArray(Charsets.ISO_8859_1))
                        s.getOutputStream().flush()
                    }
                }
            }
        }
    }

    private fun client() = HostHttpClient(
        https = false,
        host = "127.0.0.1",
        port = socket.localPort,
        timeoutMillis = 3_000,
    )

    @Test
    fun `a kept-alive connection the server closed is noticed and replaced`() {
        serve("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", perConnection = 1)
        val http = client()
        http.send("GET", "/a").readBody(10).toString(Charsets.UTF_8) shouldBe "ok"
        Thread.sleep(200) // the server has closed it by now
        http.send("GET", "/a").readBody(10).toString(Charsets.UTF_8) shouldBe "ok"
        accepted.get() shouldBe 2
    }

    @Test
    fun `a live connection is reused`() {
        serve("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        val http = client()
        repeat(3) { http.send("GET", "/a").readBody(10) }
        accepted.get() shouldBe 1
        http.close()
        http.send("GET", "/a").readBody(10)
        accepted.get() shouldBe 2
    }

    @Test
    fun `chunked bodies are decoded`() {
        serve(
            "HTTP/1.1 207 Multi-Status\r\nTransfer-Encoding: chunked\r\n\r\n4;x=y\r\nWiki\r\n5\r\npedia\r\n0\r\nT: v\r\n\r\n",
        )
        client().send("PROPFIND", "/").readBody(100).toString(Charsets.UTF_8) shouldBe "Wikipedia"
    }

    @Test
    fun `hostile answers are refused`() {
        val many = (1..200).joinToString("") { "X-$it: v\r\n" }
        listOf(
            "HTTP/1.1 200 OK\r\n$many\r\n",
            "HTTP/1.1 200 OK\r\nX: " + "a".repeat(10_000) + "\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\nok",
            "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n",
            "HTTP/1.1 200 OK\r\n folded: x\r\n\r\n",
            "SMTP ready\r\n\r\n",
        ).forEach { answer ->
            val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            thread(isDaemon = true) {
                server.accept().use { s ->
                    val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (input.readLine()?.isNotEmpty() == true) continue
                    s.getOutputStream().write(answer.toByteArray(Charsets.ISO_8859_1))
                    s.getOutputStream().flush()
                    Thread.sleep(200)
                }
            }
            val http = HostHttpClient(https = false, host = "127.0.0.1", port = server.localPort, timeoutMillis = 3_000)
            shouldThrow<IOException> { http.send("GET", "/").readBody(100) }
            server.close()
        }
        // A body larger than the caller allows.
        serve("HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n" + "x".repeat(20))
        shouldThrow<HttpProtocolException> { client().send("GET", "/").readBody(10) }
    }

    /** Accepts uploads, reads the request head, then never reads the body and never closes. */
    private fun stall() = thread(isDaemon = true) {
        while (!socket.isClosed) {
            val s = runCatching { socket.accept() }.getOrNull() ?: break
            accepted.incrementAndGet()
            s.receiveBufferSize = 4 * 1024
            val input = s.getInputStream()
            var crlf = 0
            while (crlf < 4) {
                val b = input.read()
                if (b < 0) break
                crlf = if ((b == '\r'.code && crlf % 2 == 0) || (b == '\n'.code && crlf % 2 == 1)) crlf + 1 else 0
            }
            // ...and nothing more: the socket stays open and unread.
        }
    }

    private fun uploadUntilStuck(http: HostHttpClient) {
        val up = http.upload("PUT", "/big", emptyList(), 1L shl 30)
        val chunk = ByteArray(64 * 1024)
        repeat(16 * 1024) { up.write(chunk, 0, chunk.size) } // 1 GiB: far more than any socket buffer
    }

    @Test
    fun `a server that stops reading an upload can't block it forever (PR 31 review)`() {
        stall()
        val http = HostHttpClient(https = false, host = "127.0.0.1", port = socket.localPort, timeoutMillis = 1_000)
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assertThrows(IOException::class.java) { uploadUntilStuck(http) }
        }
    }

    @Test
    fun `close aborts an upload that is blocked in a write (PR 31 review)`() {
        stall()
        val http = HostHttpClient(https = false, host = "127.0.0.1", port = socket.localPort, timeoutMillis = 120_000)
        thread(isDaemon = true) {
            Thread.sleep(1_000)
            http.close()
        }
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assertThrows(IOException::class.java) { uploadUntilStuck(http) }
        }
    }

    @Test
    fun `header injection through a value is refused`() {
        serve("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        shouldThrow<IllegalArgumentException> { client().send("GET", "/", listOf("X" to "a\r\nEvil: 1")) }
        shouldThrow<IllegalArgumentException> { client().send("GET", "/a b") }
    }

    @Test
    fun `a redirect is handed back to the caller, never followed (ADR 0011)`() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { other ->
            serve("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${other.localPort}/x\r\nContent-Length: 0\r\n\r\n")
            other.soTimeout = 500
            val r = client().send("GET", "/a", listOf("Authorization" to "Basic c2VjcmV0"))
            r.status shouldBe 302
            r.close()
            shouldThrow<java.net.SocketTimeoutException> { other.accept() }
        }
    }

    @Test
    fun `a request that changes something is never sent twice, even on a dropped kept-alive connection`() {
        serve("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n", perConnection = 1)
        val http = client()
        http.send("MKCOL", "/a/").close()
        Thread.sleep(200) // the server has closed it by now
        shouldThrow<IOException> { http.send("MKCOL", "/b/").close() }
        heads.map { it.first() } shouldBe listOf("MKCOL /a/ HTTP/1.1")
    }

    @Test
    fun `a 503 asking to retry at once is handed back, not repeated`() {
        serve("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 0\r\nContent-Length: 0\r\n\r\n")
        client().send("PUT", "/a", body = byteArrayOf(1)).use { it.status shouldBe 503 }
        heads.size shouldBe 1
    }

    @Test
    fun `cookies are never sent back, and nothing asks for compression`() {
        serve("HTTP/1.1 200 OK\r\nSet-Cookie: session=abc; path=/\r\nContent-Length: 0\r\n\r\n")
        val http = client()
        http.send("GET", "/a").close()
        http.send("GET", "/b").close()
        heads.size shouldBe 2
        heads.flatten().none { it.startsWith("Cookie:", ignoreCase = true) } shouldBe true
        heads.forEach { head ->
            head.single { it.startsWith("Accept-Encoding:", ignoreCase = true) } shouldBe
                "Accept-Encoding: identity"
        }
    }

    @Test
    fun `the system proxy is never used`() {
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI?) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            },
        )
        try {
            serve("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
            client().send("GET", "/a").readBody(10).toString(Charsets.UTF_8) shouldBe "ok"
        } finally {
            ProxySelector.setDefault(previous)
        }
    }

    @Test
    fun `plain http is refused for any host but this device`() {
        shouldThrow<IllegalArgumentException> { HostHttpClient(false, "nas.local", 80, 1_000) }
        shouldThrow<IllegalArgumentException> { HostHttpClient(false, "192.168.1.2", 80, 1_000) }
    }

    /** Reads the request head, then counts body bytes until the client closes the connection. */
    private fun countBody(
        received: AtomicInteger,
        ended: java.util.concurrent.CountDownLatch,
    ) = thread(isDaemon = true) {
        socket.accept().use { s ->
            val input = s.getInputStream()
            var crlf = 0
            while (crlf < 4) {
                val b = input.read()
                if (b < 0) break
                crlf = if ((b == '\r'.code && crlf % 2 == 0) || (b == '\n'.code && crlf % 2 == 1)) crlf + 1 else 0
            }
            runCatching {
                while (input.read() >= 0) received.incrementAndGet()
            }
            ended.countDown()
        }
    }

    @Test
    fun `the announced length's last byte is held back, so one byte too many never completes the upload`() {
        val received = AtomicInteger()
        val ended = java.util.concurrent.CountDownLatch(1)
        countBody(received, ended)
        val up = client().upload("PUT", "/f", emptyList(), 10)
        up.write(ByteArray(10) { 7 }, 0, 10)
        up.flush()
        Thread.sleep(300)
        received.get() shouldBe 9 // the server can't complete the request yet
        shouldThrow<HttpLengthMismatchException> { up.write(byteArrayOf(1), 0, 1) }
        ended.await(10, TimeUnit.SECONDS) shouldBe true // the connection was dropped...
        received.get() shouldBe 9 // ...before the last byte
        shouldThrow<HttpProtocolException> { up.finish() }
    }

    @Test
    fun `fewer bytes than announced are never completed either`() {
        val received = AtomicInteger()
        val ended = java.util.concurrent.CountDownLatch(1)
        countBody(received, ended)
        val up = client().upload("PUT", "/f", emptyList(), 10)
        up.write(ByteArray(5) { 7 }, 0, 5)
        up.flush()
        Thread.sleep(300)
        received.get() shouldBe 5
        shouldThrow<HttpLengthMismatchException> { up.finish() }
        ended.await(10, TimeUnit.SECONDS) shouldBe true
        received.get() shouldBe 5
    }

    @Test
    fun `an upload of the announced length completes, and one of unknown length is sent chunked`() {
        val bodies = CopyOnWriteArrayList<String>()
        thread(isDaemon = true) {
            repeat(2) {
                socket.accept().use { s ->
                    val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    heads += head
                    val chunked = head.any { it.equals("Transfer-Encoding: chunked", ignoreCase = true) }
                    val body = if (chunked) {
                        buildString {
                            while (true) {
                                val size = input.readLine().trim().toInt(16)
                                if (size == 0) {
                                    input.readLine()
                                    break
                                }
                                repeat(size) { append(input.read().toChar()) }
                                input.readLine()
                            }
                        }
                    } else {
                        val n = head.single { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
                        buildString { repeat(n) { append(input.read().toChar()) } }
                    }
                    bodies += body
                    s.getOutputStream().write(
                        "HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                    s.getOutputStream().flush()
                }
            }
        }
        val http = client()
        http.upload("PUT", "/known", emptyList(), 5).apply { write("hello".toByteArray(), 0, 5) }.finish().use {
            it.status shouldBe 201
        }
        http.upload("PUT", "/unknown", emptyList(), null).apply {
            write("chun".toByteArray(), 0, 4)
            flush()
            write("ked".toByteArray(), 0, 3)
        }.finish().use { it.status shouldBe 201 }
        bodies shouldBe listOf("hello", "chunked")
        heads[1].any { it.equals("Transfer-Encoding: chunked", ignoreCase = true) } shouldBe true
    }

    @Test
    fun `an early answer to an upload (507 before the body is read) reaches the caller`() {
        thread(isDaemon = true) {
            socket.accept().use { s ->
                val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                while (input.readLine()?.isNotEmpty() == true) continue
                s.getOutputStream().write(
                    "HTTP/1.1 507 Insufficient Storage\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                s.getOutputStream().flush()
                s.shutdownOutput()
                Thread.sleep(5_000)
            }
        }
        val up = client().upload("PUT", "/big", emptyList(), 1L shl 30)
        val chunk = ByteArray(64 * 1024)
        assertTimeoutPreemptively(Duration.ofSeconds(30)) {
            val e = shouldThrow<IOException> {
                repeat(16 * 1024) { up.write(chunk, 0, chunk.size) }
                up.finish().close()
            }
            // Either the server's answer, or (if the socket broke first) a plain disconnect: never a success.
            if (e is HttpStatusException) e.status shouldBe 507
        }
    }

    @Test
    fun `close aborts a read that is waiting for a stalled server`() {
        thread(isDaemon = true) {
            val s = socket.accept()
            val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            while (input.readLine()?.isNotEmpty() == true) continue
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n0123456789".toByteArray())
            s.getOutputStream().flush()
            // ...and the other 90 bytes never come; the socket stays open.
        }
        val http = HostHttpClient(https = false, host = "127.0.0.1", port = socket.localPort, timeoutMillis = 120_000)
        val r = http.send("GET", "/a")
        thread(isDaemon = true) {
            Thread.sleep(1_000)
            http.close()
        }
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assertThrows(IOException::class.java) { r.readBody(1_000) }
        }
    }
}
