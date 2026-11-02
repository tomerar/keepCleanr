// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/**
 * M17b: Login Flow v2 against the **real** Nextcloud of W4 (`scripts/nextcloud-test-server.sh`; skipped without
 * it, never in CI). The browser's part is played by plain HTTP calls: open the sign-in page, then use Nextcloud's
 * own "sign in with an app password" form on it (the same endpoint a browser posts to), so no account password is
 * needed. KeepClear's side is exactly what the app runs.
 */
class NextcloudLoginServerTest {
    @BeforeEach
    fun needsServer() = NextcloudTestEnv.assume()

    /** A tiny browser: keeps cookies, never follows redirects by itself. */
    private class Browser {
        private val cookies = linkedMapOf<String, String>()

        fun request(url: String, form: Map<String, String>? = null, headers: Map<String, String> = emptyMap()):
            Pair<HttpURLConnection, String> {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (test browser)")
            if (cookies.isNotEmpty()) {
                c.setRequestProperty(
                    "Cookie",
                    cookies.entries.joinToString("; ") {
                        "${it.key}=${it.value}"
                    },
                )
            }
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (form != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = form.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                }
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = c.responseCode
            c.headerFields["Set-Cookie"].orEmpty().forEach { line ->
                val pair = line.substringBefore(';')
                cookies[pair.substringBefore('=')] = pair.substringAfter('=')
            }
            val text = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() }
            return c to text.orEmpty()
        }
    }

    /** What the user does in the browser: open the page and approve with an app password. */
    private fun grant(loginUrl: String) {
        val base = NextcloudTestEnv.url!!.trimEnd('/')
        val browser = Browser()
        var (c, page) = browser.request(loginUrl)
        var hops = 0
        while (c.responseCode in 300..399 && hops++ < 5) {
            val next = URI(loginUrl).resolve(c.getHeaderField("Location")).toString()
            val r = browser.request(next)
            c = r.first
            page = r.second
        }
        c.responseCode shouldBe 200

        fun field(name: String) = Regex("""name="$name" value="([^"]+)"""").find(page)!!.groupValues[1]
        val (answer, _) = browser.request(
            "$base/login/v2/apptoken",
            mapOf(
                "stateToken" to field("stateToken"),
                "user" to NextcloudTestEnv.user,
                "password" to NextcloudTestEnv.password,
                "requesttoken" to field("requesttoken"),
            ),
        )
        answer.responseCode shouldBe 200
    }

    @Test
    fun `sign in with Login Flow v2, get a working app password, and open the backup place`(): Unit = runBlocking {
        val server = NextcloudServer.parse(NextcloudTestEnv.url!!)
        NextcloudLoginFlow(server).use { flow ->
            val started = flow.start()
            flow.poll(started) shouldBe null
            grant(started.loginUrl)
            val result = flow.await(started, intervalMillis = 200, timeoutMillis = 30_000)
            result.loginName shouldBe NextcloudTestEnv.user
            // (Approving with an app password hands that same app password back; a browser sign-in makes a new one.)
            val account = NextcloudAccount.verify(server, result.loginName, result.appPassword)
            account.userId shouldBe NextcloudTestEnv.user
            val folder = "KeepClear-test/login-${UUID.randomUUID()}"
            val check = WebDavTargets.open(account.location(folder), "Nextcloud")
            (check is WebDavCheck.Ok) shouldBe true
            (check as WebDavCheck.Ok).target.caps.serverDigests.isNotEmpty() shouldBe true
            check.target.close()
        }
    }

    @Test
    fun `manual entry - the app password works, a wrong one is refused`() {
        val server = NextcloudServer.parse(NextcloudTestEnv.url!!)
        NextcloudAccount.verify(server, NextcloudTestEnv.user, NextcloudTestEnv.password).userId shouldBe
            NextcloudTestEnv.user
        shouldThrow<NextcloudException> {
            NextcloudAccount.verify(server, NextcloudTestEnv.user, "not-the-app-password")
        }.problem shouldBe NextcloudProblem.REFUSED
    }
}
