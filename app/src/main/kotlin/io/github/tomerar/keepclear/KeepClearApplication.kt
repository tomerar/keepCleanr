// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.Application
import android.os.storage.StorageManager
import io.github.tomerar.keepclear.core.database.KeepClearDatabase
import io.github.tomerar.keepclear.core.database.RoomTrashIndex
import io.github.tomerar.keepclear.core.database.ScanIndex
import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.OpResult
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.file.FileGateway
import io.github.tomerar.keepclear.core.storage.media.MediaStoreSource
import io.github.tomerar.keepclear.engine.trash.TrashManager
import java.util.UUID

/**
 * Manual dependency graph. Hilt is deferred until the graph is big enough to need it
 * (tasks.md M3 note); everything here is created lazily on first use.
 */
class KeepClearApplication : Application() {
    /** Identifies this process for plan confirmation (SAFETY-1, MIG-5). */
    val sessionId: String = UUID.randomUUID().toString()

    val database by lazy { KeepClearDatabase.build(this) }
    val scanIndex by lazy { ScanIndex(database) }
    val fileGateway by lazy { FileGateway() }
    val mediaSource by lazy { MediaStoreSource(contentResolver) }
    val protectedPaths by lazy { ProtectedPaths() }

    /** Mounted volume roots (internal storage, SD card, OTG). */
    fun volumeRoots(): List<String> = getSystemService(StorageManager::class.java).storageVolumes
        .mapNotNull { v -> v.directory?.takeIf { v.state == "mounted" }?.absolutePath }

    val trashManager by lazy {
        TrashManager(
            gateway = fileGateway,
            index = RoomTrashIndex(database.trashDao()),
            signer = KeystoreSidecarSigner(),
            clock = SystemTrashClock(contentResolver),
            protectedPaths = protectedPaths,
            volumeRoots = volumeRoots(),
        )
    }

    /** The single choke point for every destructive action (CLAUDE.md safety rule 3). */
    fun executor(onResult: (OpResult) -> Unit = {}) = ActionExecutor(
        gateway = fileGateway,
        guard = SafetyGuard(protectedPaths),
        protectedPaths = protectedPaths,
        handler = trashManager,
        currentSession = sessionId,
        onResult = onResult,
    )

    /** MOVE jobs: Room store, the running job, the foreground service (M10). */
    val moveController by lazy { MoveController(this) }

    override fun onCreate() {
        super.onCreate()
        TrashPurgeWorker.schedule(this)
    }
}
