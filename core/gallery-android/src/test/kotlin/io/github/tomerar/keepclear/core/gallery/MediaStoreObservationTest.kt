// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.GroupKey
import io.github.tomerar.keepclear.engine.classify.RiskChip
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaStoreObservationTest {
    private val run = GalleryRun("run", "scan", 1)
    private val protected = ProtectedPaths()
    private lateinit var provider: FakeMediaProvider
    private lateinit var reader: MediaStoreObservation

    @Before
    fun setUp() {
        provider = FakeMediaProvider()
        ShadowContentResolver.registerProviderInternal("media", provider)
        val context = ApplicationProvider.getApplicationContext<Context>()
        reader = MediaStoreObservation(context.contentResolver) { "store-v1" }
    }

    @Test
    fun `current scanner row is observed without opening image content`(): Unit = runBlocking {
        val result = reader.observe(item(), run, protected) { _, _ -> true }
        assertNotNull(result)
        assertEquals("external_primary", result!!.key.volume)
        assertEquals("17", result.key.ref)
        assertEquals("media:8:store-v1:3", result.revision)
        assertEquals("run", result.observationId)
        assertEquals(2, provider.queries)
        assertEquals(0, provider.opens)
    }

    @Test
    fun `protected and unapproved inputs never query provider`(): Unit = runBlocking {
        assertNull(reader.observe(item(category = Category.PROTECTED), run, protected) { _, _ -> true })
        assertNull(reader.observe(item(), run, protected) { _, _ -> false })
        val protectedItem = item(path = "/storage/emulated/0/WhatsApp/Databases/a.png")
        assertNull(reader.observe(protectedItem, run, protected) { _, _ -> true })
        assertEquals(0, provider.queries)
    }

    @Test
    fun `pending trashed favorite and wrong metadata are withheld`(): Unit = runBlocking {
        val flags = listOf(
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.IS_FAVORITE,
        )
        for (column in flags) {
            provider.fields[column] = 1
            assertNull(reader.observe(item(), run, protected) { _, _ -> true })
            provider.fields[column] = 0
        }
        provider.fields[MediaStore.MediaColumns.SIZE] = 101L
        assertNull(reader.observe(item(), run, protected) { _, _ -> true })
        provider.fields[MediaStore.MediaColumns.SIZE] = 100L
        provider.fields[MediaStore.MediaColumns.MIME_TYPE] = "video/mp4"
        assertNull(reader.observe(item(), run, protected) { _, _ -> true })
        provider.fields[MediaStore.MediaColumns.MIME_TYPE] = "image/png"
        provider.fields[MediaStore.MediaColumns.DATA] = "/storage/emulated/0/WhatsApp/Databases/a.png"
        assertNull(reader.observe(item(), run, protected) { _, _ -> true })
        provider.fields[MediaStore.MediaColumns.DATA] = "/storage/emulated/0/Pictures/a.png"
        provider.fields[MediaStore.MediaColumns.VOLUME_NAME] = "other_volume"
        assertNull(reader.observe(item(), run, protected) { _, _ -> true })
    }

    @Test
    fun `permission epoch change and provider replacement are withheld`(): Unit = runBlocking {
        var calls = 0
        assertNull(reader.observe(item(), run, protected) { _, _ -> ++calls == 1 })
        provider.queries = 0
        provider.afterQuery = {
            if (provider.queries == 1) provider.fields[MediaStore.MediaColumns.GENERATION_MODIFIED] = 4L
        }
        assertNull(reader.observe(item(), run, protected) { _, _ -> true })
    }

    @Test
    fun `store reset and missing generation are withheld`(): Unit = runBlocking {
        var calls = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reset = MediaStoreObservation(context.contentResolver) { if (++calls == 1) "v1" else "v2" }
        assertNull(reset.observe(item(), run, protected) { _, _ -> true })
        provider.fields[MediaStore.MediaColumns.GENERATION_MODIFIED] = 0L
        assertNull(reader.observe(item(), run, protected) { _, _ -> true })
    }

    @Test
    fun `provider access failure is treated as unavailable`(): Unit = runBlocking {
        provider.afterQuery = { throw SecurityException("revoked") }
        assertNull(reader.observe(item(), run, protected) { _, _ -> true })
    }

    @Test
    fun `cancellation from the live scope gate is propagated`(): Unit = runBlocking {
        var calls = 0
        try {
            reader.observe(item(), run, protected) { _, _ ->
                if (++calls == 2) throw CancellationException("stopped")
                true
            }
            fail("Cancellation must not become an unavailable result")
        } catch (_: CancellationException) {
            assertEquals(2, calls)
        }
    }

    private fun item(category: Category = Category.REVIEW, path: String = "/storage/emulated/0/Pictures/a.png") =
        ClassifiedItem(
            item = ScanItem(
                ref = KPath.Media("external_primary", 17),
                path = path,
                size = 100,
                mtimeMillis = 2_000,
                mime = "image/png",
                source = Source.MEDIA_STORE,
            ),
            category = category,
            ruleId = "review",
            group = GroupKey("review"),
            risk = RiskChip.UNKNOWN,
        )

    private class FakeMediaProvider : ContentProvider() {
        var queries = 0
        var opens = 0
        var afterQuery: () -> Unit = {}
        val fields = mutableMapOf<String, Any?>(
            MediaStore.MediaColumns._ID to 17L,
            MediaStore.MediaColumns.SIZE to 100L,
            MediaStore.MediaColumns.DATE_MODIFIED to 2L,
            MediaStore.MediaColumns.GENERATION_MODIFIED to 3L,
            MediaStore.MediaColumns.IS_PENDING to 0,
            MediaStore.MediaColumns.IS_TRASHED to 0,
            MediaStore.MediaColumns.MIME_TYPE to "image/png",
            MediaStore.MediaColumns.IS_FAVORITE to 0,
            MediaStore.MediaColumns.DATA to "/storage/emulated/0/Pictures/a.png",
            MediaStore.MediaColumns.VOLUME_NAME to "external_primary",
        )

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor {
            queries++
            val names = requireNotNull(projection)
            val cursor = MatrixCursor(names).apply { addRow(names.map { fields[it] }) }
            afterQuery()
            return cursor
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = query(uri, projection, null, null)

        override fun getType(uri: Uri): String? = null

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            throw FileNotFoundException()
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
