// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.media

import android.content.ContentValues
import android.content.IntentSender
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.safety.ActionHandler
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.storage.KPath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** M12 on a device: MediaStore rows are re-checked through MediaStore; Android builds its own consent requests. */
@RunWith(AndroidJUnit4::class)
class MediaGatewayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val created = mutableListOf<android.net.Uri>()

    @After
    fun cleanUp() {
        created.forEach { resolver.delete(it, null, null) }
    }

    private fun insertImage(bytes: ByteArray): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "kc_gw_${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/KeepClearTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        created += uri
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    @Test
    fun statReadAndTrashedState(): Unit = runBlocking {
        val bytes = ByteArray(1234) { (it % 7).toByte() }
        val uri = insertImage(bytes)
        val row = KPath.Media(MediaStore.VOLUME_EXTERNAL_PRIMARY, uri.lastPathSegment!!.toLong())
        val gateway = MediaGateway(resolver)
        val stat = gateway.stat(row)!!
        assertEquals(1234L, stat.size)
        assertFalse(MediaGateway.isTrashed(stat.identity!!))
        assertArrayEquals(bytes, gateway.openRead(row).use { it.readBytes() })
        // This app owns the row, so it may mark it trashed directly (other apps' rows need Android's dialog).
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
        assertTrue(MediaGateway.isTrashed(gateway.stat(row)!!.identity!!))
    }

    @Test
    fun androidBuildsTheConsentRequests(): Unit = runBlocking {
        val uri = insertImage(ByteArray(10))
        val row = KPath.Media(MediaStore.VOLUME_EXTERNAL_PRIMARY, uri.lastPathSegment!!.toLong())
        val handler = MediaConsentHandler(ActionHandler { _, _ -> }, AndroidMediaRequests(resolver))
        val op = PlannedOp(row, "/storage/emulated/0/Pictures/KeepClearTest/x.jpg", OpKind.TRASH, false, 10, 1)
        val requests = handler.requestConsent(listOf(op))
        assertEquals(1, requests.size)
        assertTrue(requests.single() is IntentSender)
    }
}
