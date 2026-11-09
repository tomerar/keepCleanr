// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.engine.gallery.GalleryAuthorization
import io.github.tomerar.keepclear.engine.gallery.GalleryScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveGalleryScopeTest {
    private val images = Manifest.permission.READ_MEDIA_IMAGES
    private val selected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    private val legacy = Manifest.permission.READ_EXTERNAL_STORAGE

    @Test
    fun `platform scope from the permissions each API level really uses`() {
        fun scope(sdk: Int, vararg granted: String, allFiles: Boolean = false) =
            PlatformGalleryScope.of(sdk, { it in granted }, allFiles)
        assertEquals(GalleryScope.NONE, scope(36))
        assertEquals(GalleryScope.SELECTED, scope(34, selected))
        assertEquals(GalleryScope.FULL_IMAGES, scope(34, images, selected))
        assertEquals(GalleryScope.FULL_IMAGES, scope(33, images))
        // Video and audio do not matter to the gallery; selected access does not exist before Android 14.
        assertEquals(GalleryScope.NONE, scope(33, selected, Manifest.permission.READ_MEDIA_VIDEO))
        // READ_EXTERNAL_STORAGE has no effect from Android 13 on.
        assertEquals(GalleryScope.NONE, scope(33, legacy))
        assertEquals(GalleryScope.FULL_IMAGES, scope(30, legacy))
        assertEquals(GalleryScope.NONE, scope(32, images))
        assertEquals(GalleryScope.ALL_FILES, scope(30, allFiles = true))
        assertEquals(GalleryScope.ALL_FILES, scope(36, selected, allFiles = true))
    }

    @Test
    fun `the epoch is persisted privately and moves only when the scope changes`(): Unit = runBlocking {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(PrefsEpochLedger.FILE, Context.MODE_PRIVATE)
        val ledger = PrefsEpochLedger(prefs)
        assertNull(ledger.read())
        var platform = GalleryScope.FULL_IMAGES
        val live = LiveGalleryScope({ platform }, ledger)
        assertEquals(GalleryAuthorization(GalleryScope.FULL_IMAGES, 1), live.current())
        assertEquals(GalleryAuthorization(GalleryScope.FULL_IMAGES, 1), live.current())
        platform = GalleryScope.SELECTED
        assertEquals(GalleryAuthorization(GalleryScope.SELECTED, 2), live.current())
        // A new process reads the same ledger: no reset to epoch 1.
        assertEquals(
            GalleryAuthorization(GalleryScope.SELECTED, 2),
            LiveGalleryScope({
                platform
            }, PrefsEpochLedger(prefs)).current(),
        )
        platform = GalleryScope.FULL_IMAGES
        assertEquals(GalleryAuthorization(GalleryScope.FULL_IMAGES, 3), live.current())
        assertEquals(setOf("scope", "epoch"), prefs.all.keys)
        // A garbled ledger reads as "nothing seen"; the cache's newer epoch is then cleared by the coordinator.
        prefs.edit().putString("scope", "EVERYTHING").commit()
        assertNull(ledger.read())
    }
}
