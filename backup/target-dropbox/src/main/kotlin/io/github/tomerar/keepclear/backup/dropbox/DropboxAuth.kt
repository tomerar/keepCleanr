// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.dropbox

import io.github.tomerar.keepclear.backup.dropbox.DropboxJson.long
import io.github.tomerar.keepclear.backup.dropbox.DropboxJson.string
import io.github.tomerar.keepclear.backup.net.MiniHttp
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Where KeepClear talks to Dropbox (ADR 0011 network rule: fixed provider hosts only). There are exactly two:
 * [PRODUCTION] (HTTPS to Dropbox's API hosts, system CAs and host-name check) and [loopback] for the tests' fake
 * server on this machine. No other host can be configured.
 */
class DropboxEndpoints private constructor(
    val https: Boolean,
    val apiHost: String,
    val contentHost: String,
    val port: Int,
) {
    override fun toString() = "DropboxEndpoints($apiHost, $contentHost)"

    companion object {
        val PRODUCTION = DropboxEndpoints(true, "api.dropboxapi.com", "content.dropboxapi.com", 443)

        /** Tests only: plain HTTP to a fake server on 127.0.0.1. */
        fun loopback(port: Int) = DropboxEndpoints(false, "127.0.0.1", "127.0.0.1", port)
    }
}

/**
 * Access tokens for the Dropbox API (M17c). Short-lived access tokens come from a refresh token obtained once with
 * OAuth 2 PKCE ([DropboxOAuth]); the sign-in screen and the encrypted token store come with the app wiring.
 *
 * Both functions block (a refresh is a network call) and are called on an I/O thread. Implementations never log
 * a token and never put one in `toString`.
 */
interface DropboxAuth {
    /** A token to send now. Throws [DropboxSignInRequiredException] when the user must connect Dropbox again. */
    fun accessToken(): String

    /**
     * Dropbox refused [rejected] (401). Returns a new token to retry with, or null when there is none (the user
     * must connect Dropbox again). If another call already refreshed, the current token is returned.
     */
    fun refresh(rejected: String): String?
}

/** The refresh token was refused or revoked: the user must connect Dropbox again ("Sign in to Dropbox again"). */
class DropboxSignInRequiredException : IOException("Dropbox sign-in needed")

/** Tokens from the token endpoint. `toString` never shows them. */
class DropboxTokens(
    val accessToken: String,
    /** Seconds the access token is valid, as Dropbox said (usually 4 hours). */
    val expiresInSeconds: Long,
    /** Only in the first code exchange (`token_access_type=offline`); refreshes don't return a new one. */
    val refreshToken: String?,
) {
    override fun toString() = "DropboxTokens(expiresIn=${expiresInSeconds}s, refreshToken=${refreshToken != null})"
}

/** A form POST to Dropbox's token endpoint, ready to send. `toString` hides the form (it carries secrets). */
class TokenRequest internal constructor(val path: String, val form: ByteArray) {
    val contentType = "application/x-www-form-urlencoded"

    override fun toString() = "TokenRequest($path, ${form.size} bytes)"
}

/**
 * OAuth 2 with PKCE for Dropbox (RFC 7636; research 08 §3.2): no client secret, the App key is public. Pure code:
 * builds what the sign-in step sends and reads what it gets back. The App key and the redirect URI belong to the
 * owner's Dropbox app registration (docs/progress.md, waiting for owner).
 */
object DropboxOAuth {
    const val AUTHORIZE_URL = "https://www.dropbox.com/oauth2/authorize"
    const val TOKEN_PATH = "/oauth2/token"

    private val VERIFIER = Regex("^[A-Za-z0-9._~-]{43,128}$")
    private val TOKEN = Regex("^[\\x21-\\x7e]{1,4096}$")
    private val urlSafe = Base64.getUrlEncoder().withoutPadding()

    /** A new code verifier: 64 characters from 48 random bytes, base64url (RFC 7636 §4.1). */
    fun newCodeVerifier(random: SecureRandom = SecureRandom()): String =
        urlSafe.encodeToString(ByteArray(48).also { random.nextBytes(it) })

    /** `S256` challenge for [verifier]: base64url(SHA-256(ASCII(verifier))) without padding (RFC 7636 §4.2). */
    fun codeChallenge(verifier: String): String {
        require(VERIFIER.matches(verifier)) { "not a PKCE code verifier" }
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return urlSafe.encodeToString(digest)
    }

    /**
     * The page to open in a browser tab. `token_access_type=offline` asks for a refresh token; no scope list, so
     * the App folder permissions set in the app registration apply.
     */
    fun authorizeUrl(appKey: String, codeChallenge: String, redirectUri: String, state: String): String =
        AUTHORIZE_URL + "?" + form(
            "client_id" to appKey,
            "response_type" to "code",
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "token_access_type" to "offline",
            "redirect_uri" to redirectUri,
            "state" to state,
        )

    /** Exchanges the code from the redirect for tokens. */
    fun codeExchange(appKey: String, code: String, codeVerifier: String, redirectUri: String): TokenRequest {
        require(VERIFIER.matches(codeVerifier)) { "not a PKCE code verifier" }
        return TokenRequest(
            TOKEN_PATH,
            form(
                "code" to code,
                "grant_type" to "authorization_code",
                "code_verifier" to codeVerifier,
                "client_id" to appKey,
                "redirect_uri" to redirectUri,
            ).toByteArray(Charsets.US_ASCII),
        )
    }

    /** Gets a new short-lived access token with the refresh token. */
    fun refresh(appKey: String, refreshToken: String): TokenRequest = TokenRequest(
        TOKEN_PATH,
        form(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to appKey,
        ).toByteArray(Charsets.US_ASCII),
    )

    /** What the token endpoint answered. */
    sealed interface TokenResult {
        data class Ok(val tokens: DropboxTokens) : TokenResult

        /** `invalid_grant` and friends: the refresh token is revoked or the code is used up. Sign in again. */
        data object SignInAgain : TokenResult

        /** Anything else (server trouble, a malformed answer): try again later. */
        data object Failed : TokenResult
    }

    fun parseTokenResponse(status: Int, body: ByteArray): TokenResult {
        val o = runCatching { DropboxJson.parse(body) }.getOrNull()
        if (status == 400 || status == 401) {
            val error = o?.string("error")
            return if (error == "invalid_grant" || error == "invalid_client" || error == "unauthorized_client") {
                TokenResult.SignInAgain
            } else {
                TokenResult.Failed
            }
        }
        if (status != 200 || o == null) return TokenResult.Failed
        val access = o.string("access_token")?.takeIf { TOKEN.matches(it) } ?: return TokenResult.Failed
        if (o.string("token_type")?.equals("bearer", ignoreCase = true) != true) return TokenResult.Failed
        val expires = o.long("expires_in")?.takeIf { it in 1..366L * 24 * 3600 } ?: return TokenResult.Failed
        val refresh = o.string("refresh_token")?.takeIf { TOKEN.matches(it) }
        return TokenResult.Ok(DropboxTokens(access, expires, refresh))
    }

    private fun form(vararg pairs: Pair<String, String>): String = pairs.joinToString("&") { (k, v) ->
        URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
    }
}

/**
 * [DropboxAuth] that keeps a short-lived access token and renews it with the refresh token, a few minutes before
 * it runs out or when Dropbox refuses it. Talks only to the token endpoint on [endpoints]' API host.
 */
class RefreshingDropboxAuth(
    private val appKey: String,
    private val refreshToken: String,
    endpoints: DropboxEndpoints = DropboxEndpoints.PRODUCTION,
    timeoutSeconds: Long = 30,
    private val clock: () -> Long = System::currentTimeMillis,
) : DropboxAuth {
    private val http = MiniHttp(endpoints.https, endpoints.apiHost, endpoints.port, (timeoutSeconds * 1000).toInt())
    private val lock = Any()
    private var current: String? = null
    private var expiresAt = 0L

    override fun accessToken(): String = synchronized(lock) {
        val token = current
        if (token != null && clock() < expiresAt - EARLY_MILLIS) return token
        renew()
    }

    override fun refresh(rejected: String): String? = synchronized(lock) {
        val token = current
        if (token != null && token != rejected) return token
        try {
            renew()
        } catch (_: DropboxSignInRequiredException) {
            null
        }
    }

    /** Asks for a new access token. Network trouble is an IOException (the job pauses, nothing is lost). */
    private fun renew(): String {
        val request = DropboxOAuth.refresh(appKey, refreshToken)
        val response = http.send(
            "POST",
            request.path,
            listOf("Content-Type" to request.contentType),
            request.form,
            repeatable = true,
        )
        val body = response.readBody(MAX_TOKEN_RESPONSE)
        return when (val result = DropboxOAuth.parseTokenResponse(response.status, body)) {
            is DropboxOAuth.TokenResult.Ok -> {
                current = result.tokens.accessToken
                expiresAt = clock() + result.tokens.expiresInSeconds * 1000
                result.tokens.accessToken
            }

            DropboxOAuth.TokenResult.SignInAgain -> {
                current = null
                throw DropboxSignInRequiredException()
            }

            DropboxOAuth.TokenResult.Failed -> {
                throw IOException("the Dropbox token endpoint answered ${response.status}")
            }
        }
    }

    /** Drops the connection to the token endpoint. */
    fun close() = http.close()

    override fun toString() = "RefreshingDropboxAuth"

    private companion object {
        /** Renew this long before the token runs out, so a long upload doesn't start with a dying token. */
        const val EARLY_MILLIS = 5 * 60_000L
        const val MAX_TOKEN_RESPONSE = 64 * 1024
    }
}
