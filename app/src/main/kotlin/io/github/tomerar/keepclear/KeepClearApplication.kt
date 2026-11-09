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
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manual dependency graph. Hilt is deferred until the graph is big enough to need it
 * (tasks.md M3 note); everything here is created lazily on first use.
 */
class KeepClearApplication : Application() {
    /** Identifies this process for plan confirmation (SAFETY-1, MIG-5). */
    val sessionId: String = UUID.randomUUID().toString()

    /** Tests only: stand-ins set before first use (Robolectric has no key store, tests use their own folders). */
    internal var databaseFactory: (android.content.Context) -> KeepClearDatabase = { KeepClearDatabase.build(it) }
    internal var sidecarSigner: () -> io.github.tomerar.keepclear.engine.trash.SidecarSigner = ::KeystoreSidecarSigner
    internal var gatewayWrapper: (io.github.tomerar.keepclear.core.storage.StorageGateway) ->
    io.github.tomerar.keepclear.core.storage.StorageGateway = { it }
    internal var volumeRootsOverride: List<String>? = null
    internal var targetOpener: (suspend (ref: String, label: String) -> OpenedTarget)? = null

    val database by lazy { databaseFactory(this) }
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
        // MSG-3 (PR #30 review): readable cloned-app storage is scanned, so it must also hold its own trash.
        clonedRoots = ClonedApps.probe(
            allFiles,
            exists = { java.io.File(it).exists() },
            canList = { java.io.File(it).list() != null },
        ).readable.toSet()
    }

    /** MSG-3: readable cloned-app storage roots; each is a trash volume like the main one. */
    @Volatile
    var clonedRoots: Set<String> = emptySet()
        private set

    /** Every file path goes through here: the phone's files, or a granted folder's documents (M12b). */
    val localGateway by lazy {
        gatewayWrapper(io.github.tomerar.keepclear.core.storage.MountedGateway(fileGateway) { grantedFolders })
    }
    val mediaSource by lazy { MediaStoreSource(contentResolver) }

    /** SET-3: the opt-in low-space reminder. */
    val reminderPrefs by lazy {
        MainThreadPolicy.settings { ReminderPrefs(getSharedPreferences("space-reminder", MODE_PRIVATE)) }
    }

    /** CLS-7 / SET-2: what the user kept or marked as junk. */
    val userChoices by lazy {
        MainThreadPolicy.settings { UserChoices(getSharedPreferences("user-choices", MODE_PRIVATE)) }
    }

    /** Own folders are PROTECTED under this build's real package (test builds end in `.test`). */
    val protectedPaths by lazy { ProtectedPaths(ownPackage = packageName) }

    /** Mounted volume roots (internal storage, SD card, OTG). */
    fun volumeRoots(): List<String> = volumeRootsOverride ?: getSystemService(StorageManager::class.java).storageVolumes
        .mapNotNull { v -> v.directory?.takeIf { v.state == "mounted" }?.absolutePath }

    val trashManager by lazy {
        TrashManager(
            gateway = localGateway,
            index = RoomTrashIndex(database.trashDao()),
            signer = sidecarSigner(),
            clock = trashClock,
            protectedPaths = protectedPaths,
            volumeRoots = volumeRoots(),
            // M12b: a granted folder keeps its own trash inside it (documents can't move out of it).
            grantedRoots = { ClonedApps.trashVolumes(grantedFolderRoots, clonedRoots) },
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

    val trashClock by lazy { MainThreadPolicy.settings { SystemTrashClock(contentResolver, this) } }

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
        MainThreadPolicy.settings {
            NetworkDriveStore(getSharedPreferences("network-drive", MODE_PRIVATE), KeystoreSecretBox())
        }
    }

    /**
     * "Forget the network drive" (C9): removes it and its sealed password, and pauses a backup that is running to
     * it right now, so nothing more is sent to that address (PR #25 review).
     */
    suspend fun forgetNetworkDrive() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { networkDrives.clear() }
        val running = moveController.running.value?.let { moveController.store.job(it) }
        if (OpenedTarget.usesNetworkDrive(running)) {
            moveController.pause(io.github.tomerar.keepclear.engine.move.PauseReason.TARGET_DISCONNECTED)
        }
    }

    /** Opens the network drive at [location] (M15). Network I/O; call off the main thread. */
    suspend fun openNetworkDrive(location: io.github.tomerar.keepclear.backup.smb.SmbLocation, label: String) =
        OpenedTarget.of(io.github.tomerar.keepclear.backup.smb.SmbTargets.open(location, label))

    /** Seals stored passwords; tests on Robolectric (which has no Android key store) put a stand-in here first. */
    internal var secretBox: (alias: String) -> SecretBox = ::KeystoreSecretBox

    /**
     * D3: which servers the network places may reach. Always [HostRule.NetworkOnly] in the app (never this phone);
     * only tests, which talk to servers on their own machine, set the test-only rule here before first use.
     */
    internal var nextcloudHosts: io.github.tomerar.keepclear.backup.net.HostRule =
        io.github.tomerar.keepclear.backup.net.HostRule.NetworkOnly

    /** M17b (Advanced): the user's Nextcloud (app password sealed with its own key-store key). */
    val nextcloud by lazy {
        MainThreadPolicy.settings {
            NextcloudStore(
                getSharedPreferences("nextcloud", MODE_PRIVATE),
                secretBox("keepclear-nextcloud"),
                nextcloudHosts,
            )
        }
    }

    /**
     * "Forget your Nextcloud" (C27, like C9; D6): pauses a backup that is running to it right now, then **revokes the
     * app password on the server** (so a copy of it stops working), then removes the account and its sealed app
     * password from the phone. When the server can't be reached the account is removed from the phone anyway (C40)
     * and the answer says the password may still work there, so the user can remove it in Nextcloud.
     * Returns null when no Nextcloud was saved.
     */
    suspend fun forgetNextcloud(
        revoke: suspend (io.github.tomerar.keepclear.backup.webdav.NextcloudAccount) ->
        io.github.tomerar.keepclear.backup.webdav.NextcloudRevoke = { account ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { account.revoke(timeoutSeconds = 15) }
        },
    ): io.github.tomerar.keepclear.backup.webdav.NextcloudRevoke? {
        val running = moveController.running.value?.let { moveController.store.job(it) }
        if (OpenedTarget.usesNextcloud(running)) {
            moveController.pause(io.github.tomerar.keepclear.engine.move.PauseReason.TARGET_DISCONNECTED)
        }
        val clear = suspend { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { nextcloud.clear() } }
        if (nextcloud.host() == null) {
            clear()
            return null
        }
        var result = io.github.tomerar.keepclear.backup.webdav.NextcloudRevoke.NOT_REVOKED
        try {
            val account = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { nextcloud.load() }.getOrNull()
            }
            // A saved account whose app password can't be opened any more can't be revoked from here either.
            if (account != null) result = revoke(account)
        } finally {
            // PR #44 review: "Forget" always forgets on the phone, even when the caller is cancelled (or anything
            // throws) while loading the account or waiting for the server.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                nextcloud.clear()
            }
            if (result == io.github.tomerar.keepclear.backup.webdav.NextcloudRevoke.NOT_REVOKED) {
                // No host, user or password in the log.
                android.util.Log.w(
                    "KeepClear-Nextcloud",
                    "forgotten on the phone; the server did not confirm the revoke",
                )
            }
        }
        return result
    }

    /** D6 / PR #44 review: whether "Forget your Nextcloud" is running, and what it achieved (Settings shows it). */
    data class NextcloudForgetState(
        val forgetting: Boolean = false,
        val result: io.github.tomerar.keepclear.backup.webdav.NextcloudRevoke? = null,
    )

    private val _nextcloudForget = kotlinx.coroutines.flow.MutableStateFlow(NextcloudForgetState())
    val nextcloudForget: kotlinx.coroutines.flow.StateFlow<NextcloudForgetState> = _nextcloudForget

    /**
     * Runs for the whole process, not for one screen: an operation started here (forgetting the Nextcloud) isn't
     * cancelled when the activity is recreated (rotation, language change). Tests may replace it.
     */
    internal var appScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
        )

    /** "Forget your Nextcloud" from Settings: one at a time, in [appScope]; the outcome lands in [nextcloudForget]. */
    fun forgetNextcloudInBackground(
        revoke: suspend (io.github.tomerar.keepclear.backup.webdav.NextcloudAccount) ->
        io.github.tomerar.keepclear.backup.webdav.NextcloudRevoke = { account ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { account.revoke(timeoutSeconds = 15) }
        },
    ): kotlinx.coroutines.Job? {
        val current = _nextcloudForget.value
        if (current.forgetting || !_nextcloudForget.compareAndSet(current, current.copy(forgetting = true))) return null
        return appScope.launch {
            var result: io.github.tomerar.keepclear.backup.webdav.NextcloudRevoke? = null
            try {
                result = forgetNextcloud(revoke)
            } finally {
                _nextcloudForget.value = NextcloudForgetState(false, result ?: _nextcloudForget.value.result)
            }
        }
    }

    /** Opens the Nextcloud backup place of [account] (M17b). Network I/O; call off the main thread. */
    suspend fun openNextcloud(account: io.github.tomerar.keepclear.backup.webdav.NextcloudAccount, label: String) =
        OpenedTarget.of(
            io.github.tomerar.keepclear.backup.webdav.WebDavTargets.open(NextcloudStore.location(account), label),
        )

    /**
     * Reopens a job's target from its stored reference: a picked tree URI, the saved network drive or the saved
     * Nextcloud. A network reference opens only if it still matches the saved place; the marker then has to match
     * too (E-MOVE-2).
     */
    suspend fun openTargetRef(ref: String, label: String): OpenedTarget = targetOpener?.invoke(ref, label) ?: when {
        OpenedTarget.isNextcloud(ref) -> {
            val account = nextcloud.load()?.takeIf { NextcloudStore.ref(it) == ref }
            if (account == null) OpenedTarget.Unavailable else openNextcloud(account, label)
        }

        OpenedTarget.isSmb(ref) -> {
            val location = networkDrives.load()
                ?.takeIf { io.github.tomerar.keepclear.backup.smb.SmbTargets.ref(it) == ref }
            if (location == null) OpenedTarget.Unavailable else openNetworkDrive(location, label)
        }

        else -> {
            OpenedTarget.of(openTarget(ref.toUri(), label))
        }
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

    /** Review of PR #61: test mode's export rows while Android's "create document" screen is open. */
    val pendingExport = PendingExport()

    /** Test mode (SAFETY-9): on by default in debug/test builds; nothing changes on disk while it is on. */
    val testMode by lazy { MainThreadPolicy.settings { TestMode(this) } }

    /**
     * The single choke point for every destructive action (CLAUDE.md safety rule 3). Built per plan so the
     * PROTECTED roots include every backup folder known right now (H3, safety rule 4).
     */
    suspend fun executor(onResult: (OpResult) -> Unit = {}): ActionExecutor {
        // E-G20 (C221): a file action takes priority over a running gallery comparison.
        pauseGalleryForFileAction()
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
            // Owner decision P1: after a verified backup the originals are deleted for good unless the user keeps
            // them in the trash too (Settings); asked right before each original.
            freeOriginalsForGood = { !freeSettings.keepInTrash },
        )
    }

    /** PR H2b: saved one-by-one sessions ("Continue where you left off"); read and written off the main thread. */
    val swipeStore by lazy {
        SwipeSessionStore(
            SwipeSessionStore.SharedPreferencesBackend(
                getSharedPreferences(SwipeSessionStore.PREFS, MODE_PRIVATE),
            ),
        )
    }

    /** PR H2b: "Swap the swipe directions" and the one-time direction hint. */
    val swipeSettings by lazy { MainThreadPolicy.settings { SwipeSettings(this) } }

    /** Owner decision P1: "Keep the original in the 30-day trash too" (off by default). */
    val freeSettings by lazy { MainThreadPolicy.settings { FreeSettings(this) } }

    /**
     * Owner decision P3: the battery right now (null when Android doesn't say). Tests replace it with a fake source.
     */
    @Volatile internal var batterySource: () -> io.github.tomerar.keepclear.engine.move.BatteryLevel? = {
        runCatching {
            val bm = getSystemService(android.os.BatteryManager::class.java)
            val percent = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            percent.takeIf { it in 0..100 }
                ?.let { io.github.tomerar.keepclear.engine.move.BatteryLevel(it, bm.isCharging) }
        }.getOrNull()
    }

    private var keptCache: Pair<Set<String>, io.github.tomerar.keepclear.core.safety.ProtectedPaths>? = null

    private fun keptNow(path: String): Boolean {
        val kept = userChoices.state.value.kept
        if (kept.isEmpty()) return false
        val cached = keptCache?.takeIf { it.first === kept }
            ?: (kept to BackupRoots.protectedPaths(emptyList(), packageName, kept)).also { keptCache = it }
        return cached.second.isProtected(path)
    }

    /** Owner brief H3 item 4: "What KeepClear did" (a small versioned file in private storage, C163). */
    val history by lazy {
        ActivityHistory(historyBackend ?: AtomicFileBackend(java.io.File(filesDir, "history/history-v1.txt")))
    }

    /** Owner brief H3 item 3: the trace of a Java crash, written as KeepClear crashes (redacted), read next launch. */
    val crashStore by lazy { CrashTraceStore(java.io.File(filesDir, "crash/last-trace.txt")) }

    /** Owner brief H3 item 3: the unexpected exit to offer once on Home (null: none, or already offered). */
    val crashNotice = kotlinx.coroutines.flow.MutableStateFlow<ExitRecord?>(null)

    /** Owner brief H3 item 5: the "What's new" note to show once after a bigger update. */
    val whatsNew = kotlinx.coroutines.flow.MutableStateFlow<WhatsNewEntry?>(null)

    /** Tests only: an in-memory history file. */
    internal var historyBackend: ActivityHistory.Backend? = null

    /** Records [events] off the main thread (E1); a failure to write is never fatal and never logs a path. */
    fun recordHistory(events: List<HistoryEvent>) {
        if (events.isEmpty()) return
        moveController.scope.launch {
            try {
                history.record(events)
            } catch (e: java.io.IOException) {
                android.util.Log.w("KeepClear", "history not written: ${e.javaClass.simpleName}")
            }
        }
    }

    /** E13: full hashes of files seen in earlier classifications (memory only), so a rescan doesn't read them again. */
    val hashCache by lazy { MemoryHashCache() }

    /**
     * G4a (C220): the gallery's live scope (foss: All files access counts; play: never). Re-read on every check;
     * its tiny preferences file only notices a change (C211).
     */
    val galleryScope by lazy {
        MainThreadPolicy.settings {
            io.github.tomerar.keepclear.core.gallery.LiveGalleryScope.of(this) {
                BuildConfig.FLAVOR == "foss" && MediaAccess.hasAllFiles()
            }
        }
    }

    /** Tests only: a coordinator over fakes, set before first use. */
    internal var galleryFactory: (() -> io.github.tomerar.keepclear.core.gallery.GalleryCoordinator)? = null

    private val galleryLazy = lazy {
        galleryFactory?.invoke() ?: run {
            val reads = io.github.tomerar.keepclear.core.gallery.MediaStoreGalleryReads.of(this)
            io.github.tomerar.keepclear.core.gallery.GalleryCoordinator(
                store = io.github.tomerar.keepclear.core.database.RoomGalleryFeatureStore(
                    database.galleryDao(),
                    freeBytes = { phoneFreeBytes(this) },
                ),
                observer = reads.observer,
                reader = reads.reader,
                scope = galleryScope,
            )
        }
    }

    /**
     * G4a (C220): the user-started, read-only gallery comparison. Created on first use only: until the user opens
     * "Find similar photos" nothing of it exists (no database read, no scope check, no coordinator).
     */
    val gallery: io.github.tomerar.keepclear.core.gallery.GalleryCoordinator by galleryLazy

    /**
     * E-G20 (C221): every file action (clean, trash, restore, free after a backup, backup copy, restore from a
     * backup) takes priority; a running gallery comparison pauses at its next item or read and can be continued by
     * the user. Never creates the coordinator.
     */
    fun pauseGalleryForFileAction() {
        if (galleryLazy.isInitialized()) gallery.pauseForFileAction()
    }

    /** E-G20 (C237): Home's clean, trash, restore or empty is running (set from its state). */
    @Volatile internal var homeFileAction = false

    /**
     * E-G20 (C237): a file action is running now: Home's clean or trash work, a backup copy, or a free, check or
     * restore task. A gallery comparison can't start or continue meanwhile (the pause hook stops one already
     * running); the user taps Continue once it is over.
     */
    fun fileActionActive(): Boolean =
        homeFileAction || moveController.running.value != null || moveController.task.value != null

    /**
     * GAL-1 (C236): called when the app leaves the foreground. A running comparison stops (the honest partial result
     * stays, Continue is explicit); an idle, finished or paused one is left as it is. Never creates the coordinator.
     */
    fun stopGalleryInBackground() {
        if (galleryLazy.isInitialized() &&
            gallery.progress.value.state == io.github.tomerar.keepclear.core.gallery.GalleryRunState.RUNNING
        ) {
            gallery.stop()
        }
    }

    /** MOVE jobs: Room store, the running job, the foreground service (M10). */
    val moveController by lazy { MoveController(this) }

    /** Owner brief H1 items 3 and 6: tests replace the volume source before [drives] is first used. */
    @Volatile internal var volumeSource: VolumeSource? = null

    /** Owner brief H1 items 3 and 6: the removable drives, live (watching starts on first use). */
    val drives by lazy { DriveWatcher(volumeSource ?: AndroidVolumeSource(this)).also { it.start() } }

    /** Owner brief H1 item 11: the opt-in "check the backup again" reminder. */
    val checkReminderPrefs by lazy {
        MainThreadPolicy.settings { CheckReminderPrefs(getSharedPreferences("check-reminder", MODE_PRIVATE)) }
    }

    /** D4: drives' unmount → mount events in this process (the proof "Check backup" needs). */
    val volumeEvents = VolumeEvents(epoch = sessionId)

    // Owner brief H4 item 7: on Android 11–12 the in-app language is KeepClear's own (Android 13+ applies it itself).
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    // Owner brief H4 item 4: the channels' names follow the language (Android 13+ per-app or the phone's).
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { NotificationChannels.registerAll(this) }
    }

    override fun onCreate() {
        super.onCreate()
        // Owner brief H4 item 4: every channel exists from the start, named in the app's language.
        runCatching { NotificationChannels.registerAll(this) }
        // E1: in test builds a disk read or write on the main thread crashes at once (NFR-1).
        if (BuildConfig.DEBUG) MainThreadPolicy.enable()
        // Owner brief H3 item 3: a Java crash's trace is kept (redacted, private storage) for the next launch's card.
        // No crash SDK and nothing sent: the user decides whether to share it.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // The process is ending; this small private file is written even on the main thread (StrictMode, E1).
            runCatching { MainThreadPolicy.settings { crashStore.save(System.currentTimeMillis(), error) } }
            previous?.uncaughtException(thread, error)
        }
        // D4: watch from the start, so an unplug during "Check backup" is never missed. Never fatal.
        runCatching { volumeEvents.register(this) }
        // E1: off the main thread (it checks folders). The purge worker refreshes the grants itself before any
        // trash work (PR #23 review), and Home refreshes them on every resume.
        moveController.scope.launch {
            runCatching { refreshGrantedFolders() }
            TrashPurgeWorker.schedule(this@KeepClearApplication)
            // Owner brief H3 items 3 and 5: each offered once (marked as offered right away).
            runCatching {
                val crashPrefs = getSharedPreferences(CrashCheck.PREFS, MODE_PRIVATE)
                crashNotice.value =
                    CrashCheck.take(AndroidExitInfoSource(this@KeepClearApplication, crashStore), crashPrefs)
            }
            runCatching {
                val code = packageManager.getPackageInfo(packageName, 0).longVersionCode
                whatsNew.value = WhatsNew.take(getSharedPreferences(WhatsNew.PREFS, MODE_PRIVATE), code)
            }
            runCatching { history.load() }
        }
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
