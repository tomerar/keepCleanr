// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** M17: the HTTP/1.1 client against a raw socket server that sends exactly the bytes a test wants. */
class MiniHttpTest {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val accepted = AtomicInteger()

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
                        while (true) {
                            val line = input.readLine() ?: return@thread
                            if (line.isEmpty()) break
                        }
                        s.getOutputStream().write(answer.toByteArray(Charsets.ISO_8859_1))
                        s.getOutputStream().flush()
                    }
                }
            }
        }
    }

    private fun client() = MiniHttp(https = false, host = "127.0.0.1", port = socket.localPort, timeoutMillis = 3_000)

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
            val http = MiniHttp(https = false, host = "127.0.0.1", port = server.localPort, timeoutMillis = 3_000)
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

    private fun uploadUntilStuck(http: MiniHttp) {
        val up = http.upload("PUT", "/big", emptyList(), 1L shl 30)
        val chunk = ByteArray(64 * 1024)
        repeat(16 * 1024) { up.write(chunk, 0, chunk.size) } // 1 GiB: far more than any socket buffer
    }

    @Test
    fun `a server that stops reading an upload can't block it forever (PR 31 review)`() {
        stall()
        val http = MiniHttp(https = false, host = "127.0.0.1", port = socket.localPort, timeoutMillis = 1_000)
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assertThrows(IOException::class.java) { uploadUntilStuck(http) }
        }
    }

    @Test
    fun `close aborts an upload that is blocked in a write (PR 31 review)`() {
        stall()
        val http = MiniHttp(https = false, host = "127.0.0.1", port = socket.localPort, timeoutMillis = 120_000)
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
}
