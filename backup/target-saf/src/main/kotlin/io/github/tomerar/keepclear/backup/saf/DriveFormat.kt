// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.saf

import io.github.tomerar.keepclear.backup.TargetCaps

/**
 * The file system of a removable drive (owner decision Q4). SAF doesn't expose it, so KeepClear reads the
 * mount table the system already shows to apps (`/proc/self/mounts`), where Android mounts a USB drive or SD
 * card with id `1234-ABCD` at `/mnt/media_rw/1234-ABCD`. If that line isn't visible (it may not be on every
 * Android version or phone), the format is [UNKNOWN] and the safe FAT32 limit stays.
 */
enum class DriveFormat(
    /** Largest file the format can hold; null when it has no practical limit for photos and videos. */
    val maxFileSize: Long?,
) {
    FAT32(TargetCaps.FAT32_MAX_FILE),
    EXFAT(null),
    NTFS(null),
    EXT4(null),
    F2FS(null),

    /** Couldn't tell: treated like FAT32, so files over 4 GiB are blocked with a message, never truncated. */
    UNKNOWN(TargetCaps.FAT32_MAX_FILE),
    ;

    companion object {
        /**
         * Finds the format of volume [volumeId] (the part of the tree document id before ':') in [mounts]
         * (the text of `/proc/self/mounts`). Only exact, known type names count; anything ambiguous, such as
         * Samsung's `sdfat` driver that serves both FAT32 and exFAT, is [UNKNOWN].
         */
        fun detect(volumeId: String, mounts: String?): DriveFormat {
            if (mounts == null || volumeId.isBlank() || '/' in volumeId) return UNKNOWN
            val types = mounts.lineSequence().mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 3) return@mapNotNull null
                fields[2].takeIf { fields[1] == "/mnt/media_rw/$volumeId" }
            }.toSet()
            if (types.size != 1) return UNKNOWN
            return when (types.single().lowercase()) {
                "vfat", "msdos", "fat" -> FAT32
                "exfat", "texfat" -> EXFAT
                "ntfs", "ntfs3", "tntfs" -> NTFS
                "ext4" -> EXT4
                "f2fs" -> F2FS
                else -> UNKNOWN
            }
        }

        /** Reads the mount table, or null if it can't be read. */
        fun readMounts(): String? = runCatching { java.io.File("/proc/self/mounts").readText() }.getOrNull()
    }
}
