// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import io.github.tomerar.keepclear.engine.move.AttachGeneration
import io.github.tomerar.keepclear.engine.move.AttachTracker

/**
 * D4: the real unmount → mount events of each drive, per volume UUID, from StorageManager's volume callback
 * (registered once per process in [KeepClearApplication.onCreate]). "Check backup" may say a drive was re-attached
 * only when these events were seen for the drive's own volume; a target that merely failed to open once proves
 * nothing. The engine records [generation] per file at the first check and compares it at the second.
 */
class VolumeEvents(epoch: String) {
    private val tracker = AttachTracker(epoch)

    /** The drive's generation now; null when there is no volume to watch (a network place, a test provider). */
    fun generation(volumeUuid: String?): AttachGeneration? = volumeUuid?.takeIf { it.isNotBlank() }?.let {
        tracker.generation(it)
    }

    /** One state change, as Android reported it ([StorageVolume.getState]). */
    fun onState(volumeUuid: String, state: String) =
        tracker.onState(volumeUuid, state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY)

    fun register(context: Context) {
        val sm = context.getSystemService(StorageManager::class.java) ?: return
        sm.registerStorageVolumeCallback(
            context.mainExecutor,
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    val uuid = volume.uuid ?: return // the phone's own storage has none and never unmounts
                    onState(uuid, volume.state)
                }
            },
        )
    }
}
