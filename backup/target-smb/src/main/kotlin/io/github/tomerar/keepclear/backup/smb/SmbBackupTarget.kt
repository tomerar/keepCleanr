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
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
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
) {
    init {
        require(SmbHosts.isPlainHost(host)) { "host must be a plain name or address" }
        require(share.isNotBlank() && '/' !in share && '\\' !in share) { "share must be one name" }
        require(port in 1..65535)
    }

    override fun toString(): String = "SmbLocation($host:$port/$share/$folder, user=${user.ifEmpty { "guest" }})"
}

/** ADR 0011 network rule: SMB only ever connects to the host the user typed, never to a name it was redirected to. */
object SmbHosts {
    private val name = Regex("""^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$""")
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** A host name or IPv4 address only: no scheme, no path, no user info, no port. */
    fun isPlainHost(host: String): Boolean {
        ipv4.matchEntire(host)?.let { m -> return m.groupValues.drop(1).all { it.toInt() in 0..255 } }
        return name.matches(host) && !host.contains("..")
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
 * - Names follow the Windows rules (case-insensitive, 260-character paths, see `TargetNames`).
 */
class SmbBackupTarget(
    private val location: SmbLocation,
    override val id: String,
    override val label: String,
    private val timeoutSeconds: Long = 30,
) : BackupTarget {
    override val caps = TargetCaps(canReadBack = true, caseSensitive = false, maxPathChars = 260)

    private val mutex = Mutex()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null

    private fun config() = SmbConfig.builder()
        .withSecurityProvider(BCSecurityProvider())
        .withTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .withSoTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    /** The share, connected on first use and again after a drop. */
    private suspend fun share(): DiskShare = mutex.withLock {
        diskShare?.takeIf { it.isConnected }?.let { return it }
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
        val s = conn.authenticate(auth)
        session = s
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

    /** Disconnects; the next call connects again. */
    suspend fun close() = mutex.withLock { closeQuietly() }

    private fun smbPath(rel: String): String {
        require(!rel.startsWith("/")) { "relative path expected" }
        return listOf(location.folder.trim('/'), rel.trim('/'))
            .filter { it.isNotEmpty() }
            .joinToString("/")
            .replace('/', '\\')
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
            throw map(e, path)
        } catch (e: IOException) {
            // Connection refused, timeout, dropped Wi-Fi: the medium is gone for now (pause, never delete).
            mutex.withLock { closeQuietly() }
            throw StorageException.Disconnected(path, e)
        } catch (e: RuntimeException) {
            // smbj wraps transport failures (TransportException, SMBRuntimeException) in runtime exceptions.
            mutex.withLock { closeQuietly() }
            throw StorageException.Disconnected(path, e)
        }
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
            .filter { it.fileName != "." && it.fileName != ".." }
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

        private inline fun <T> mapped(path: KPath, block: () -> T): T = try {
            block()
        } catch (e: StorageException) {
            throw e
        } catch (e: SMBApiException) {
            throw map(e, path)
        } catch (e: IOException) {
            throw StorageException.Disconnected(path, e)
        } catch (e: RuntimeException) {
            throw StorageException.Disconnected(path, e)
        }
    }
}
