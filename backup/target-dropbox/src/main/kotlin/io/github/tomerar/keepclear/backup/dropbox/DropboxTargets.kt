// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.dropbox

import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.Streams.readUpTo
import java.security.SecureRandom
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** What KeepClear found in the Dropbox folder the user connected (M17c). */
sealed interface DropboxCheck {
    data class Ok(val target: DropboxBackupTarget) : DropboxCheck

    /** Dropbox refused the sign-in (revoked, expired refresh token): connect Dropbox again. */
    data object Refused : DropboxCheck

    /** A `.keepclear-target` file exists but isn't ours or is damaged (SEC-3). Never overwritten. */
    data object DamagedMarker : DropboxCheck

    /** Dropbox's certificate wasn't trusted (a network that intercepts HTTPS). Never bypassed. */
    data object CertificateRejected : DropboxCheck

    /** Nothing answered (no network, Dropbox down, rate-limited). */
    data object Unreachable : DropboxCheck
}

object DropboxTargets {
    const val MARKER = ".keepclear-target"
    private val MARKER_ID = Regex("^[0-9a-f]{32}$")

    /**
     * Opaque reference stored with a job to reopen the same place (never trusted on its own, E-MOVE-2):
     * `dropbox:/<folder>` inside the App folder. No account name or token.
     */
    fun ref(folder: String): String = "dropbox:/" + folder.split('/').filter { it.isNotEmpty() }.joinToString("/")

    /** Target identity = place + marker id; a resume needs both to match (E-MOVE-2, SEC-3). */
    fun targetId(folder: String, markerId: String) = "${ref(folder)}|$markerId"

    /** Parses a marker file strictly (SEC-3): exactly one 128-bit lowercase hex id, at most 64 bytes. */
    fun parseMarker(bytes: ByteArray): String? {
        if (bytes.size > 64) return null
        return bytes.toString(Charsets.UTF_8).trim().takeIf { MARKER_ID.matches(it) }
    }

    private fun newMarkerId(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /**
     * Makes sure [folder] exists in the App folder, reads or creates the marker file and returns a ready target.
     * Network I/O; call off the main thread.
     */
    suspend fun open(
        auth: DropboxAuth,
        folder: String,
        label: String,
        endpoints: DropboxEndpoints = DropboxEndpoints.PRODUCTION,
        timeoutSeconds: Long = 30,
    ): DropboxCheck {
        val probe = DropboxBackupTarget(auth, folder, "probe", label, endpoints, timeoutSeconds)
        return try {
            probe.mkdirs("")
            val existing = probe.stat(MARKER)
            val markerId = if (existing == null) {
                val id = newMarkerId()
                val bytes = id.toByteArray()
                probe.create(MARKER, bytes.size.toLong()).use { it.write(bytes) }
                id
            } else {
                if (!existing.isFile) return DropboxCheck.DamagedMarker
                val bytes = probe.openRead(MARKER).use { it.readUpTo(65) }
                parseMarker(bytes) ?: return DropboxCheck.DamagedMarker
            }
            DropboxCheck.Ok(
                DropboxBackupTarget(auth, folder, targetId(folder, markerId), label, endpoints, timeoutSeconds),
            )
        } catch (_: StorageException.PermissionDenied) {
            DropboxCheck.Refused
        } catch (_: StorageException.AlreadyExists) {
            // Another device created the marker at the same moment: read it next time; nothing overwritten.
            DropboxCheck.Unreachable
        } catch (e: StorageException) {
            if (generateSequence<Throwable>(e) { it.cause }.any {
                    it is SSLHandshakeException || it is SSLPeerUnverifiedException
                }
            ) {
                DropboxCheck.CertificateRejected
            } else {
                DropboxCheck.Unreachable
            }
        } finally {
            probe.close()
        }
    }
}
