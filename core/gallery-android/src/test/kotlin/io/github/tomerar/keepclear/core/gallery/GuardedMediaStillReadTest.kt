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
class GuardedMediaStillReadTest {
    private val run = GalleryRun("run", "scan", 1)
    private val protected = ProtectedPaths()
    private lateinit var provider: FakeMediaProvider
    private lateinit var reader: GuardedMediaStillRead

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
        reader = GuardedMediaStillRead(context.contentResolver, observation) {
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
        assertTrue(result is GuardedStill.Read)
        assertEquals(1, provider.opens)
        assertEquals(4, provider.queries)
        assertTrue(provider.closed)
    }

    @Test
    fun `protected and unapproved sources do not open`(): Unit = runBlocking {
        assertEquals(
            GuardedStill.Unavailable,
            reader.read(item(Category.PROTECTED), run, FeatureVersion.V1, protected) { _, _ -> true },
        )
        assertEquals(
            GuardedStill.Unavailable,
            reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> false },
        )
        assertEquals(
            GuardedStill.Unavailable,
            reader.read(item(), run, FeatureVersion("new", "new"), protected) { _, _ -> true },
        )
        assertEquals(0, provider.opens)
    }

    @Test
    fun `replaced source after open never publishes features`(): Unit = runBlocking {
        provider.onOpen = { provider.fields[MediaStore.MediaColumns.GENERATION_MODIFIED] = 4L }
        assertEquals(
            GuardedStill.Unavailable,
            reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> true },
        )
        assertEquals(1, provider.opens)
    }

    @Test
    fun `scope reduction after open never publishes features`(): Unit = runBlocking {
        var calls = 0
        assertEquals(
            GuardedStill.Unavailable,
            reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> ++calls < 3 },
        )
        assertEquals(1, provider.opens)
    }

    @Test
    fun `revoked stream access is unavailable`(): Unit = runBlocking {
        provider.onOpen = { throw SecurityException("revoked") }
        assertEquals(
            GuardedStill.Unavailable,
            reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> true },
        )
    }

    @Test
    fun `stream length mismatch never publishes features`(): Unit = runBlocking {
        val wrongSize = provider.data.size.toLong() + 1
        provider.fields[MediaStore.MediaColumns.SIZE] = wrongSize
        assertEquals(
            GuardedStill.Unavailable,
            reader.read(item(size = wrongSize), run, FeatureVersion.V1, protected) { _, _ -> true },
        )
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

    @Test
    fun `content signature selects the decoder whatever the name and mime say`(): Unit = runBlocking {
        content(JpegFixtures.oriented(6)) // Named a.png with MIME image/png.
        val result = reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> true }
        assertTrue(result is GuardedStill.Read)
        assertEquals(2.0, (result as GuardedStill.Read).record.facts.aspect, 0.0)
        assertEquals(4, provider.queries)
        assertTrue(provider.closed)
    }

    @Test
    fun `current but unsupported or damaged content is reported as unsupported`(): Unit = runBlocking {
        content("GIF89a".toByteArray() + ByteArray(32))
        assertEquals(
            GuardedStill.Unsupported(StillSkip.UNSUPPORTED_FORMAT),
            reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> true },
        )
        assertTrue(provider.closed)
        content(JpegFixtures.encode(16, 16) + 0)
        assertEquals(
            GuardedStill.Unsupported(StillSkip.UNSUPPORTED_VARIANT),
            reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> true },
        )
        content(JpegFixtures.encode(16, 16).let { it.copyOf(it.size - 10) })
        assertEquals(
            GuardedStill.Unsupported(StillSkip.DAMAGED),
            reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> true },
        )
        assertEquals(3, provider.opens)
        assertEquals(0, provider.writes)
    }

    @Test
    fun `unsupported content of a source that changed is unavailable`(): Unit = runBlocking {
        content("GIF89a".toByteArray() + ByteArray(32))
        provider.onOpen = { provider.fields[MediaStore.MediaColumns.GENERATION_MODIFIED] = 4L }
        assertEquals(
            GuardedStill.Unavailable,
            reader.read(item(), run, FeatureVersion.V1, protected) { _, _ -> true },
        )
    }

    @Test
    fun `a source above the byte cap is never opened`(): Unit = runBlocking {
        val size = BoundedStillDecoder.MAX_BYTES + 1L
        provider.fields[MediaStore.MediaColumns.SIZE] = size
        assertEquals(
            GuardedStill.Unsupported(StillSkip.TOO_LARGE_FILE),
            reader.read(item(size = size), run, FeatureVersion.V1, protected) { _, _ -> true },
        )
        assertEquals(0, provider.opens)
        assertEquals(4, provider.queries)
    }

    private fun content(bytes: ByteArray) {
        provider.data = bytes
        provider.fields[MediaStore.MediaColumns.SIZE] = bytes.size.toLong()
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

    private class FakeMediaProvider(var data: ByteArray) : ContentProvider() {
        var opens = 0
        var closed = false
        var queries = 0
        var writes = 0
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

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            writes++
            return null
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            writes++
            return 0
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            writes++
            return 0
        }
    }
}
