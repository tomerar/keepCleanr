// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.media

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** M3 DoD: MediaStore seeding test (runs on the CI emulators). Uses only items this app owns. */
@RunWith(AndroidJUnit4::class)
class MediaStoreSourceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val created = mutableListOf<android.net.Uri>()

    @After
    fun cleanUp() {
        created.forEach { resolver.delete(it, null, null) }
    }

    private fun insertImage(name: String, bytes: Int): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/KeepClearTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        created += uri
        resolver.openOutputStream(uri)!!.use { it.write(ByteArray(bytes) { i -> i.toByte() }) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    @Test
    fun seededImagesAreFoundWithSizeFavouriteAndTrashState() = runBlocking {
        val plain = insertImage("kc_plain_${System.nanoTime()}.jpg", 1234)
        val fav = insertImage("kc_fav_${System.nanoTime()}.jpg", 2345)
        val trashed = insertImage("kc_trash_${System.nanoTime()}.jpg", 3456)
        resolver.update(fav, ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, 1) }, null, null)
        resolver.update(trashed, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)

        val rows = MediaStoreSource(resolver).query(null).filter { "KeepClearTest" in it.path }
        val byId = rows.associateBy { it.id }
        val p = byId.getValue(plain.lastPathSegment!!.toLong())
        assertEquals(1234L, p.size)
        assertTrue(p.path.contains("Pictures/KeepClearTest/"))
        assertTrue(byId.getValue(fav.lastPathSegment!!.toLong()).isFavorite)
        assertTrue(byId.getValue(trashed.lastPathSegment!!.toLong()).isTrashed)
    }

    @Test
    fun incrementalQueryReturnsOnlyNewerGenerations() = runBlocking {
        val first = insertImage("kc_gen1_${System.nanoTime()}.jpg", 10)
        val generation = MediaStoreSource(resolver).query(null).first { it.id == first.lastPathSegment!!.toLong() }.generation
        val second = insertImage("kc_gen2_${System.nanoTime()}.jpg", 20)
        val newer = MediaStoreSource(resolver).query(generation).map { it.id }
        assertTrue(second.lastPathSegment!!.toLong() in newer)
        assertTrue(first.lastPathSegment!!.toLong() !in newer)
    }
}
