// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
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
import io.github.tomerar.keepclear.engine.gallery.FeatureVersion
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuardedMediaPngReadTest {
    private val run = GalleryRun("run", "scan", 1)
    private val protected = ProtectedPaths()
    private lateinit var provider: FakeMediaProvider
    private lateinit var reader: GuardedMediaPngRead

    @Before
    fun setUp() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        provider = FakeMediaProvider(output.toByteArray())
        ShadowContentResolver.registerProviderInternal("media", provider)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val observation = MediaStoreObservation(context.contentResolver) { "store-v1" }
        reader = GuardedMediaPngRead(context.contentResolver, observation) {
            provider.opens++
            provider.onOpen()
            object : ByteArrayInputStream(provider.data) {
                override fun close() {
                    provider.closed = true
                    super.close()
                }
            }
        }
    }

    @Test
    fun `current scoped png is decoded once`(): Unit = runBlocking {
        val result = reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> true }
        assertNotNull(result)
        assertEquals(1, provider.opens)
        assertEquals(4, provider.queries)
        assertTrue(provider.closed)
    }

    @Test
    fun `protected and unapproved sources do not open`(): Unit = runBlocking {
        assertNull(reader.read(item(Category.PROTECTED), run, FeatureVersion.V1, protected) { _, _ -> true })
        assertNull(reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> false })
        assertNull(reader.read(item(), run, FeatureVersion("new", "new"), protected) { _, _ -> true })
        assertEquals(0, provider.opens)
    }

    @Test
    fun `replaced source after open never publishes features`(): Unit = runBlocking {
        provider.onOpen = { provider.fields[MediaStore.MediaColumns.GENERATION_MODIFIED] = 4L }
        assertNull(reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> true })
        assertEquals(1, provider.opens)
    }

    @Test
    fun `scope reduction after open never publishes features`(): Unit = runBlocking {
        var calls = 0
        assertNull(reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> ++calls < 3 })
        assertEquals(1, provider.opens)
    }

    @Test
    fun `revoked stream access is unavailable`(): Unit = runBlocking {
        provider.onOpen = { throw SecurityException("revoked") }
        assertNull(reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> true })
    }

    @Test
    fun `stream length mismatch never publishes features`(): Unit = runBlocking {
        val wrongSize = provider.data.size.toLong() + 1
        provider.fields[MediaStore.MediaColumns.SIZE] = wrongSize
        assertNull(reader.read(item(size = wrongSize), run, FeatureVersion.V1, protected) { _, _ -> true })
        assertEquals(1, provider.opens)
    }

    @Test
    fun `cancellation after decoding propagates and stream is closed`(): Unit = runBlocking {
        var calls = 0
        try {
            reader.read(item(), run, FeatureVersion.V1, protected) { _, _ ->
                if (++calls == 3) throw CancellationException("stopped")
                true
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(provider.closed)
        }
    }

    private fun item(category: Category = Category.REVIEW, size: Long = provider.data.size.toLong()) = ClassifiedItem(
        item = ScanItem(
            ref = KPath.Media("external_primary", 17),
            path = "/storage/emulated/0/Pictures/a.png",
            size = size,
            mtimeMillis = 2_000,
            mime = "image/png",
            source = Source.MEDIA_STORE,
        ),
        category = category,
        ruleId = "review",
        group = GroupKey("review"),
        risk = RiskChip.UNKNOWN,
    )

    private class FakeMediaProvider(val data: ByteArray) : ContentProvider() {
        var opens = 0
        var closed = false
        var queries = 0
        var onOpen: () -> Unit = {}
        val fields = mutableMapOf<String, Any?>(
            MediaStore.MediaColumns._ID to 17L,
            MediaStore.MediaColumns.SIZE to data.size.toLong(),
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
            return MatrixCursor(names).apply { addRow(names.map { fields[it] }) }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = query(uri, projection, null, null)

        override fun getType(uri: Uri): String = "image/png"

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            onOpen()
            val pipe = ParcelFileDescriptor.createPipe()
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(data) }
            return pipe[0]
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
