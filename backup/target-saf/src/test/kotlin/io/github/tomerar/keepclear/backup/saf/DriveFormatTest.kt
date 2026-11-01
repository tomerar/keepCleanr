// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.saf

import io.github.tomerar.keepclear.backup.TargetCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Q4: exFAT lifts the 4 GiB limit; FAT32 and anything unknown or ambiguous keep it. */
class DriveFormatTest {
    private fun mounts(type: String, id: String = "1234-ABCD") = """
        /dev/block/dm-6 /data f2fs rw,lazytime 0 0
        /dev/block/vold/public:8,1 /mnt/media_rw/$id $type rw,dirsync,nosuid,nodev,noexec 0 0
        /dev/fuse /mnt/user/0/$id fuse rw,lazytime,nosuid,nodev,noexec 0 0
        /dev/fuse /storage/$id fuse rw,lazytime,nosuid,nodev,noexec 0 0
        """.trimIndent()

    @Test
    fun `known formats`() {
        mapOf(
            "vfat" to DriveFormat.FAT32,
            "exfat" to DriveFormat.EXFAT,
            "texfat" to DriveFormat.EXFAT,
            "ntfs3" to DriveFormat.NTFS,
            "ext4" to DriveFormat.EXT4,
            "f2fs" to DriveFormat.F2FS,
            "sdfat" to DriveFormat.UNKNOWN,
            "fuseblk" to DriveFormat.UNKNOWN,
        ).forEach { (type, expected) -> assertEquals(type, expected, DriveFormat.detect("1234-ABCD", mounts(type))) }
    }

    @Test
    fun `missing, unreadable or mismatched mount tables stay safe`() {
        assertEquals(DriveFormat.UNKNOWN, DriveFormat.detect("1234-ABCD", null))
        assertEquals(DriveFormat.UNKNOWN, DriveFormat.detect("1234-ABCD", ""))
        assertEquals(DriveFormat.UNKNOWN, DriveFormat.detect("9999-0000", mounts("exfat")))
        // A prefix of another id must not match.
        assertEquals(DriveFormat.UNKNOWN, DriveFormat.detect("1234", mounts("exfat")))
        assertEquals(DriveFormat.UNKNOWN, DriveFormat.detect("../x", mounts("exfat")))
        // Two different types for the same mount point: ambiguous.
        assertEquals(DriveFormat.UNKNOWN, DriveFormat.detect("1234-ABCD", mounts("exfat") + "\n" + mounts("vfat")))
    }

    @Test
    fun `only formats without the limit lift it`() {
        assertNull(DriveFormat.EXFAT.maxFileSize)
        assertEquals(TargetCaps.FAT32_MAX_FILE, DriveFormat.FAT32.maxFileSize)
        assertEquals(TargetCaps.FAT32_MAX_FILE, DriveFormat.UNKNOWN.maxFileSize)
    }
}
