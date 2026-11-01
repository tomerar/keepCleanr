// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.saf.DriveFormat
import io.github.tomerar.keepclear.backup.saf.TargetCheck
import io.github.tomerar.keepclear.backup.saf.TargetKind
import io.github.tomerar.keepclear.backup.smb.SmbCheck

/** A backup place, opened: a drive or folder picked in Android's picker (M10), or a network drive (M15). */
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

    /** Network drive: the address answered but refused the name or password. */
    data object Refused : OpenedTarget

    companion object {
        /** Network drives are stored as `smb://host:port/share/folder` (never trusted on its own, E-MOVE-2). */
        fun isNetwork(ref: String?) = ref?.startsWith("smb://") == true

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
    }
}
