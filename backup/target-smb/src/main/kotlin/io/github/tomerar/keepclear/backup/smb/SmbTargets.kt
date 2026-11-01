// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.smb

import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.Streams.readUpTo
import java.security.SecureRandom

/** What KeepClear found at a network drive the user set up (M15). */
sealed interface SmbCheck {
    data class Ok(val target: SmbBackupTarget) : SmbCheck

    /** The address answered but the name or password was refused. */
    data object Refused : SmbCheck

    /** A `.keepclear-target` file exists but isn't ours or is damaged (SEC-3). Never overwritten. */
    data object DamagedMarker : SmbCheck

    /** Nothing answered (computer off or asleep, wrong address, other Wi-Fi). */
    data object Unreachable : SmbCheck
}

object SmbTargets {
    const val MARKER = ".keepclear-target"
    private val MARKER_ID = Regex("^[0-9a-f]{32}$")

    /** Opaque reference stored with a job to reopen the same place (never trusted on its own, E-MOVE-2). */
    fun ref(location: SmbLocation): String =
        "smb://${location.host}:${location.port}/${location.share}/${location.folder.trim('/')}"

    /** Target identity = place + marker id; a resume needs both to match (E-MOVE-2, SEC-3). */
    fun targetId(location: SmbLocation, markerId: String) = "${ref(location)}|$markerId"

    /** Parses a marker file strictly (SEC-3): exactly one 128-bit lowercase hex id, at most 64 bytes. */
    fun parseMarker(bytes: ByteArray): String? {
        if (bytes.size > 64) return null
        return bytes.toString(Charsets.UTF_8).trim().takeIf { MARKER_ID.matches(it) }
    }

    private fun newMarkerId(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /**
     * Connects, makes sure the folder exists, reads or creates the marker file and returns a ready target.
     * Blocking network I/O; call off the main thread.
     */
    suspend fun open(location: SmbLocation, label: String, timeoutSeconds: Long = 30): SmbCheck {
        val probe = SmbBackupTarget(location, id = "probe", label = label, timeoutSeconds = timeoutSeconds)
        return try {
            probe.mkdirs("")
            val existing = probe.stat(MARKER)
            val markerId = if (existing == null) {
                val id = newMarkerId()
                probe.create(MARKER).use { it.write(id.toByteArray()) }
                id
            } else {
                val bytes = probe.openRead(MARKER).use { it.readUpTo(65) }
                parseMarker(bytes) ?: return SmbCheck.DamagedMarker
            }
            SmbCheck.Ok(
                SmbBackupTarget(
                    location,
                    id = targetId(location, markerId),
                    label = label,
                    timeoutSeconds = timeoutSeconds,
                ),
            )
        } catch (_: StorageException.PermissionDenied) {
            SmbCheck.Refused
        } catch (_: StorageException.AlreadyExists) {
            // Another device created the marker at the same moment: read it next time; nothing overwritten.
            SmbCheck.Unreachable
        } catch (_: StorageException) {
            SmbCheck.Unreachable
        } finally {
            probe.close()
        }
    }
}
