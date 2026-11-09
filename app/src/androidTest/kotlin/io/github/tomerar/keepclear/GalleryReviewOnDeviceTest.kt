// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ActionExecutor
import io.github.tomerar.keepclear.core.safety.ActionHandler
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.OpResult
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SafetyGuard
import io.github.tomerar.keepclear.core.safety.SkipReason
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.core.storage.media.AndroidMediaRequests
import io.github.tomerar.keepclear.core.storage.media.MediaConsentHandler
import io.github.tomerar.keepclear.core.storage.media.MediaGateway
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.GroupKey
import io.github.tomerar.keepclear.engine.classify.RiskChip
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import io.github.tomerar.keepclear.engine.gallery.SourceVersion
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Source
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G4b on a real device (API 30/36 in CI): a photo chosen from a similar-photos group goes to **Android's own trash**
 * and comes back, through the real MediaStore re-check, the real media handler's veto and the real choke point with
 * the gallery bridge's plan. The rows are this test's own, so it may move them in and out of Android's trash itself;
 * for other apps' photos Android shows its dialog first, which a test can't answer. Also on the device: an edit of the
 * kept photo (a new MediaStore generation) or the kept photo in Android's trash refuses the group, and test mode
 * simulates.
 */
@RunWith(AndroidJUnit4::class)
class GalleryReviewOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val created = mutableListOf<Uri>()
    private val gateway = MediaGateway(resolver)

    @After
    fun cleanUp() {
        created.forEach { resolver.delete(it, null, null) }
    }

    private fun insert(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "kc_g4b_${System.nanoTime()}.jpg")
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

    private fun setTrashed(uri: Uri, trashed: Boolean) {
        val args = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }
        resolver.update(uri, values, args)
    }

    private fun row(uri: Uri) = KPath.Media(MediaStore.VOLUME_EXTERNAL_PRIMARY, uri.lastPathSegment!!.toLong())

    /** One photo as the scan and the comparison saw it (REVIEW, with its real size, time and generation). */
    private suspend fun member(uri: Uri): GalleryMember {
        val ref = row(uri)
        val s = gateway.stat(ref)!!
        val path = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null)!!.use {
            it.moveToFirst()
            it.getString(0)
        }
        val generation = s.identity!!.substringAfter("gen:").substringBefore(';')
        val item = ClassifiedItem(
            ScanItem(ref, path, s.size, s.mtimeMillis, mime = "image/jpeg", source = Source.MEDIA_STORE),
            Category.REVIEW,
            "ai_images",
            GroupKey("ai_images"),
            RiskChip.UNKNOWN,
        )
        val version = SourceVersion(
            SourceKey(ref.volume, ref.id.toString()),
            s.size,
            s.mtimeMillis,
            "media:1:x:$generation",
            "run",
        )
        return GalleryMember(item, suggested = false, source = version)
    }

    /**
     * The real media handler (its veto re-reads the row), performing what Android does once the user agrees in its
     * dialog: these rows are this test's own, so it may set the trashed flag itself.
     */
    private val handler = object : ActionHandler {
        val real = MediaConsentHandler(ActionHandler { _, _ -> error("not a file") }, AndroidMediaRequests(resolver))

        override suspend fun veto(op: PlannedOp, gateway: StorageGateway) = real.veto(op, gateway)

        override suspend fun perform(op: PlannedOp, gateway: StorageGateway) {
            val ref = op.ref as KPath.Media
            val uri = created.single { row(it) == ref }
            when (op.kind) {
                OpKind.TRASH -> setTrashed(uri, true)
                OpKind.RESTORE -> setTrashed(uri, false)
                else -> error("unexpected ${op.kind}")
            }
        }
    }

    private fun executor(dryRun: Boolean = false) =
        ActionExecutor(gateway, SafetyGuard(ProtectedPaths()), ProtectedPaths(), handler, "s", dryRun = dryRun)

    private fun plan(keep: GalleryMember, chosen: GalleryMember): ActionPlan {
        val group = GalleryKeep("g", "v", listOf(keep, chosen))
        return GalleryReview.plan("p", GalleryDisposal("g", listOf(chosen), listOf(group)))!!.confirm(1, "s")
    }

    private fun ran(report: ExecutionReport) = report as ExecutionReport.Ran

    @Test
    fun aChosenPhotoGoesToAndroidsTrashAndComesBack(): Unit = runBlocking {
        val a = insert(ByteArray(2048) { 1 })
        val bBytes = ByteArray(3072) { 2 }
        val b = insert(bBytes)
        val keep = member(a)
        val chosen = member(b)

        // Test mode first: simulated, nothing moved.
        ran(executor(dryRun = true).run(plan(keep, chosen))).results.single().let {
            assertTrue(it is OpResult.Simulated)
        }
        assertFalse(MediaGateway.isTrashed(gateway.stat(row(b))!!.identity!!))

        val done = ran(executor().run(plan(keep, chosen))).done.single().op
        assertEquals(OpKind.TRASH, done.kind)
        assertTrue("b is in Android's trash", MediaGateway.isTrashed(gateway.stat(row(b))!!.identity!!))
        assertFalse("a stays", MediaGateway.isTrashed(gateway.stat(row(a))!!.identity!!))

        // Undo: exactly b comes back, byte for byte.
        val restore = PlannedOp(row(b), done.displayPath, OpKind.RESTORE, false, done.expectedSize, done.expectedMtime)
        ran(executor().run(ActionPlan("u", listOf(restore), "undo").confirm(1, "s"))).done.single()
        assertFalse(MediaGateway.isTrashed(gateway.stat(row(b))!!.identity!!))
        assertArrayEquals(bBytes, gateway.openRead(row(b)).use { it.readBytes() })
    }

    @Test
    fun anEditedOrTrashedKeptPhotoRefusesTheGroup(): Unit = runBlocking {
        val a = insert(ByteArray(2048) { 1 })
        val b = insert(ByteArray(3072) { 2 })
        val keep = member(a)
        val chosen = member(b)

        // Another app edits a (same size): MediaStore gives it a new generation.
        resolver.openOutputStream(a, "wt")!!.use { it.write(ByteArray(2048) { 9 }) }
        val edited = ran(executor().run(plan(keep, chosen)))
        assertEquals(SkipReason.RETAINED_MEMBER_GONE, edited.skipped.single().reason)
        assertFalse(MediaGateway.isTrashed(gateway.stat(row(b))!!.identity!!))

        // a as it is now, then moved to Android's trash before the plan runs.
        val fresh = member(a)
        setTrashed(a, true)
        val trashed = ran(executor().run(plan(fresh, chosen)))
        assertEquals(SkipReason.RETAINED_MEMBER_GONE, trashed.skipped.single().reason)
        assertFalse(MediaGateway.isTrashed(gateway.stat(row(b))!!.identity!!))
        setTrashed(a, false)
    }
}
