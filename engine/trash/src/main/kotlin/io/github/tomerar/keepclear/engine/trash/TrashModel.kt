// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

/** Stored by name; never rename (MIG-2). */
enum class TrashState {
    /** Journal written, file not (yet) moved. Resolved by [TrashManager.recover]. */
    PENDING,

    /** File is in the trash. */
    ACTIVE,
}

/**
 * One trashed item (ADR 0006/0010). Room holds these (source of truth); every ACTIVE entry also has a
 * signed sidecar next to the file so the trash survives app reinstall (E-DEL-8).
 */
data class TrashEntry(
    val id: String,
    val originalPath: String,
    /** Where the file sits inside `.KeepClearTrash`. */
    val trashedPath: String,
    val volumeRoot: String,
    val size: Long,
    val mtimeMillis: Long,
    val sha256: String? = null,
    val ruleId: String? = null,
    val reason: String,
    val deletedAt: Long,
    val expiresAt: Long,
    /** Elapsed-realtime clock at deletion and the boot it belongs to (E-DEL-10). */
    val deletedAtElapsed: Long,
    val bootId: String,
    val state: TrashState,
    /** False for entries rebuilt from a sidecar whose HMAC didn't verify (SEC-1). */
    val trusted: Boolean = true,
    /** Verified backup copy (MOVE, TRASH-5), e.g. "SanDisk:KeepClear/…/IMG.jpg". */
    val verifiedCopy: String? = null,
)

/** Port: persisted trash index (Room in the app, in-memory in tests). */
interface TrashIndex {
    suspend fun put(entry: TrashEntry)

    suspend fun get(id: String): TrashEntry?

    suspend fun byTrashedPath(path: String): TrashEntry?

    suspend fun all(): List<TrashEntry>

    suspend fun remove(id: String)
}

/** Port: signs and verifies sidecars (Android Keystore HMAC in the app, fixed key in tests). */
interface SidecarSigner {
    fun sign(canonical: ByteArray): String

    fun verify(canonical: ByteArray, mac: String): Boolean
}

/** Port: clocks (E-DEL-10). */
interface TrashClock {
    fun wallMillis(): Long

    fun elapsedMillis(): Long

    /** Changes on every reboot (Android: `Settings.Global.BOOT_COUNT`). */
    fun bootId(): String
}

/** Retention (TRASH-2; owner decision Q2: 30 days for every category). */
object TrashPolicy {
    const val RETENTION_MILLIS: Long = 30L * 24 * 3_600_000
    const val MIN_ELAPSED_BEFORE_PURGE: Long = 24L * 3_600_000
    const val TRASH_DIR = ".KeepClearTrash"
}
