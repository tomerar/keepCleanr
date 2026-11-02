// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * M17b: Login Flow v2 and the account check against a fake Nextcloud (the real one: [NextcloudLoginServerTest]).
 * The fake answers like Nextcloud 31 did in the W4 container: pretty URLs without `index.php`, 404 while the user
 * hasn't granted access, the credentials once, then 404 again.
 */
class NextcloudLoginTest {
    private val token = "t".repeat(40) + "0123456789"
    private val flowId = "F".repeat(64)
    private val appPassword = "A1b2".repeat(18)
    private val granted = AtomicInteger(0)
    private val polls = AtomicInteger(0)

    /** Overrides for hostile answers. */
    private var startAnswer: ((String) -> Pair<Int, String>)? = null
    private var pollAnswer: ((String) -> Pair<Int, String>)? = null
    private var userAnswer: Pair<Int, String>? = null
    private var lastPollBody = ""

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex -> handle(ex) }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterEach
    fun stop() {
        runCatching { server.stop(0) }
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.rawPath
        val (status, body) = when {
            ex.requestMethod == "POST" && path == "/index.php/login/v2" -> {
                startAnswer?.invoke(base) ?: (
                    200 to """{"poll":{"token":"$token","endpoint":"$base\/login\/v2\/poll"},""" +
                        """"login":"$base\/login\/v2\/flow\/$flowId"}"""
                )
            }

            ex.requestMethod == "POST" && path == "/login/v2/poll" -> {
                lastPollBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                polls.incrementAndGet()
                pollAnswer?.invoke(base) ?: if (lastPollBody == "token=$token" && granted.getAndSet(-1) == 1) {
                    200 to """{"server":"$base","loginName":"dana@example.com","appPassword":"$appPassword"}"""
                } else {
                    404 to "[]"
                }
            }

            ex.requestMethod == "GET" && path == "/ocs/v2.php/cloud/user" -> {
                userAnswer ?: run {
                    val ok = ex.requestHeaders.getFirst("Authorization") == "Basic " +
                        Base64.getEncoder().encodeToString("dana@example.com:$appPassword".toByteArray()) &&
                        ex.requestHeaders.getFirst("OCS-APIRequest") == "true"
                    if (ok) {
                        200 to """{"ocs":{"meta":{"status":"ok"},"data":{"id":"dana","quota":{"free":-3}}}}"""
                    } else {
                        401 to "{}"
                    }
                }
            }

            else -> {
                404 to ""
            }
        }
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    private fun flow() = NextcloudLoginFlow(NextcloudServer.parse(base), timeoutSeconds = 5)

    @Test
    fun `sign in in the browser, then the poll hands over the app password once`(): Unit = runBlocking {
        flow().use { flow ->
            val started = flow.start()
            started.loginUrl shouldBe "$base/login/v2/flow/$flowId"
            started.toString() shouldNotContain token
            flow.poll(started).shouldBeNull()
            lastPollBody shouldBe "token=$token"
            granted.set(1)
            val result = flow.await(started, intervalMillis = 10)
            result.loginName shouldBe "dana@example.com"
            result.appPassword shouldBe appPassword
            result.toString() shouldNotContain appPassword
            // Nextcloud forgets the grant once handed over.
            flow.poll(started).shouldBeNull()
        }
    }

    @Test
    fun `the account check finds the user id for the files path and never shows the password`() {
        val account = NextcloudAccount.verify(NextcloudServer.parse(base), "dana@example.com", appPassword, 5)
        account.userId shouldBe "dana"
        account.location().canonicalUrl shouldBe "$base/remote.php/dav/files/dana"
        account.location().user shouldBe "dana@example.com"
        account.location().nextcloud shouldBe true
        account.toString() shouldNotContain appPassword
        WebDavTargets.ref(account.location()) shouldStartWith "dav://127.0.0.1:"
    }

    @Test
    fun `a wrong app password is refused`() {
        val e = shouldThrow<NextcloudException> {
            NextcloudAccount.verify(NextcloudServer.parse(base), "dana@example.com", "wrong-password", 5)
        }
        e.problem shouldBe NextcloudProblem.REFUSED
    }

    @Test
    fun `waiting too long ends the sign-in`(): Unit = runBlocking {
        flow().use { flow ->
            val started = flow.start()
            var now = 0L
            val e = shouldThrow<NextcloudException> {
                flow.await(started, intervalMillis = 1, timeoutMillis = 1_000) { now.also { now += 400 } }
            }
            e.problem shouldBe NextcloudProblem.EXPIRED
        }
    }

    @Test
    fun `addresses on another server are refused, so the browser and the password stay on the typed server`() {
        val other = "https://evil.example"
        listOf(
            """{"poll":{"token":"$token","endpoint":"$other/login/v2/poll"},"login":"BASE/login/v2/flow/$flowId"}""",
            """{"poll":{"token":"$token","endpoint":"BASE/login/v2/poll"},"login":"$other/login/v2/flow/$flowId"}""",
            """{"poll":{"token":"$token","endpoint":"BASE/login/v2/poll"},""" +
                """"login":"http://u:p@127.0.0.1:1/login/v2/flow/$flowId"}""",
        ).forEach { answer ->
            startAnswer = { b -> 200 to answer.replace("BASE", b) }
            val e = shouldThrow<NextcloudException> { flow().use { it.start() } }
            e.problem shouldBe NextcloudProblem.OTHER_ADDRESS
        }
        startAnswer = null
        pollAnswer = { _ -> 200 to """{"server":"$other","loginName":"dana","appPassword":"$appPassword"}""" }
        flow().use { flow ->
            val started = flow.start()
            shouldThrow<NextcloudException> { flow.poll(started) }.problem shouldBe NextcloudProblem.OTHER_ADDRESS
        }
    }

    @Test
    fun `a server under an encoded path signs in, and only safely decoded Login Flow routes count (PR 40 review)`() {
        val flow = "a".repeat(32)
        NextcloudLoginFlow.isPollPath("/my%20cloud/login/v2/poll") shouldBe true
        NextcloudLoginFlow.isPollPath("/%D7%A2%D7%A0%D7%9F/index.php/login/v2/poll") shouldBe true
        NextcloudLoginFlow.isLoginPath("/my%20cloud/login/v2/flow/$flow") shouldBe true
        NextcloudLoginFlow.isPollPath("/login/v2/poll") shouldBe true
        // Unsafe or malformed paths still never count.
        listOf(
            "/a/../login/v2/poll",
            "/a%2Fb/login/v2/poll",
            "/%2e%2e/login/v2/poll",
            "/a%zz/login/v2/poll",
            "/a%ff/login/v2/poll",
            "/login/v2/poll/x",
            "login/v2/poll",
        ).forEach { NextcloudLoginFlow.isPollPath(it) shouldBe false }
        NextcloudLoginFlow.isLoginPath("/login/v2/flow/short") shouldBe false
        NextcloudLoginFlow.isLoginPath("/login/v2/flow/$flow/x") shouldBe false
        NextcloudLoginFlow.isLoginPath("/login/v2/flow/${"a".repeat(31)}%21") shouldBe false
    }

    @Test
    fun `malformed or hostile answers are not Nextcloud`() {
        listOf(
            200 to "not json",
            200 to "[]",
            200 to """{"poll":{"token":"short","endpoint":"BASE/login/v2/poll"},""" +
                """"login":"BASE/login/v2/flow/$flowId"}""",
            200 to """{"poll":{"token":"$token","endpoint":"BASE/login/v2/poll?x=1"},""" +
                """"login":"BASE/login/v2/flow/$flowId"}""",
            200 to """{"poll":{"token":"$token","endpoint":"BASE/other"},"login":"BASE/login/v2/flow/$flowId"}""",
            200 to """{"poll":{"token":"$token","endpoint":"BASE/login/v2/poll"},"login":"BASE/apps/files"}""",
            200 to "[".repeat(100) + "]".repeat(100),
            404 to "",
            500 to "{}",
        ).forEach { (status, answer) ->
            startAnswer = { b -> status to answer.replace("BASE", b) }
            val e = shouldThrow<NextcloudException> { flow().use { it.start() } }
            e.problem shouldBe NextcloudProblem.NOT_NEXTCLOUD
        }
        startAnswer = null
        listOf(
            """{"server":"BASE","loginName":"a:b","appPassword":"$appPassword"}""",
            """{"server":"BASE","loginName":"dana","appPassword":"has space"}""",
            """{"server":"BASE","loginName":"dana"}""",
        ).forEach { answer ->
            pollAnswer = { b -> 200 to answer.replace("BASE", b) }
            flow().use { flow ->
                val started = flow.start()
                shouldThrow<NextcloudException> { flow.poll(started) }.problem shouldBe NextcloudProblem.NOT_NEXTCLOUD
            }
        }
        userAnswer = 200 to """{"ocs":{"data":{"id":"../admin"}}}"""
        shouldThrow<NextcloudException> {
            NextcloudAccount.verify(NextcloudServer.parse(base), "dana@example.com", appPassword, 5)
        }.problem shouldBe NextcloudProblem.NOT_NEXTCLOUD
    }

    @Test
    fun `nothing answering is unreachable, and polling keeps trying through it`(): Unit = runBlocking {
        val started = flow().use { it.start() }
        server.stop(0)
        shouldThrow<NextcloudException> { flow().use { it.start() } }.problem shouldBe NextcloudProblem.UNREACHABLE
        var now = 0L
        flow().use { flow ->
            // A poll without an answer isn't the end; only the deadline is.
            shouldThrow<NextcloudException> {
                flow.await(started, intervalMillis = 1, timeoutMillis = 1_000) { now.also { now += 300 } }
            }.problem shouldBe NextcloudProblem.EXPIRED
        }
    }

    @Test
    fun `typed addresses - https by default, plain http only for this phone, no credentials or queries`() {
        NextcloudServer.parse("cloud.example.com").url shouldBe "https://cloud.example.com"
        NextcloudServer.parse(" https://Cloud.Example.com/nextcloud/ ").url shouldBe
            "https://cloud.example.com/nextcloud"
        NextcloudServer.parse("https://cloud.example.com:8443").url shouldBe "https://cloud.example.com:8443"
        NextcloudServer.parse("http://localhost:8080").url shouldBe "http://localhost:8080"
        NextcloudServer.parse("http://127.0.0.1:8089").url shouldBe "http://127.0.0.1:8089"
        listOf("http://cloud.example.com", "http://192.168.1.20", "http://nas.local:8080").forEach {
            NextcloudServer.problem(it) shouldBe NextcloudProblem.NEEDS_HTTPS
        }
        listOf(
            "",
            "https://user:pw@cloud.example.com",
            "https://cloud.example.com/?x=1",
            "https://cloud.example.com/#f",
            "https://cloud.example.com/a/../b",
            "https://cloud.example.com/a%2Fb",
            "ftp://cloud.example.com",
            "https://",
            "cloud example.com",
            "https://cloud.example.com:99999",
        ).forEach { NextcloudServer.problem(it).shouldNotBeNull() shouldBe NextcloudProblem.BAD_ADDRESS }
    }

    @Test
    fun `the account never holds a name or password that could break a request`() {
        val s = NextcloudServer.parse("cloud.example.com")
        shouldThrow<IllegalArgumentException> { NextcloudAccount(s, "a:b", "a", appPassword) }
        shouldThrow<IllegalArgumentException> { NextcloudAccount(s, "a", "..", appPassword) }
        shouldThrow<IllegalArgumentException> { NextcloudAccount(s, "a", "a", "line\nbreak") }
        shouldThrow<IllegalArgumentException> { NextcloudAccount(s, "a", "a", "") }
        NextcloudAccount(s, "a", "a", appPassword).location().canonicalUrl shouldBe
            "https://cloud.example.com/remote.php/dav/files/a"
    }
}
