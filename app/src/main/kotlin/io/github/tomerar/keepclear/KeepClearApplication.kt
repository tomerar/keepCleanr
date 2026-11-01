// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.Application
import android.os.storage.StorageManager
import androidx.core.net.toUri
import io.github.tomerar.keepclear.core.database.KeepClearDatabase
import io.github.tomerar.keepclear.core.database.RoomTrashIndex
import io.github.tomerar.keepclear.core.database.ScanIndex
import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.OpResult
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.file.FileGateway
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.core.storage.media.MediaStoreSource
import io.github.tomerar.keepclear.core.storage.saf.GrantedFolders
import io.github.tomerar.keepclear.core.storage.saf.SafGateway
import io.github.tomerar.keepclear.core.storage.saf.SafTreeMount
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

    /**
     * M12b, ONB-6: WhatsApp folders the user granted through the system picker, mounted at their real path while
     * All files access is off. Read once per resume ([refreshGrantedFolders]), not on every file operation.
     */
    @Volatile
    var grantedFolders: Map<String, io.github.tomerar.keepclear.core.storage.StorageGateway> = emptyMap()
        private set

    /** One mount per granted folder, kept so its path cache survives a refresh. */
    private val mountCache = java.util.concurrent.ConcurrentHashMap<String, SafTreeMount>()

    val safGateway by lazy { SafGateway(contentResolver) }

    /**
     * Every granted WhatsApp folder, in any access mode. These stay trash volumes even after All files access is
     * turned on (then the file gateway reaches them), so items trashed earlier are never orphaned (PR #23 review).
     */
    @Volatile
    var grantedFolderRoots: Set<String> = emptySet()
        private set

    /** Re-reads the persisted folder grants: at start (also for the background purge) and on every resume. */
    fun refreshGrantedFolders(allFiles: Boolean = BuildConfig.FLAVOR == "foss" && MediaAccess.hasAllFiles()) {
        val found = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.isWritePermission }
            .mapNotNull { permission ->
                val uri = permission.uri
                if (!android.provider.DocumentsContract.isTreeUri(uri)) return@mapNotNull null
                val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
                if (!GrantedFolders.isWhatsAppFolder(id)) return@mapNotNull null
                val point = GrantedFolders.mountPoint(uri.authority.orEmpty(), id) ?: return@mapNotNull null
                point to mountCache.getOrPut(uri.toString()) {
                    SafTreeMount(safGateway, KPath.Saf(uri.toString(), id), point)
                }
            }
        val split = GrantedFolderSplit.of(found.toMap(), allFiles)
        grantedFolderRoots = split.trashRoots
        grantedFolders = split.mounts
    }

    /** Every file path goes through here: the phone's files, or a granted folder's documents (M12b). */
    val localGateway by lazy {
        io.github.tomerar.keepclear.core.storage.MountedGateway(fileGateway) { grantedFolders }
    }
    val mediaSource by lazy { MediaStoreSource(contentResolver) }

    /** CLS-7 / SET-2: what the user kept or marked as junk. */
    val userChoices by lazy { UserChoices(getSharedPreferences("user-choices", MODE_PRIVATE)) }

    /** Own folders are PROTECTED under this build's real package (test builds end in `.test`). */
    val protectedPaths by lazy { ProtectedPaths(ownPackage = packageName) }

    /** Mounted volume roots (internal storage, SD card, OTG). */
    fun volumeRoots(): List<String> = getSystemService(StorageManager::class.java).storageVolumes
        .mapNotNull { v -> v.directory?.takeIf { v.state == "mounted" }?.absolutePath }

    val trashManager by lazy {
        TrashManager(
            gateway = localGateway,
            index = RoomTrashIndex(database.trashDao()),
            signer = KeystoreSidecarSigner(),
            clock = trashClock,
            protectedPaths = protectedPaths,
            volumeRoots = volumeRoots(),
            // M12b: a granted folder keeps its own trash inside it (documents can't move out of it).
            grantedRoots = { grantedFolderRoots.toList() },
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
            localGateway,
            media = io.github.tomerar.keepclear.core.storage.media.MediaGateway(contentResolver),
        )
    }

    /** Providers treated as a removable drive: only debug builds' fake USB drive, for device tests (H1). */
    val testDriveAuthorities: Set<String>
        get() = if (BuildConfig.DEBUG) setOf("$packageName.testusb") else emptySet()

    /** M15: the network drive the user set up (password sealed with a key-store key). */
    val networkDrives by lazy {
        NetworkDriveStore(getSharedPreferences("network-drive", MODE_PRIVATE), KeystoreSecretBox())
    }

    /**
     * "Forget the network drive" (C9): removes it and its sealed password, and pauses a backup that is running to
     * it right now, so nothing more is sent to that address (PR #25 review).
     */
    suspend fun forgetNetworkDrive() {
        networkDrives.clear()
        val running = moveController.running.value?.let { moveController.store.job(it) }
        if (OpenedTarget.usesNetworkDrive(running)) {
            moveController.pause(io.github.tomerar.keepclear.engine.move.PauseReason.TARGET_DISCONNECTED)
        }
    }

    /** Opens the network drive at [location] (M15). Network I/O; call off the main thread. */
    suspend fun openNetworkDrive(location: io.github.tomerar.keepclear.backup.smb.SmbLocation, label: String) =
        OpenedTarget.of(io.github.tomerar.keepclear.backup.smb.SmbTargets.open(location, label))

    /**
     * Reopens a job's target from its stored reference: a picked tree URI, or the saved network drive. A network
     * reference opens only if it still matches the saved drive; the marker then has to match too (E-MOVE-2).
     */
    suspend fun openTargetRef(ref: String, label: String): OpenedTarget = if (OpenedTarget.isNetwork(ref)) {
        val location = networkDrives.load()
            ?.takeIf { io.github.tomerar.keepclear.backup.smb.SmbTargets.ref(it) == ref }
        if (location == null) OpenedTarget.Unavailable else openNetworkDrive(location, label)
    } else {
        OpenedTarget.of(openTarget(ref.toUri(), label))
    }

    /** Opens a picked backup folder (MOVE-2). Blocking I/O. */
    suspend fun openTarget(uri: android.net.Uri, label: String) =
        io.github.tomerar.keepclear.backup.saf.SafTargets.open(
            contentResolver,
            uri,
            label,
            removableAuthorities = testDriveAuthorities,
        )

    /** M12: builds Android's trash/delete requests for MediaStore rows, one dialog at a time. */
    val mediaRequests by lazy {
        io.github.tomerar.keepclear.core.storage.media.AndroidMediaRequests(contentResolver)
    }

    /** Test mode (SAFETY-9): on by default in debug/test builds; nothing changes on disk while it is on. */
    val testMode by lazy { TestMode(this) }

    /**
     * The single choke point for every destructive action (CLAUDE.md safety rule 3). Built per plan so the
     * PROTECTED roots include every backup folder known right now (H3, safety rule 4).
     */
    suspend fun executor(onResult: (OpResult) -> Unit = {}): ActionExecutor {
        // SAFETY-3: what the user kept is PROTECTED at the choke point too, not only in the lists.
        val guarded = BackupRoots.protectedPaths(moveController.store.jobs(), packageName, userChoices.state.value.kept)
        return ActionExecutor(
            // M12: MediaStore rows are re-checked through MediaStore and go to Android's trash with its consent.
            gateway = actionGateway,
            // M12b: a granted folder's own trash may be emptied (only directly inside that folder).
            guard = SafetyGuard(guarded, grantedTrashRoots = grantedFolderRoots),
            protectedPaths = guarded,
            handler = io.github.tomerar.keepclear.core.storage.media.MediaConsentHandler(trashManager, mediaRequests),
            currentSession = sessionId,
            // Re-read before every operation: turning test mode on mid-plan stops the rest (SAFETY-9).
            isDryRun = { testMode.isOn },
            onResult = { result ->
                TestMode.log(result)
                onResult(result)
            },
            // CLS-7: a file kept while the plan runs is skipped too (PR #27 review).
            keptNow = { path -> keptNow(path) },
        )
    }

    private var keptCache: Pair<Set<String>, io.github.tomerar.keepclear.core.safety.ProtectedPaths>? = null

    private fun keptNow(path: String): Boolean {
        val kept = userChoices.state.value.kept
        if (kept.isEmpty()) return false
        val cached = keptCache?.takeIf { it.first === kept }
            ?: (kept to BackupRoots.protectedPaths(emptyList(), packageName, kept)).also { keptCache = it }
        return cached.second.isProtected(path)
    }

    /** MOVE jobs: Room store, the running job, the foreground service (M10). */
    val moveController by lazy { MoveController(this) }

    override fun onCreate() {
        super.onCreate()
        // Before any trash work, also in a process WorkManager starts for the daily purge (PR #23 review).
        runCatching { refreshGrantedFolders() }
        TrashPurgeWorker.schedule(this)
    }
}

/**
 * M12b: every granted folder is a trash volume in any access mode, but it is mounted (routed through SAF) only
 * without All files access; with it, the phone's own file gateway reaches the folder directly (PR #23 review).
 */
data class GrantedFolderSplit<T>(val trashRoots: Set<String>, val mounts: Map<String, T>) {
    companion object {
        fun <T> of(found: Map<String, T>, allFiles: Boolean) =
            GrantedFolderSplit(found.keys, if (allFiles) emptyMap() else found)
    }
}
