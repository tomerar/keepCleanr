// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import io.github.tomerar.keepclear.backup.net.HostHttpClient
import io.github.tomerar.keepclear.backup.net.HttpProtocolException
import io.github.tomerar.keepclear.backup.webdav.NextcloudJson.obj
import io.github.tomerar.keepclear.backup.webdav.NextcloudJson.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/** What went wrong while connecting a Nextcloud (M17b), so the app can say it in plain words. */
enum class NextcloudProblem {
    /** Not an address KeepClear accepts ([NextcloudServer.parse]). */
    BAD_ADDRESS,

    /** Plain http to another device: refused, the app password would travel unencrypted (ADR 0011). */
    NEEDS_HTTPS,

    /** Nothing answered (server off, wrong address, no internet). */
    UNREACHABLE,

    /** The certificate isn't trusted or is for another name. Never bypassed ("trust anyway" is Later). */
    CERTIFICATE,

    /** Something answered, but not like a Nextcloud (no Login Flow v2, a malformed answer). */
    NOT_NEXTCLOUD,

    /** The server sent KeepClear to another address (sign-in page, poll address or server in its answer). */
    OTHER_ADDRESS,

    /** The user name or app password was refused. */
    REFUSED,

    /** The sign-in in the browser wasn't finished in time (Nextcloud forgets it after 20 minutes). */
    EXPIRED,
}

class NextcloudException(val problem: NextcloudProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * A Nextcloud server address as the user typed it, checked (M17b). `cloud.example.com` means
 * `https://cloud.example.com`; a sub path (`https://example.com/nextcloud`) is kept. HTTPS only, plain http only
 * to this phone itself (ADR 0011, C16). No user name, password, query or fragment in the address.
 */
class NextcloudServer private constructor(
    val https: Boolean,
    val host: String,
    val port: Int,
    val basePath: List<String>,
) {
    /** Canonical, without a trailing slash: the same server always gives the same string. */
    val url: String
        get() {
            val hostPart = if (':' in host) "[$host]" else host
            val portPart = if (port == defaultPort(https)) "" else ":$port"
            val path = if (basePath.isEmpty()) "" else DavPath.encode(basePath)
            return "${if (https) "https" else "http"}://$hostPart$portPart$path"
        }

    /** An encoded absolute path under this server's base path. */
    internal fun path(vararg segments: String): String = DavPath.encode(basePath + segments)

    /** True when [uri] is on exactly this server (scheme, host and port), and carries no credentials. */
    internal fun sameOrigin(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (uri.rawUserInfo != null) return false
        val h = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return false
        val p = if (uri.port == -1) defaultPort(scheme == "https") else uri.port
        return (scheme == "https") == https && (scheme == "https" || scheme == "http") && h == host && p == port
    }

    internal fun client(timeoutSeconds: Long, trustManager: X509TrustManager?) =
        HostHttpClient(https, host, port, (timeoutSeconds * 1000).toInt(), trustManager)

    override fun equals(other: Any?) = other is NextcloudServer && other.url == url

    override fun hashCode() = url.hashCode()

    override fun toString() = url

    companion object {
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1")

        private fun defaultPort(https: Boolean) = if (https) 443 else 80

        /** The checked server, or a [NextcloudException] with [NextcloudProblem.BAD_ADDRESS] / `NEEDS_HTTPS`. */
        fun parse(typed: String): NextcloudServer {
            val text = typed.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() }) bad()
            val withScheme = if ("://" in text) text else "https://$text"
            val uri = try {
                URI(withScheme)
            } catch (_: URISyntaxException) {
                bad()
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && scheme != "http") bad()
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) bad()
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
            if (host.isNullOrEmpty()) bad()
            if (scheme == "http" && host !in LOOPBACK) throw NextcloudException(NextcloudProblem.NEEDS_HTTPS)
            val https = scheme == "https"
            val port = if (uri.port == -1) defaultPort(https) else uri.port
            if (port !in 1..65535) bad()
            val base = DavPath.split(uri.rawPath.orEmpty()) ?: bad()
            return NextcloudServer(https, host, port, base)
        }

        /** Null when [typed] is acceptable, else why not (for the form, before anything is sent). */
        fun problem(typed: String): NextcloudProblem? = try {
            parse(typed)
            null
        } catch (e: NextcloudException) {
            e.problem
        }

        private fun bad(): Nothing = throw NextcloudException(NextcloudProblem.BAD_ADDRESS)
    }
}

