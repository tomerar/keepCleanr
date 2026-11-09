// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import io.github.tomerar.keepclear.engine.gallery.GalleryAuthorization
import io.github.tomerar.keepclear.engine.gallery.GalleryScope
import io.github.tomerar.keepclear.engine.gallery.GalleryScopeSource
import io.github.tomerar.keepclear.engine.gallery.PermissionEpoch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** C211: the gallery's scope from the platform's current permission state, for both flavours. */
object PlatformGalleryScope {
    /**
     * [allFiles] is the foss flavour's all-files access (the play flavour always passes false). Only image access
     * counts here: video and audio permissions, which the storage scan's "full" label needs, do not matter.
     */
    fun of(sdk: Int, granted: (String) -> Boolean, allFiles: Boolean): GalleryScope = when {
        allFiles -> GalleryScope.ALL_FILES
        sdk >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> GalleryScope.FULL_IMAGES
        sdk >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> GalleryScope.SELECTED
        sdk < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> GalleryScope.FULL_IMAGES
        else -> GalleryScope.NONE
    }

    fun current(context: Context, allFiles: Boolean): GalleryScope = of(
        Build.VERSION.SDK_INT,
        { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
        allFiles,
    )
}

/** Where the last seen scope and its epoch are remembered (only to notice a change; never read authority). */
interface EpochLedger {
    fun read(): GalleryAuthorization?

    fun write(authorization: GalleryAuthorization)
}

/** A private, tiny preferences file holding only the last scope name and epoch. Garbage reads as "none seen". */
class PrefsEpochLedger(private val prefs: SharedPreferences) : EpochLedger {
    override fun read(): GalleryAuthorization? = runCatching {
        val scope = GalleryScope.valueOf(prefs.getString(SCOPE, null) ?: return null)
        GalleryAuthorization(scope, prefs.getLong(EPOCH, 0))
    }.getOrNull()

    override fun write(authorization: GalleryAuthorization) {
        prefs.edit().putString(SCOPE, authorization.scope.name).putLong(EPOCH, authorization.epoch).commit()
    }

    companion object {
        const val FILE = "gallery-scope"
        private const val SCOPE = "scope"
        private const val EPOCH = "epoch"
    }
}

/**
 * Live scope plus a permission epoch that moves forward whenever the scope narrows or widens (GAL-12, E-G02).
 * Every [current] call re-reads [platform]; the ledger only remembers what was seen last. Runs off the main thread.
 */
class LiveGalleryScope(
    private val platform: () -> GalleryScope,
    private val ledger: EpochLedger,
) : GalleryScopeSource {
    private val lock = Any()

    override suspend fun current(): GalleryAuthorization = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val last = ledger.read()
            val next = PermissionEpoch.advance(last, platform())
            // A failed write is harmless: the next call sees the same old value and derives the same new epoch.
            if (next != last) runCatching { ledger.write(next) }
            next
        }
    }

    companion object {
        /** [allFiles] is read on every call (foss: `MediaAccess.hasAllFiles()`; play: always false). */
        fun of(context: Context, allFiles: () -> Boolean): LiveGalleryScope = LiveGalleryScope(
            { PlatformGalleryScope.current(context, allFiles()) },
            PrefsEpochLedger(context.getSharedPreferences(PrefsEpochLedger.FILE, Context.MODE_PRIVATE)),
        )
    }
}
