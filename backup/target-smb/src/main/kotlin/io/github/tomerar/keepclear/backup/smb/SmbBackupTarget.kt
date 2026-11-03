// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.backup.net.HostRule
import io.github.tomerar.keepclear.backup.net.ThisDevice
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.Stat
import io.github.tomerar.keepclear.core.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Where an SMB backup goes: what the user typed on the phone (M15). The password is never logged. */
data class SmbLocation(
    val host: String,
    val share: String,
    /** Folder inside the share, '/'-separated, may be empty. */
    val folder: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    val port: Int = 445,
    /** D3: the app always uses [HostRule.NetworkOnly]; only tests reach a Samba on their own machine. */
    val hosts: HostRule = HostRule.NetworkOnly,
) {
    init {
        require(SmbHosts.isPlainHost(host)) { "host must be a plain name or address" }
        // D3: a share on the phone itself is a copy on the same phone, not a backup.
        require(hosts.allowsThisDevice || !SmbHosts.isThisPhone(host)) { "this phone itself" }
        require(SmbPaths.isSafeSegment(share)) { "share must be one name" }
        require(folder.split('/').filter { it.isNotEmpty() }.all(SmbPaths::isSafeSegment)) { "folder not allowed" }
        require(port in 1..65535)
        // PR H5: the domain field; a name only (never a path, a user or a second address).
        require(SmbFields.isDomain(domain)) { "domain not allowed" }
    }

    /** D3: no user name means a guest session, which can't be signed: copies only, never "free up space". */
    val isGuest: Boolean get() = user.isEmpty()

    override fun toString(): String = "SmbLocation($host:$port/$share/$folder, user=${user.ifEmpty { "guest" }})"
}

/** ADR 0011 network rule: SMB only ever connects to the host the user typed, never to a name it was redirected to. */
object SmbHosts {
    private val name = Regex("""^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$""")
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * A host name or IPv4 address only: no scheme, no path, no user info, no port, and no number the system would
     * read in a surprising way (`127.1`, `2130706433`, `010.0.0.1`; D3).
     */
    fun isPlainHost(host: String): Boolean {
        if (ThisDevice.isAmbiguousNumber(host)) return false
        ipv4.matchEntire(host)?.let { m -> return m.groupValues.drop(1).all { it.toInt() in 0..255 } }
        return name.matches(host) && !host.contains("..")
    }

    /**
     * D3: the phone itself by its text (`localhost`, `127.x.x.x`, `0.0.0.0`). The phone's own network addresses and
     * names that lead to it are refused when connecting ([SmbCheck.OnThisPhone]).
     */
    fun isThisPhone(host: String): Boolean = ThisDevice.isLocalName(host)
}

/**
 * PR H5: the network form's optional fields, as Material Files has them: port („445”), domain and a folder inside the
 * share („אפשר להשאיר ריק”). Pure checks, so the form and [SmbLocation] refuse the same things; none of them changes
 * which host is used ([HostRule.NetworkOnly], D3).
 */
object SmbFields {
    const val DEFAULT_PORT = 445