/**
 * A Nextcloud account KeepClear may back up to: the server, the login name, the user id used in the files path,
 * and an **app password** (never the account password; it can be revoked in Nextcloud's settings). The app
 * password is never part of [toString].
 */
class NextcloudAccount(
    val server: NextcloudServer,
    val loginName: String,
    val userId: String,
    val appPassword: String,
) {
    init {
        require(isLoginName(loginName)) { "login name not allowed" }
        require(DavPath.isSafeSegment(userId)) { "user id not allowed" }
        require(isAppPassword(appPassword)) { "app password not allowed" }
    }

    /** Where backups go: the account's own files, at [folder] (M17b: the top, like a drive; C29). */
    fun location(folder: String = ""): WebDavLocation =
        WebDavLocation.nextcloud(server.url, loginName, appPassword, folder, filesUser = userId)

    override fun toString() = "NextcloudAccount(${server.url}, login=$loginName, user=$userId)"

    override fun equals(other: Any?) = other is NextcloudAccount &&
        other.server == server &&
        other.loginName == loginName &&
        other.userId == userId &&
        other.appPassword == appPassword

    override fun hashCode() = server.hashCode() * 31 + loginName.hashCode()

    companion object {
        fun isLoginName(s: String) = s.length in 1..MAX_NAME && ':' !in s && s.none { it.isISOControl() }

        /** Printable ASCII, no spaces: Nextcloud's app passwords are 72 letters and digits. */
        fun isAppPassword(s: String) = s.length in 1..MAX_PASSWORD && s.all { it.code in 0x21..0x7e }

        private const val MAX_NAME = 256
        private const val MAX_PASSWORD = 512

        /**
         * Checks [appPassword] and asks the server for the account's user id (OCS `cloud/user`): the files path
         * uses the id, which differs from the login name when people sign in with an e-mail address (OCS answers
         * JSON for `Accept: application/json`). Network I/O;
         * call off the main thread. Throws [NextcloudException].
         */
        fun verify(
            server: NextcloudServer,
            loginName: String,
            appPassword: String,
            timeoutSeconds: Long = 30,
            trustManager: X509TrustManager? = null,
        ): NextcloudAccount {
            if (!isLoginName(loginName) || !isAppPassword(appPassword)) {
                throw NextcloudException(NextcloudProblem.REFUSED)
            }
            val auth = "Basic " + Base64.getEncoder().encodeToString("$loginName:$appPassword".toByteArray())
            server.client(timeoutSeconds, trustManager).use { http ->
                val body = NextcloudCalls.call {
                    http.send(
                        "GET",
                        server.path("ocs", "v2.php", "cloud", "user"),
                        listOf("Authorization" to auth, "OCS-APIRequest" to "true", "Accept" to "application/json"),
                    ).use { r ->
                        when (r.status) {
                            200 -> r.readBody(MAX_ANSWER)
                            401, 403 -> throw NextcloudException(NextcloudProblem.REFUSED)
                            else -> throw NextcloudException(NextcloudProblem.NOT_NEXTCLOUD)
                        }
                    }
                }
                val data = NextcloudJson.parse(body).obj("ocs")?.obj("data")
                val id = data?.string("id")?.takeIf { DavPath.isSafeSegment(it) && it.length <= MAX_NAME }
                    ?: throw NextcloudException(NextcloudProblem.NOT_NEXTCLOUD)
                return NextcloudAccount(server, loginName, id, appPassword)
            }
        }

        internal const val MAX_ANSWER = 64 * 1024
    }
}

