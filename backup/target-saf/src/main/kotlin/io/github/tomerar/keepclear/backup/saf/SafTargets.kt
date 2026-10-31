// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.core.storage.Streams.readUpTo
import java.security.SecureRandom

/** What KeepClear can promise for a picked folder (MOVE-2, MOVE-14). */
sealed interface TargetCheck {
    data class Ok(val target: SafBackupTarget, val kind: TargetKind) : TargetCheck

    /** The folder is on the phone's own storage: a "backup" there frees nothing and protects nothing. */
    data object OnThisPhone : TargetCheck

    /** A `.keepclear-target` file exists but isn't ours or is damaged (SEC-3). Never overwritten. */
    data object DamagedMarker : TargetCheck

    /** The folder can't be opened or written (unplugged, grant gone, read-only). */
    data object Unavailable : TargetCheck
}

enum class TargetKind {
    /** USB-C drive or SD card through the system's external storage provider: copy, verify, free originals. */
    REMOVABLE,

    /** A cloud provider that uploads later: copying is allowed, freeing originals is not (MOVE-14). */
    CLOUD_COPY_ONLY,

    /** Unknown provider: verified by reading back, with a warning that the provider may serve a cached copy. */
    UNKNOWN_PROVIDER,
}

object SafTargets {
    const val MARKER = ".keepclear-target"
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** Providers that upload in the background; reading back proves nothing about the cloud copy. */
    val CLOUD_AUTHORITIES = setOf(
        "com.google.android.apps.docs.storage",
        "com.google.android.apps.docs.storage.legacy",
        "com.microsoft.skydrive.content.StorageAccessProvider",
        "com.dropbox.product.android.dbapp.document_provider.documents",
        "com.box.android.documents",
        "org.nextcloud.documents",
        "com.owncloud.android.providers",
        "com.mega.android.provider",
    )

    private val MARKER_ID = Regex("^[0-9a-f]{32}$")

    /** Pure classification, unit-tested: authority + tree document id → kind and caps, or null if on the phone. */
    fun classify(authority: String?, treeDocId: String): Pair<TargetKind, TargetCaps>? = when {
        authority == EXTERNAL_STORAGE && (treeDocId.startsWith("primary:") || treeDocId == "primary") -> {
            null
        }

        authority == EXTERNAL_STORAGE -> {
            // The file system isn't visible through SAF; assume FAT32's 4 GiB limit (blocks, never truncates).
            TargetKind.REMOVABLE to TargetCaps(canReadBack = true, maxFileSize = TargetCaps.FAT32_MAX_FILE)
        }

        authority in CLOUD_AUTHORITIES -> {
            TargetKind.CLOUD_COPY_ONLY to TargetCaps(canReadBack = false)
        }

        else -> {
            TargetKind.UNKNOWN_PROVIDER to TargetCaps(canReadBack = true, maxFileSize = TargetCaps.FAT32_MAX_FILE)
        }
    }

    /** Parses a marker file strictly (SEC-3): exactly one 128-bit lowercase hex id, at most 64 bytes. */
    fun parseMarker(bytes: ByteArray): String? {
        if (bytes.size > 64) return null
        val text = bytes.toString(Charsets.UTF_8).trim()
        return text.takeIf { MARKER_ID.matches(it) }
    }

    fun newMarkerId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    /** Target identity = tree document id + marker id; a resume needs both to match (E-MOVE-2, SEC-3). */
    fun targetId(treeDocId: String, markerId: String) = "$treeDocId|$markerId"

    /**
     * Opens a picked tree: classifies it, reads or creates the marker file and returns a ready target.
     * Blocking; call off the main thread.
     */
    suspend fun open(resolver: ContentResolver, treeUri: Uri, label: String): TargetCheck {
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return TargetCheck.Unavailable
        val (kind, caps) = classify(treeUri.authority, treeDocId) ?: return TargetCheck.OnThisPhone
        val probe = SafBackupTarget(resolver, treeUri, targetId(treeDocId, "pending"), label, caps)
        if (!probe.isAttached()) return TargetCheck.Unavailable
        val markerId = try {
            val existing = probe.stat(MARKER)
            if (existing != null) {
                val bytes = probe.openRead(MARKER).use { it.readUpTo(65) }
                parseMarker(bytes) ?: return TargetCheck.DamagedMarker
            } else {
                newMarkerId().also { id -> probe.create(MARKER).use { it.write(id.toByteArray(Charsets.UTF_8)) } }
            }
        } catch (e: java.io.IOException) {
            return TargetCheck.Unavailable
        }
        return TargetCheck.Ok(SafBackupTarget(resolver, treeUri, targetId(treeDocId, markerId), label, caps), kind)
    }
}