    private val domainName = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,253}[A-Za-z0-9])?$""")

    /** The port typed: empty is 445; otherwise digits only, 1 to 65535; null when not allowed. */
    fun port(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return DEFAULT_PORT
        if (t.length > 5 || !t.all { it in '0'..'9' }) return null
        return t.toInt().takeIf { it in 1..65535 }
    }

    /** Empty, or a Windows/NetBIOS or DNS-style name: letters, digits, `.`, `-`, `_`, no `..`. */
    fun isDomain(text: String): Boolean = text.isEmpty() || (domainName.matches(text) && ".." !in text)

    /**
     * The folder inside the share as typed, made plain ("Backups/Phone"), or null when it would leave the share or
     * name something odd: it may not start with `/` or `\` (absolute), contain `\`, `:` or control characters, or have
     * a `.`/`..` or empty segment in the middle. A trailing `/` is dropped; empty means the share itself.
     */
    fun folder(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith('/') || t.startsWith('\\') || '\\' in t) return null
        val segments = t.trimEnd('/').split('/')
        if (segments.any { !SmbPaths.isSafeSegment(it) }) return null
        return segments.joinToString("/")
    }
}

/** D3: what may be sent to the share as one path segment. */
object SmbPaths {
    /**
     * Not empty, not `.` or `..`, and no `\` or `/` (separators), `:` (an NTFS alternate data stream, `name:stream`) or
     * control characters. Server-sent names that fail this are never listed or used.
     */
    fun isSafeSegment(s: String): Boolean = s.isNotEmpty() && s != "." && s != ".." &&
        s.none { it == '\\' || it == '/' || it == ':' || it.code < 0x20 || it == '\u007f' }
}

/** D3: whether an SMB session may be used, and how. */
internal object SmbSessionCheck {
    /**
     * Null when the session is acceptable, else why not. A signed-in user's session must be signed or encrypted and
     * must really be that user: a server that quietly made it a guest or anonymous session (Samba's
     * `map to guest = bad user`) didn't check the password, and an unsigned session can be changed on the way
     * by anyone on the network. A guest place is accepted unsigned, but only ever for copies ([SmbBackupTarget]).
     */
    fun problem(guestPlace: Boolean, guest: Boolean, anonymous: Boolean, signed: Boolean, encrypted: Boolean): String? =
        when {
            guestPlace -> null
            guest || anonymous -> "the server signed in as a guest, not as the user"
            !signed && !encrypted -> "the session is not signed"
            else -> null
        }
}

/**
 * M15 (MOVE-3), ADR 0011: a backup target on an SMB share (a home PC or NAS), reached over the local network.
 *
 * - SMB has no server-side digest, so verification is a full read-back (`canReadBack`, ADR 0007): the copy is
 *   read again over the network and hashed.
 * - **Never overwrite:** files are created with `FILE_CREATE` and renamed with `replaceIfExists = false`.
 * - Every failure becomes a [StorageException]: an unreachable server or dropped Wi-Fi is
 *   [StorageException.Disconnected], so a job pauses and nothing is deleted (E-MOVE-2).
 * - Names follow the Windows rules (case-insensitive, 260-character paths, see `TargetNames`); every path segment
 *   sent to the share is checked ([SmbPaths], D3).
 * - D3 (owner review 2026-11-02): message **signing is required** and SMB3 **encryption is preferred** (used when the
 *   server offers it). A signed-in user's session that isn't signed, or that the server turned into a guest one, is
 *   refused. A **guest** place can't be signed: KeepClear copies to it but never frees the originals
 *   ([TargetCaps.canFreeOriginals] false), because anyone on the Wi-Fi could change or fake what it reads back.
 * - D3: never to the phone itself: every connection checks the address it connects to ([ThisDevice]).
 */
class SmbBackupTarget(
    private val location: SmbLocation,
    override val id: String,
    override val label: String,
    private val timeoutSeconds: Long = 30,
) : BackupTarget {
    override val caps = TargetCaps(
        canReadBack = true,
        caseSensitive = false,
        maxPathChars = 260,
        readsFromServer = true,
        canFreeOriginals = !location.isGuest,
    )

    private val mutex = Mutex()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null

    internal fun config(): SmbConfig = SmbConfig.builder()
        .withSecurityProvider(BCSecurityProvider())
        .withTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .withSoTimeout(timeoutSeconds, TimeUnit.SECONDS)
        // D3: a guest session can't be signed (smbj refuses to try); everything else must be.
        .withSigningRequired(!location.isGuest)
        // D3: SMB3 encryption whenever the server offers it (smbj then encrypts instead of signing).
        .withEncryptData(true)
        // D3: refuses this phone's own addresses, after DNS, right before connecting.
        // (Connecting waits at most 5 s, like smbj's own default, so a computer that is off is noticed quickly.)
        .withSocketFactory(ThisDevice.socketFactory(location.hosts, minOf(timeoutSeconds * 1000, 5_000L).toInt()))
        .build()

    // Set when any call or stream saw the connection, session or share go away. smbj's `isConnected` is only a
    // local flag, so a server that drops the session or share (but not the TCP connection) would otherwise be
    // reused forever (PR #24 review). The next call then connects again from scratch.
    @Volatile
    private var stale = false

    /** The share, connected on first use and again after a drop. */
    private suspend fun share(): DiskShare = mutex.withLock {
        if (!stale) diskShare?.takeIf { it.isConnected }?.let { return it }
        stale = false
        closeQuietly()
        val c = SMBClient(config())
        client = c
        val conn = c.connect(location.host, location.port)
        connection = conn
        val auth = if (location.user.isEmpty()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(location.user, location.password.toCharArray(), location.domain.ifEmpty { null })
        }
        val s = try {
            conn.authenticate(auth)
        } catch (e: SMB2GuestSigningRequiredException) {
            // The server answered a named user with a guest session: the password wasn't checked (D3).
            throw StorageException.PermissionDenied(KPath.Local("/"), e)
        }
        session = s
        SmbSessionCheck.problem(
            guestPlace = location.isGuest,
            guest = s.isGuest,
            anonymous = s.isAnonymous,
            signed = s.isSigningRequired,
            encrypted = s.shouldEncryptData(),
        )?.let { why -> throw StorageException.PermissionDenied(KPath.Local("/"), IOException(why)) }
        val share = s.connectShare(location.share) as? DiskShare
            ?: throw StorageException.Unsupported(KPath.Local("/"), "not a folder share")
        diskShare = share
        share
    }

    private fun closeQuietly() {
        runCatching { diskShare?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        diskShare = null
        session = null
        connection = null
        client = null
    }

    /** The current session (tests check how it was set up, D3). */
    internal fun sessionForTests(): Session? = session

    /** Disconnects; the next call connects again. */
    override suspend fun close() = mutex.withLock { closeQuietly() }

    /** D3: every segment checked ([SmbPaths]); a bad one is refused, never sent. */
    internal fun smbPath(rel: String): String {
        require(!rel.startsWith("/")) { "relative path expected" }
        val segments = (location.folder.split('/') + rel.split('/')).filter { it.isNotEmpty() }
        if (!segments.all(SmbPaths::isSafeSegment)) {
            throw StorageException.Unsupported(KPath.Local("/"), "name not allowed on a network drive")
        }
        return segments.joinToString("\\")
    }

    /** A target-relative path shown as `/<rel>` in errors and stats (never a path on the phone). */
    private fun kpath(
        rel: String,
    ): KPath.Local = rel.trim('/').let { if (it.isEmpty()) KPath.Local("/") else KPath.Local("/$it") }

    /** Runs [block] on the IO dispatcher and maps every SMB failure to a [StorageException]. */
    private suspend fun <T> io(rel: String, block: suspend (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        val path = kpath(rel)
        try {
            block(share())
        } catch (e: StorageException) {
            throw e
        } catch (e: SMBApiException) {
            // A session or share the server dropped maps to Disconnected: reconnect next time.
            throw map(e, path).also { if (it is StorageException.Disconnected) stale = true }
        } catch (e: IOException) {
            // Connection refused, timeout, dropped Wi-Fi: the medium is gone for now (pause, never delete).
            stale = true
            throw StorageException.Disconnected(path, e)
        } catch (e: RuntimeException) {
            // smbj wraps transport failures (TransportException, SMBRuntimeException) in runtime exceptions.
            stale = true
            throw StorageException.Disconnected(path, e)
        }
    }

    /** The same mapping for streams, which run outside [io]. */
    private inline fun <T> mapped(path: KPath, block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (e: SMBApiException) {
        throw map(e, path).also { if (it is StorageException.Disconnected) stale = true }
    } catch (e: IOException) {
        stale = true
        throw StorageException.Disconnected(path, e)
    } catch (e: RuntimeException) {
        stale = true
        throw StorageException.Disconnected(path, e)
    }

    override suspend fun freeBytes(): Long? = io("") { share ->
        val info = share.shareInformation
        info.freeSpace
    }

    override suspend fun mkdirs(rel: String) = io(rel) { share ->
        // The base folder inside the share first (it may not exist yet), then the relative path.
        var built = ""
        for (segment in location.folder.split('/').filter { it.isNotEmpty() }) {
            built = if (built.isEmpty()) segment else "$built\\$segment"
            if (!share.folderExists(built)) share.mkdir(built)
        }
        var current = ""
        for (segment in rel.split('/').filter { it.isNotEmpty() }) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            val p = smbPath(current)
            when {
                share.folderExists(p) -> Unit
                share.fileExists(p) -> throw StorageException.NotADirectory(kpath(current))
                else -> share.mkdir(p)
            }
        }
    }

    override suspend fun create(rel: String): OutputStream = io(rel) { share ->
        val file = share.openFile(
            smbPath(rel),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.noneOf(SMB2ShareAccess::class.java),
            // Never overwrite (MOVE-6): fails with OBJECT_NAME_COLLISION if the name is taken.
            SMB2CreateDisposition.FILE_CREATE,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        val path = kpath(rel)
        object : FilterOutputStream(file.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) = mapped(path) { out.write(b, off, len) }

            override fun write(b: Int) = mapped(path) { out.write(b) }

            override fun flush() = mapped(path) { out.flush() }

            override fun close() = mapped(path) {
                try {
                    out.close()
                    // SMB2 FLUSH: ask the server to put the data on disk before the copy is checked (research 06).
                    file.flush()
                } finally {
                    file.close()
                }
            }
        }
    }

    override suspend fun rename(fromRel: String, toRel: String) = io(fromRel) { share ->
        share.openFile(
            smbPath(fromRel),
            EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
            null,
            EnumSet.noneOf(SMB2ShareAccess::class.java),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { it.rename(smbPath(toRel), false) }
    }

    override suspend fun stat(rel: String): Stat? = io(rel) { share ->
        val p = smbPath(rel)
        if (p.isNotEmpty() && !share.fileExists(p) && !share.folderExists(p)) return@io null
        val info = share.getFileInformation(p)
        Stat(
            path = kpath(rel),
            name = rel.substringAfterLast('/'),
            kind = if (info.standardInformation.isDirectory) Stat.Kind.DIRECTORY else Stat.Kind.FILE,
            size = info.standardInformation.endOfFile,
            mtimeMillis = info.basicInformation.lastWriteTime.toEpochMillis(),
        )
    }

    override suspend fun openRead(rel: String): InputStream = io(rel) { share ->
        val p = smbPath(rel)
        if (share.folderExists(p)) throw StorageException.IsADirectory(kpath(rel))
        val file = share.openFile(
            p,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        val path = kpath(rel)
        object : FilterInputStream(file.inputStream) {
            override fun read(): Int = mapped(path) { `in`.read() }

            override fun read(b: ByteArray, off: Int, len: Int): Int = mapped(path) { `in`.read(b, off, len) }

            override fun close() = mapped(path) {
                try {
                    `in`.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    override suspend fun delete(rel: String) = io(rel) { share ->
        val p = smbPath(rel)
        when {
            share.folderExists(p) -> share.rmdir(p, false)
            share.fileExists(p) -> share.rm(p)
            else -> throw StorageException.NotFound(kpath(rel))
        }
    }

    override suspend fun list(relDir: String): List<Stat> = io(relDir) { share ->
        val p = smbPath(relDir)
        if (p.isNotEmpty() && !share.folderExists(p)) throw StorageException.NotFound(kpath(relDir))
        share.list(p)
            // D3: "." and "..", and any name the server sends that couldn't be used safely, are left out.
            .filter { SmbPaths.isSafeSegment(it.fileName) }
            .map { e ->
                val rel = if (relDir.isEmpty()) e.fileName else "${relDir.trimEnd('/')}/${e.fileName}"
                val dir = e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                Stat(
                    path = kpath(rel),
                    name = e.fileName,
                    kind = if (dir) Stat.Kind.DIRECTORY else Stat.Kind.FILE,
                    size = e.endOfFile,
                    mtimeMillis = e.lastWriteTime.toEpochMillis(),
                )
            }
    }

    companion object {
        fun map(e: SMBApiException, path: KPath): StorageException = when (e.status) {
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> {
                StorageException.AlreadyExists(path)
            }

            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
            -> {
                StorageException.NotFound(path)
            }

            NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> {
                StorageException.NotEmpty(path)
            }

            NtStatus.STATUS_FILE_IS_A_DIRECTORY -> {
                StorageException.IsADirectory(path)
            }

            NtStatus.STATUS_NOT_A_DIRECTORY -> {
                StorageException.NotADirectory(path)
            }

            NtStatus.STATUS_DISK_FULL -> {
                StorageException.NoSpace(path)
            }

            NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_BAD_NETWORK_NAME -> {
                StorageException.PermissionDenied(path, e)
            }

            NtStatus.STATUS_NETWORK_NAME_DELETED, NtStatus.STATUS_USER_SESSION_DELETED -> {
                StorageException.Disconnected(path, e)
            }

            else -> {
                StorageException.Io(path, e)
            }
        }
    }
}