/**
 * Nextcloud **Login Flow v2** (ADR 0011; no app registration): [start] asks the server for a sign-in page and a
 * poll token, the user signs in on that page in the browser, and [poll] / [await] then receive the login name and
 * a new app password. KeepClear never sees the account password.
 *
 * Every address in the server's answers must be on the server the user typed (scheme, host and port); anything
 * else is refused ([NextcloudProblem.OTHER_ADDRESS]), so the browser is never sent elsewhere and the app password
 * only ever goes back to that server. The poll token is never part of [toString].
 */
class NextcloudLoginFlow(
    val server: NextcloudServer,
    timeoutSeconds: Long = 30,
    trustManager: X509TrustManager? = null,
) : Closeable {
    private val http = server.client(timeoutSeconds, trustManager)

    /** A started sign-in: open [loginUrl] in the browser, then poll. */
    class Started internal constructor(
        val loginUrl: String,
        internal val pollPath: String,
        internal val token: String,
    ) {
        override fun toString() = "NextcloudLoginFlow.Started"
    }

    /** Asks the server for a sign-in page. Network I/O. Throws [NextcloudException]. */
    fun start(): Started {
        val body = NextcloudCalls.call {
            http.send(
                "POST",
                server.path("index.php", "login", "v2"),
                body = ByteArray(0),
                repeatable = false,
            ).use { r ->
                if (r.status != 200) throw NextcloudException(NextcloudProblem.NOT_NEXTCLOUD)
                r.readBody(NextcloudAccount.MAX_ANSWER)
            }
        }
        val json = NextcloudJson.parse(body)
        val poll = json.obj("poll") ?: notNextcloud()
        val token = poll.string("token")?.takeIf { TOKEN.matches(it) } ?: notNextcloud()
        val endpoint = uri(poll.string("endpoint"))
        val login = uri(json.string("login"))
        if (!server.sameOrigin(endpoint) || !server.sameOrigin(login)) {
            throw NextcloudException(NextcloudProblem.OTHER_ADDRESS)
        }
        val pollPath = endpoint.rawPath.orEmpty()
        if (endpoint.rawQuery != null || endpoint.rawFragment != null || !isPollPath(pollPath)) notNextcloud()
        if (login.rawQuery != null || login.rawFragment != null || !isLoginPath(login.rawPath.orEmpty())) {
            notNextcloud()
        }
        return Started(login.toASCIIString(), pollPath, token)
    }

    /**
     * One poll: the account once the user granted access in the browser, null while they haven't (yet). The
     * server answers the credentials only once. Network I/O. Throws [NextcloudException].
     */
    fun poll(started: Started): NextcloudLoginResult? {
        val form = "token=" + URLEncoder.encode(started.token, "UTF-8")
        val body = NextcloudCalls.call {
            http.send(
                "POST",
                started.pollPath,
                listOf("Content-Type" to "application/x-www-form-urlencoded"),
                form.toByteArray(),
                repeatable = false,
            ).use { r ->
                when (r.status) {
                    200 -> r.readBody(NextcloudAccount.MAX_ANSWER)
                    404 -> return null
                    else -> throw NextcloudException(NextcloudProblem.NOT_NEXTCLOUD)
                }
            }
        }
        val json = NextcloudJson.parse(body)
        val answered = json.string("server") ?: notNextcloud()
        val sameServer = try {
            server.sameOrigin(URI(answered))
        } catch (_: URISyntaxException) {
            false
        }
        if (!sameServer) throw NextcloudException(NextcloudProblem.OTHER_ADDRESS)
        val loginName = json.string("loginName")?.takeIf(NextcloudAccount::isLoginName) ?: notNextcloud()
        val appPassword = json.string("appPassword")?.takeIf(NextcloudAccount::isAppPassword) ?: notNextcloud()
        return NextcloudLoginResult(loginName, appPassword)
    }

    /**
     * Polls every [intervalMillis] until the user granted access, or fails with [NextcloudProblem.EXPIRED] after
     * [timeoutMillis]. A poll that gets no answer (the phone switched networks while in the browser) is tried
     * again; a refused certificate or a wrong answer ends the sign-in. Cancel the coroutine to stop.
     */
    suspend fun await(
        started: Started,
        intervalMillis: Long = 2_000,
        timeoutMillis: Long = 20 * 60_000L,
        clock: () -> Long = System::currentTimeMillis,
    ): NextcloudLoginResult {
        val deadline = clock() + timeoutMillis
        while (true) {
            val result = try {
                withContext(Dispatchers.IO) { poll(started) }
            } catch (e: NextcloudException) {
                if (e.problem != NextcloudProblem.UNREACHABLE) throw e
                null
            }
            if (result != null) return result
            if (clock() >= deadline) throw NextcloudException(NextcloudProblem.EXPIRED)
            delay(intervalMillis)
        }
    }

    override fun close() = http.close()

    private fun uri(s: String?): URI = try {
        URI(s ?: notNextcloud())
    } catch (_: URISyntaxException) {
        notNextcloud()
    }

    private fun notNextcloud(): Nothing = throw NextcloudException(NextcloudProblem.NOT_NEXTCLOUD)

    internal companion object {
        val TOKEN = Regex("^[A-Za-z0-9]{32,256}$")
        val FLOW_TOKEN = Regex("^[A-Za-z0-9]{32,256}$")

        /**
         * The path is decoded with the same strict rules as a typed address ([DavPath.split]: valid escapes, valid
         * UTF-8, no `..`, no encoded `/`), so a server under e.g. `/my%20cloud` signs in (PR #40 review); only the
         * Login Flow v2 route at its end is fixed.
         */
        fun isPollPath(rawPath: String): Boolean =
            DavPath.split(rawPath)?.takeLast(3) == listOf("login", "v2", "poll")

        fun isLoginPath(rawPath: String): Boolean {
            val segments = DavPath.split(rawPath) ?: return false
            return segments.size >= 4 && segments.subList(segments.size - 4, segments.size - 1) ==
                listOf("login", "v2", "flow") && FLOW_TOKEN.matches(segments.last())
        }
    }
}

