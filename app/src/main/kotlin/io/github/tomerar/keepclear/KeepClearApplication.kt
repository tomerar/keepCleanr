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

    /** Own folders are PROTECTED under this build's real package (test builds end in `.test`). */
    val protectedPaths by lazy { ProtectedPaths(ownPackage = packageName) }

    /** Mounted volume roots (internal storage, SD card, OTG). */
    fun volumeRoots(): List<String> = getSystemService(StorageManager::class.java).storageVolumes
        .mapNotNull { v -> v.directory?.takeIf { v.state == "mounted" }?.absolutePath }

    val trashManager by lazy {
        TrashManager(
            gateway = fileGateway,
            index = RoomTrashIndex(database.trashDao()),
            signer = KeystoreSidecarSigner(),
            clock = trashClock,
            protectedPaths = protectedPaths,
            volumeRoots = volumeRoots(),
            // E-DEL-10: a refused purge run is logged (no file paths).
            onClockJump = { j ->
                android.util.Log.w(
                    "KeepClear-Trash",
                    "purge refused: clock jump wall=${j.wallDeltaMillis}ms uptime=${j.uptimeDeltaMillis}ms " +
                        "sameBoot=${j.sameBoot}",
                )
            },
        )
    }

    val trashClock by lazy { SystemTrashClock(contentResolver, this) }

    /** File paths through the file gateway, MediaStore rows through MediaStore (M12). */
    val actionGateway by lazy {
        io.github.tomerar.keepclear.core.storage.RoutingGateway(
            fileGateway,
            media = io.github.tomerar.keepclear.core.storage.media.MediaGateway(contentResolver),
        )
    }

    /** Providers treated as a removable drive: only debug builds' fake USB drive, for device tests (H1). */
    val testDriveAuthorities: Set<String>
        get() = if (BuildConfig.DEBUG) setOf("$packageName.testusb") else emptySet()

    /** Opens a picked backup folder (MOVE-2). Blocking I/O. */
    suspend fun openTarget(uri: android.net.Uri, label: String) =
        io.github.tomerar.keepclear.backup.saf.SafTargets.open(
            contentResolver,
            uri,
            label,
            removableAuthorities = testDriveAuthorities,
        )

    /** Test mode (SAFETY-9): on by default in debug/test builds; nothing changes on disk while it is on. */
    val testMode by lazy { TestMode(this) }

    /**
     * The single choke point for every destructive action (CLAUDE.md safety rule 3). Built per plan so the
     * PROTECTED roots include every backup folder known right now (H3, safety rule 4).
     */
    suspend fun executor(onResult: (OpResult) -> Unit = {}): ActionExecutor {
        val guarded = BackupRoots.protectedPaths(moveController.store.jobs(), packageName)
        return ActionExecutor(
            // M12: MediaStore rows are re-checked through MediaStore and go to Android's trash with its consent.
            gateway = actionGateway,
            guard = SafetyGuard(guarded),
            protectedPaths = guarded,
            handler = io.github.tomerar.keepclear.core.storage.media.MediaConsentHandler(
                trashManager,
                io.github.tomerar.keepclear.core.storage.media.AndroidMediaRequests(contentResolver),
            ),
            currentSession = sessionId,
            // Re-read before every operation: turning test mode on mid-plan stops the rest (SAFETY-9).
            isDryRun = { testMode.isOn },
            onResult = { result ->
                TestMode.log(result)
                onResult(result)
            },
        )
    }

    /** MOVE jobs: Room store, the running job, the foreground service (M10). */
    val moveController by lazy { MoveController(this) }

    override fun onCreate() {
        super.onCreate()
        TrashPurgeWorker.schedule(this)
    }
}
