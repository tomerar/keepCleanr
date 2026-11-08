// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.GroupKey
import io.github.tomerar.keepclear.engine.classify.RiskChip
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Source
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreObservationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private var created: Uri? = null

    @After
    fun cleanUp() {
        created?.let { uri ->
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun readsCurrentMetadataAndRejectsTrashedRow(): Unit = runBlocking {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "kc_gallery_${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/KeepClearTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri =
            requireNotNull(
                resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values),
            )
        created = uri
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(ByteArray(64)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val projection =
            arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        val scan = requireNotNull(resolver.query(uri, projection, null, null, null)).use { cursor ->
            check(cursor.moveToFirst())
            ScanItem(
                ref = KPath.Media(MediaStore.VOLUME_EXTERNAL_PRIMARY, requireNotNull(uri.lastPathSegment).toLong()),
                path = cursor.getString(0),
                size = cursor.getLong(1),
                mtimeMillis = cursor.getLong(2) * 1_000,
                mime = "image/jpeg",
                source = Source.MEDIA_STORE,
            )
        }
        val item = ClassifiedItem(scan, Category.REVIEW, "review", GroupKey("review"), RiskChip.UNKNOWN)
        val reader = MediaStoreObservation(context)
        val run = GalleryRun("run", "scan", 1)
        assertNotNull(reader.observe(item, run, ProtectedPaths()) { _, _ -> true })
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
        assertNull(reader.observe(item, run, ProtectedPaths()) { _, _ -> true })
    }
}
