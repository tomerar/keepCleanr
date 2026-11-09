// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.content.ContentValues
import android.graphics.Bitmap
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
import io.github.tomerar.keepclear.engine.gallery.FeatureVersion
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Source
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

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
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png))
        bitmap.recycle()
        val (uri, item) = insert(png.toByteArray(), "png", "image/png")
        val reader = MediaStoreObservation(context)
        val guarded = GuardedMediaStillRead(resolver, reader)
        val run = GalleryRun("run", "scan", 1)
        assertNotNull(reader.observe(item, run, ProtectedPaths()) { _, _ -> true })
        assertTrue(guarded.read(item, run, FeatureVersion.V1, ProtectedPaths()) { _, _ -> true } is GuardedStill.Read)
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
        assertNull(reader.observe(item, run, ProtectedPaths()) { _, _ -> true })
        assertEquals(
            GuardedStill.Unavailable,
            guarded.read(item, run, FeatureVersion.V1, ProtectedPaths()) { _, _ -> true },
        )
    }

    @Test
    fun readsRotatedJpegThroughMediaStore(): Unit = runBlocking {
        val (_, item) = insert(JpegFixtures.oriented(6), "jpg", "image/jpeg")
        val guarded = GuardedMediaStillRead(resolver, MediaStoreObservation(context))
        val result = guarded.read(item, GalleryRun("run", "scan", 1), FeatureVersion.V1, ProtectedPaths()) { _, _ ->
            true
        }
        assertTrue("$result", result is GuardedStill.Read)
        assertEquals(2.0, (result as GuardedStill.Read).record.facts.aspect, 0.0)
    }

    private fun insert(bytes: ByteArray, extension: String, mime: String): Pair<Uri, ClassifiedItem> {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "kc_gallery_${System.nanoTime()}.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/KeepClearTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri =
            requireNotNull(
                resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values),
            )
        created = uri
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
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
                mime = mime,
                source = Source.MEDIA_STORE,
            )
        }
        return uri to ClassifiedItem(scan, Category.REVIEW, "review", GroupKey("review"), RiskChip.UNKNOWN)
    }
}
