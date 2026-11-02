// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.saf.DriveFormat
import io.github.tomerar.keepclear.backup.saf.TargetCheck
import io.github.tomerar.keepclear.backup.saf.TargetKind
import io.github.tomerar.keepclear.backup.smb.SmbCheck
import io.github.tomerar.keepclear.backup.webdav.WebDavCheck

/**
 * Runs [block] with this target and always releases it afterwards (a network drive's connection), also when
 * [block] fails or is cancelled (PR #25 review).
 */
suspend inline fun <T> BackupTarget.useTarget(block: (BackupTarget) -> T): T = try {
    block(this)
} finally {
    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { close() } }
}

/**
 * A backup place, opened: a drive or folder picked in Android's picker (M10), a network drive (M15), or the
 * user's Nextcloud (M17b, Advanced).
 */
sealed interface OpenedTarget {
    data class Ok(
        val target: BackupTarget,
        val kind: TargetKind,
        val format: DriveFormat = DriveFormat.UNKNOWN,
    ) : OpenedTarget

    data object OnThisPhone : OpenedTarget

    data object DamagedMarker : OpenedTarget

    /** Unplugged, grant gone, or (network) nothing answered at that address. */
    data object Unavailable : OpenedTarget

    /** Network drive or Nextcloud: the address answered but refused the name or (app) password. */
    data object Refused : OpenedTarget

    /** M17b: the Nextcloud's certificate isn't trusted or is for another name. Never bypassed (C16). */
    data object CertificateRejected : OpenedTarget

    companion object {
        /** Network drives are stored as `smb://host:port/share/folder` (never trusted on its own, E-MOVE-2). */
        fun isSmb(ref: String?) = ref?.startsWith("smb://") == true

        /**
         * M17b: the Nextcloud is stored as `davs://host[:port]/remote.php/dav/files/<user>` (`dav://` only for this
         * phone itself, tests), without the user name or app password (never trusted on its own, E-MOVE-2).
         */
        fun isNextcloud(ref: String?) = ref?.startsWith("davs://") == true || ref?.startsWith("dav://") == true

        /** Any place KeepClear reaches over the network: every read comes from the server (M15, M17b). */
        fun isNetwork(ref: String?) = isSmb(ref) || isNextcloud(ref)

        /** A job that backs up to the network drive must stop when the drive is forgotten (C9). */
        fun usesNetworkDrive(job: io.github.tomerar.keepclear.engine.move.MoveJob?) = isSmb(job?.targetRef)

        /** M17b: likewise for the Nextcloud (C27). */
        fun usesNextcloud(job: io.github.tomerar.keepclear.engine.move.MoveJob?) = isNextcloud(job?.targetRef)

        fun of(check: TargetCheck): OpenedTarget = when (check) {
            is TargetCheck.Ok -> Ok(check.target, check.kind, check.format)
            TargetCheck.OnThisPhone -> OnThisPhone
            TargetCheck.DamagedMarker -> DamagedMarker
            TargetCheck.Unavailable -> Unavailable
        }

        fun of(check: SmbCheck): OpenedTarget = when (check) {
            is SmbCheck.Ok -> Ok(check.target, TargetKind.NETWORK)
            SmbCheck.Refused -> Refused
            SmbCheck.DamagedMarker -> DamagedMarker
            SmbCheck.Unreachable -> Unavailable
        }

        fun of(check: WebDavCheck): OpenedTarget = when (check) {
            is WebDavCheck.Ok -> Ok(check.target, TargetKind.NETWORK)
            WebDavCheck.Refused -> Refused
            WebDavCheck.DamagedMarker -> DamagedMarker
            WebDavCheck.CertificateRejected -> CertificateRejected
            WebDavCheck.Unreachable -> Unavailable
        }
    }
}
