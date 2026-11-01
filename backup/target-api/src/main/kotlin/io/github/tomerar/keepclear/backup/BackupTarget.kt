// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup

import io.github.tomerar.keepclear.core.storage.Stat
import java.io.InputStream
import java.io.OutputStream

/** What a backup target can do and what it can't store (design.md §6.1, MOVE-5). */
data class TargetCaps(
    /**
     * True when KeepClear can read the copy back **from the medium itself** (L3, MOVE-14): only a removable drive
     * through Android's external-storage provider. A read right after writing may still come from the page
     * cache, so freeing also needs the second, uncached pass (ADR 0007 amendment, H1). False for cloud and
     * unknown providers.
     */
    val canReadBack: Boolean,
    /** Largest file the medium accepts (FAT32: 4 GiB − 1), or null if unknown/unlimited. */
    val maxFileSize: Long? = null,
    val caseSensitive: Boolean = false,
    /** Longest full path in characters (Windows/SMB: 260), or null. */
    val maxPathChars: Int? = null,
    /**
     * M15: every read goes to the server over the network and the phone keeps no copy of the data (SMB), so a
     * read over a **new connection** is a fresh read of what the server stores (H1, [FreshRead] NEW_SESSION).
     */
    val readsFromServer: Boolean = false,
) {
    companion object {
        const val FAT32_MAX_FILE: Long = 4L * 1024 * 1024 * 1024 - 1
    }
}

/**
 * A place backups go: a USB-C drive or SD card (M10), SMB (M13). All paths are relative to the
 * target's root and use '/'.
 */
interface BackupTarget {
    /** Stable id of the medium (volume/tree identity), used with the marker file (E-MOVE-2, SEC-3). */
    val id: String
    val label: String
    val caps: TargetCaps

    /**
     * Volume the target lives on, in the same terms as source files: "primary" for the phone's shared storage,
     * the volume UUID for a card or USB drive, null when unknown (cloud). Used to block same-volume backups (H3).
     */
    val volumeId: String? get() = null

    suspend fun freeBytes(): Long?

    suspend fun mkdirs(rel: String)

    /** Creates a new file (never overwrites). */
    suspend fun create(rel: String): OutputStream

    /** Renames within the target; fails if [toRel] exists. */
    suspend fun rename(fromRel: String, toRel: String)

    suspend fun stat(rel: String): Stat?

    suspend fun openRead(rel: String): InputStream

    /** Deletes a file KeepClear itself created (temp files, its own old manifest). */
    suspend fun delete(rel: String)

    suspend fun list(relDir: String): List<Stat>

    /** Releases what the target holds open (a network drive's connection). It reconnects if used again. */
    suspend fun close() {}
}
