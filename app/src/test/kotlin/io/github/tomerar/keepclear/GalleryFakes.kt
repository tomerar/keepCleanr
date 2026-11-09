// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.gallery.GalleryCoordinator
import io.github.tomerar.keepclear.core.gallery.GalleryImageReader
import io.github.tomerar.keepclear.core.gallery.GalleryRead
import io.github.tomerar.keepclear.core.gallery.GallerySourceObserver
import io.github.tomerar.keepclear.core.gallery.UnsupportedImage
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.GroupKey
import io.github.tomerar.keepclear.engine.classify.RiskChip
import io.github.tomerar.keepclear.engine.gallery.FeatureRecord
import io.github.tomerar.keepclear.engine.gallery.FeatureVersion
import io.github.tomerar.keepclear.engine.gallery.GalleryAuthorization
import io.github.tomerar.keepclear.engine.gallery.GalleryFeatureStore
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import io.github.tomerar.keepclear.engine.gallery.GalleryScope
import io.github.tomerar.keepclear.engine.gallery.GalleryScopeSource
import io.github.tomerar.keepclear.engine.gallery.PermissionEpoch
import io.github.tomerar.keepclear.engine.gallery.QualityFacts
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import io.github.tomerar.keepclear.engine.gallery.SourceVersion
import io.github.tomerar.keepclear.engine.gallery.StoreWrite
import io.github.tomerar.keepclear.engine.gallery.VisualFacts
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Source
import java.util.concurrent.atomic.AtomicInteger

/**
 * G4a: a real [GalleryCoordinator] over fakes that follow the real ports' contracts (as in the coordinator's own
 * tests): the scope moves its epoch on every change, the store matches key + version + epoch, and the reads ask the
 * gate before and after. Pictures come in "families": photos of one family look alike, others don't.
 */
class GalleryFakes {
    val scope = FakeScope()
    val store = FakeStore()

    /** Per path: the family (look-alike hashes) and the usable pixels / sharpness (quality facts). */
    val family = mutableMapOf<String, Int>()
    val pixels = mutableMapOf<String, Long>()
    val unsupported = mutableSetOf<String>()

    /** Called before each decode; a test may suspend here (to stop or pause mid-run). */
    @Volatile var beforeRead: suspend (String) -> Unit = {}
    val decoded = AtomicInteger()

    private fun version(item: ClassifiedItem, run: GalleryRun) = SourceVersion(
        SourceKey((item.item.ref as KPath.Media).volume, (item.item.ref as KPath.Media).id.toString()),
        item.item.size,
        item.item.mtimeMillis,
        "media:r1",
        run.id,
    )

    val observer = GallerySourceObserver { item, run, _, gate ->
        if (!gate.allows(run, item)) null else version(item, run)
    }

    val reader = GalleryImageReader { item, run, featureVersion, _, gate ->
        val path = item.item.path
        if (!gate.allows(run, item)) return@GalleryImageReader GalleryRead.Unavailable
        beforeRead(path)
        if (path in unsupported) return@GalleryImageReader GalleryRead.Unsupported(UnsupportedImage.FORMAT)
        decoded.incrementAndGet()
        if (!gate.allows(run, item)) return@GalleryImageReader GalleryRead.Unavailable
        val hash = when (family[path] ?: -1) {
            0 -> 0L
            1 -> -1L
            2 -> 0x00000000FFFFFFFFL
            3 -> 0x0F0F0F0F0F0F0F0FL
            else -> 0x5555555555555555L xor path.hashCode().toLong()
        }
        GalleryRead.Read(
            FeatureRecord(
                version(item, run),
                featureVersion,
                VisualFacts(hash, hash, 1.0, false, QualityFacts(pixels[path] ?: 1_000_000L, 1.0, 0.0)),
            ),
        )
    }

    fun coordinator() = GalleryCoordinator(store, observer, reader, scope)

    class FakeScope : GalleryScopeSource {
        @Volatile var scope = GalleryScope.FULL_IMAGES
            set(value) {
                field = value
                current = PermissionEpoch.advance(current, value)
            }

        @Volatile private var current: GalleryAuthorization? = null

        override suspend fun current(): GalleryAuthorization =
            synchronized(this) { PermissionEpoch.advance(current, scope).also { current = it } }
    }

    class FakeStore : GalleryFeatureStore {
        private val rows = java.util.concurrent.ConcurrentHashMap<SourceKey, Pair<FeatureRecord, Long>>()

        @Volatile private var epoch = 0L

        override suspend fun get(source: SourceVersion, version: FeatureVersion, permissionEpoch: Long) =
            rows[source.key]?.takeIf { (r, e) ->
                r.source.copy(observationId = "o") == source.copy(observationId = "o") && r.version == version &&
                    e == permissionEpoch && epoch == permissionEpoch
            }?.let { FeatureRecord(source, version, it.first.facts) }

        override suspend fun put(record: FeatureRecord, permissionEpoch: Long): StoreWrite {
            if (permissionEpoch != epoch) return StoreWrite.STALE_EPOCH
            rows[record.source.key] = record to permissionEpoch
            return StoreWrite.STORED
        }

        override suspend fun beginEpoch(permissionEpoch: Long): StoreWrite {
            if (permissionEpoch < epoch) return StoreWrite.STALE_EPOCH
            if (permissionEpoch != epoch) rows.entries.removeIf { it.value.second != permissionEpoch }
            epoch = permissionEpoch
            return StoreWrite.STORED
        }

        override suspend fun invalidate(key: SourceKey): StoreWrite {
            rows.remove(key)
            return StoreWrite.STORED
        }

        override suspend fun clear(): StoreWrite {
            rows.clear()
            epoch = 0
            return StoreWrite.STORED
        }
    }

    companion object {
        /** A still image as the scan gives it: a MediaStore row in [category] (MOVE camera, or REVIEW). */
        fun photo(id: Int, category: Category = Category.REVIEW, mtime: Long = 1_700_000_000_000L): ClassifiedItem {
            // A REVIEW photo that is not a screenshot (screenshots are text-sensitive and never matched).
            val (rule, risk) = if (category == Category.MOVE) {
                "camera" to RiskChip.MIGHT_BE_MEMORY
            } else {
                "ai_images" to RiskChip.UNKNOWN
            }
            val folder = if (category == Category.MOVE) "DCIM/Camera" else "Pictures/Saved"
            return ClassifiedItem(
                ScanItem(
                    ref = KPath.Media("external_primary", id.toLong()),
                    path = "/storage/emulated/0/$folder/IMG_$id.jpg",
                    size = 2_000_000L + id,
                    mtimeMillis = mtime + id * 60_000L,
                    mime = "image/jpeg",
                    mediaId = id.toLong(),
                    source = Source.MEDIA_STORE,
                ),
                category,
                rule,
                GroupKey(rule),
                risk,
            )
        }

        fun summary(items: List<ClassifiedItem>) = ClassificationSummary.of(items)
    }
}
