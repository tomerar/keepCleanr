// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Owner brief H1 item 3: a removable volume (a USB drive or a memory card) as Android reports it. */
data class RemovableDrive(
    /** The volume's UUID; null when Android gives none. */
    val uuid: String?,
    /** Android's own name for it ("SanDisk USB drive"). */
    val label: String,
    val mounted: Boolean,
)

/** Where the removable volumes come from: Android's StorageManager in the app, a fake in tests. */
interface VolumeSource {
    /** The removable volumes Android knows now, mounted or not. */
    fun removable(): List<RemovableDrive>

    /** Calls [onChange] whenever a volume is attached, mounted, unmounted or removed, until closed. */
    fun watch(onChange: () -> Unit): AutoCloseable

    /** The folder picker's starting place at [drive] (Android's own intent for that volume), or null. */
    fun pickerStart(drive: RemovableDrive): Uri? = null
}

/**
 * Owner brief H1 items 3 and 6: the live state of the removable drives, app-wide. The Backup tab shows "Found ✓"
 * the moment a drive is plugged in, and the second check's re-plug step follows the unplug and the plug-back.
 */
class DriveWatcher(private val source: VolumeSource) {
    private val _drives = MutableStateFlow<List<RemovableDrive>>(emptyList())
    val drives: StateFlow<List<RemovableDrive>> = _drives
    private var handle: AutoCloseable? = null

    /** The first mounted removable drive, or null. */
    val mounted: RemovableDrive? get() = _drives.value.firstOrNull { it.mounted }

    @Synchronized
    fun start() {
        if (handle != null) return
        handle = runCatching { source.watch { refresh() } }.getOrNull()
        refresh()
    }

    @Synchronized
    fun stop() {
        runCatching { handle?.close() }
        handle = null
    }

    fun refresh() {
        _drives.value = runCatching { source.removable() }.getOrDefault(emptyList())
    }

    /**
     * Whether the volume [uuid] is mounted now: true or false when the source knows the drive (a volume missing from
     * the list was removed, so false), null when [uuid] is null (a network place, a test provider without a volume).
     */
    fun isMounted(uuid: String?): Boolean? {
        if (uuid.isNullOrBlank()) return null
        return _drives.value.any { it.uuid.equals(uuid, ignoreCase = true) && it.mounted }
    }

    fun pickerStart(drive: RemovableDrive): Uri? = runCatching { source.pickerStart(drive) }.getOrNull()
}

/**
 * Android's removable volumes (API 30+): [StorageManager.getStorageVolumes] for the list, the volume callback plus the
 * media broadcasts for changes (some makers only send one of them), and [StorageVolume.createOpenDocumentTreeIntent]
 * for the picker's starting place.
 */
class AndroidVolumeSource(private val context: Context) : VolumeSource {
    private val sm get() = context.getSystemService(StorageManager::class.java)

    private fun volumes(): List<StorageVolume> = sm?.storageVolumes.orEmpty().filter { it.isRemovable }

    override fun removable(): List<RemovableDrive> = volumes().map { v ->
        RemovableDrive(
            uuid = v.uuid,
            label = runCatching { v.getDescription(context) }.getOrNull().orEmpty(),
            mounted = v.state == Environment.MEDIA_MOUNTED || v.state == Environment.MEDIA_MOUNTED_READ_ONLY,
        )
    }

    override fun watch(onChange: () -> Unit): AutoCloseable {
        val callback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) = onChange()
        }
        sm?.registerStorageVolumeCallback(context.mainExecutor, callback)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onChange()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        // Protected system broadcasts; not exported to other apps.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return AutoCloseable {
            runCatching { sm?.unregisterStorageVolumeCallback(callback) }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    override fun pickerStart(drive: RemovableDrive): Uri? {
        val volume = volumes().firstOrNull { it.uuid != null && it.uuid.equals(drive.uuid, ignoreCase = true) }
            ?: return null
        val intent = volume.createOpenDocumentTreeIntent()
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI)
    }
}

/**
 * Owner brief H1 item 6: the second check's re-plug step, driven by the volume source. When the source knows the
 * drive ([mounted] not null) its unmount and mount events decide; only for a drive without a volume (a test provider)
 * does KeepClear look by opening the drive ([present]). Coming back also needs the drive to open again.
 */
class ReplugWaiter(
    private val mounted: () -> Boolean?,
    private val present: suspend () -> Boolean,
    private val pollMillis: Long = 250,
) {
    /** Returns once the drive is gone (unmounted or removed). */
    suspend fun awaitGone() {
        while (true) {
            val known = mounted()
            if (known == false || (known == null && !present())) return
            kotlinx.coroutines.delay(pollMillis)
        }
    }

    /** Returns once the drive is mounted again and opens. */
    suspend fun awaitBack() {
        while (true) {
            if (mounted() != false && present()) return
            kotlinx.coroutines.delay(pollMillis)
        }
    }
}
