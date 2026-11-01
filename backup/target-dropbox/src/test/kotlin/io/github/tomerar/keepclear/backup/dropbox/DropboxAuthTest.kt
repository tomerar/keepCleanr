// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.dropbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder

/** M17c: OAuth 2 PKCE request building and the refreshing token provider. No real App key is involved. */
class DropboxAuthTest {
    private val server = FakeDropboxServer()

    @AfterEach
    fun stop() = server.close()

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun form(r: TokenRequest) = query("x:/?" + r.form.toString(Charsets.US_ASCII))

    @Test
    fun `the S256 challenge matches RFC 7636 appendix B`() {
        DropboxOAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") shouldBe
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        shouldThrow<IllegalArgumentException> { DropboxOAuth.codeChallenge("too-short") }
        shouldThrow<IllegalArgumentException> { DropboxOAuth.codeChallenge("a".repeat(42) + "+") }
    }

    @Test
    fun `new code verifiers are long, URL-safe and different`() {
        val a = DropboxOAuth.newCodeVerifier()
        val b = DropboxOAuth.newCodeVerifier()
        a.length shouldBe 64
        Regex("^[A-Za-z0-9_-]{64}$").matches(a) shouldBe true
        a shouldNotBe b
        DropboxOAuth.codeChallenge(a).length shouldBe 43
    }

    @Test
    fun `the authorize URL asks for a code with PKCE and a refresh token`() {
        val url = DropboxOAuth.authorizeUrl("key", "challenge", "db-key://auth", "st&ate")
        url shouldStartWith "https://www.dropbox.com/oauth2/authorize?"
        query(url) shouldBe mapOf(
            "client_id" to "key",
            "response_type" to "code",
            "code_challenge" to "challenge",
            "code_challenge_method" to "S256",
            "token_access_type" to "offline",
            "redirect_uri" to "db-key://auth",
            "state" to "st&ate",
        )
    }

    @Test
    fun `token requests carry the verifier or the refresh token, never a client secret`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val exchange = DropboxOAuth.codeExchange("key", "the-code", verifier, "db-key://auth")
        exchange.path shouldBe "/oauth2/token"
        form(exchange) shouldBe mapOf(
            "code" to "the-code",
            "grant_type" to "authorization_code",
            "code_verifier" to verifier,
            "client_id" to "key",
            "redirect_uri" to "db-key://auth",
        )
        form(DropboxOAuth.refresh("key", "r+t/=")) shouldBe
            mapOf("grant_type" to "refresh_token", "refresh_token" to "r+t/=", "client_id" to "key")
        exchange.toString().contains("the-code") shouldBe false
    }

    @Test
    fun `token answers are read strictly`() {
        fun parse(status: Int, body: String) = DropboxOAuth.parseTokenResponse(status, body.toByteArray())
        val ok = parse(200, """{"access_token":"sl.abc","token_type":"bearer","expires_in":14400}""")
        ok.shouldBeInstanceOf<DropboxOAuth.TokenResult.Ok>().tokens.accessToken shouldBe "sl.abc"
        val first = parse(
            200,
            """{"access_token":"sl.abc","token_type":"bearer","expires_in":14400,"refresh_token":"r1","uid":"1"}""",
        )
        first.shouldBeInstanceOf<DropboxOAuth.TokenResult.Ok>().tokens.refreshToken shouldBe "r1"
        parse(400, """{"error":"invalid_grant"}""") shouldBe DropboxOAuth.TokenResult.SignInAgain
        parse(500, "oops") shouldBe DropboxOAuth.TokenResult.Failed
        parse(200, "not json") shouldBe DropboxOAuth.TokenResult.Failed
        parse(200, """{"access_token":"sl.a\r\nX: 1","token_type":"bearer","expires_in":1}""") shouldBe
            DropboxOAuth.TokenResult.Failed
        parse(200, """{"access_token":"sl.abc","expires_in":14400}""") shouldBe DropboxOAuth.TokenResult.Failed
        parse(200, """{"access_token":"sl.abc","token_type":"bearer","expires_in":-1}""") shouldBe
            DropboxOAuth.TokenResult.Failed
    }

    @Test
    fun `the access token is kept until shortly before it runs out`() {
        var now = 0L
        val auth = RefreshingDropboxAuth(server.appKey, server.refreshToken, server.endpoints, 5) { now }
        val first = auth.accessToken()
        auth.accessToken() shouldBe first
        now = (4 * 3600 - 10 * 60) * 1000L
        auth.accessToken() shouldBe first
        now = (4 * 3600 - 4 * 60) * 1000L
        auth.accessToken() shouldNotBe first
        server.requests.count { it.startsWith("/oauth2/token") } shouldBe 2
    }

    @Test
    fun `a refresh after someone else already refreshed returns the new token without asking again`() {
        val auth = server.auth()
        val old = auth.accessToken()
        val fresh = auth.refresh(old)!!
        fresh shouldNotBe old
        auth.refresh(old) shouldBe fresh
        server.requests.count { it.startsWith("/oauth2/token") } shouldBe 2
    }

    @Test
    fun `a revoked refresh token means signing in again`() {
        server.refreshRefused = true
        val auth = server.auth()
        shouldThrow<DropboxSignInRequiredException> { auth.accessToken() }
        auth.refresh("anything") shouldBe null
    }
}