/** What Login Flow v2 hands back; check it with [NextcloudAccount.verify]. The password is never in [toString]. */
class NextcloudLoginResult(val loginName: String, val appPassword: String) {
    override fun toString() = "NextcloudLoginResult(login=$loginName)"
}

/** Network errors of the sign-in, as [NextcloudProblem]s. */
internal object NextcloudCalls {
    inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: NextcloudException) {
        throw e
    } catch (e: HttpProtocolException) {
        throw NextcloudException(NextcloudProblem.NOT_NEXTCLOUD, e)
    } catch (e: IOException) {
        val certificate = generateSequence<Throwable>(e) { it.cause }.any {
            it is SSLHandshakeException || it is SSLPeerUnverifiedException
        }
        throw NextcloudException(
            if (certificate) NextcloudProblem.CERTIFICATE else NextcloudProblem.UNREACHABLE,
            e,
        )
    }
}

/** Strict, bounded JSON for the sign-in answers (SEC: every answer is untrusted). */
internal object NextcloudJson {
    private const val MAX_DEPTH = 8

    fun parse(bytes: ByteArray): JsonObject {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            bad()
        }
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > MAX_DEPTH) bad()
                '}', ']' -> depth--
            }
        }
        val element = try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            bad()
        } catch (_: IllegalArgumentException) {
            bad()
        }
        return element as? JsonObject ?: bad()
    }

    fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun bad(): Nothing = throw NextcloudException(NextcloudProblem.NOT_NEXTCLOUD)
}
