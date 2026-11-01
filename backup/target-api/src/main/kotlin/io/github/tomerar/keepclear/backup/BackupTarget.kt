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
    /**
     * M17, ADR 0011: digests the **server itself** computes over the bytes it stored while a file is uploaded
     * (Nextcloud `X-Hash: sha256` → `X-Hash-SHA256`). [BackupTarget.serverDigest] returns them. Empty for targets
     * without one (USB, SAF, SMB, generic WebDAV). Values the client sent and the server merely stored (Nextcloud
     * `OC-Checksum`, `oc:checksums`) are never listed here: they prove nothing.
     */
    val serverDigests: Set<DigestKind> = emptySet(),
) {
    companion object {
        const val FAT32_MAX_FILE: Long = 4L * 1024 * 1024 * 1024 - 1
    }
}

/** A digest a server computes itself (ADR 0011). Only cryptographic kinds that ADR 0007 accepts as proof. */
enum class DigestKind {
    /** Lowercase hex SHA-256 of the stored bytes. */
    SHA256,
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

    /**
     * Like [create], when the caller knows how many bytes it will write (a network target can then announce
     * the length, M17). Writing more or fewer than [size] bytes fails the upload with a [StorageException];
     * no complete file is left under [rel] in that case.
     */
    suspend fun create(rel: String, size: Long): OutputStream = create(rel)

    /**
     * M17, ADR 0011: the digest of kind [kind] that the **server computed** over the bytes it stored for [rel]
     * during this target's own upload (followed through [rename]), or null when there is none (no server digest,
     * the server didn't send one, or the file wasn't uploaded through this object). Never a client-supplied value.
     * Only consulted when [TargetCaps.serverDigests] contains [kind].
     */
    suspend fun serverDigest(rel: String, kind: DigestKind): String? = null

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
