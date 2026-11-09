// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.GroupKey
import io.github.tomerar.keepclear.engine.classify.RiskChip
import io.github.tomerar.keepclear.engine.classify.Rules
import io.github.tomerar.keepclear.engine.gallery.FeatureRecord
import io.github.tomerar.keepclear.engine.gallery.FeatureVersion
import io.github.tomerar.keepclear.engine.gallery.GalleryAuthorization
import io.github.tomerar.keepclear.engine.gallery.GalleryFeatureStore
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import io.github.tomerar.keepclear.engine.gallery.GalleryScope
import io.github.tomerar.keepclear.engine.gallery.GalleryScopeSource
import io.github.tomerar.keepclear.engine.gallery.GroupEvidence
import io.github.tomerar.keepclear.engine.gallery.PermissionEpoch
import io.github.tomerar.keepclear.engine.gallery.QualityFacts
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import io.github.tomerar.keepclear.engine.gallery.SourceVersion
import io.github.tomerar.keepclear.engine.gallery.StoreWrite
import io.github.tomerar.keepclear.engine.gallery.VisualFacts
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

class GalleryCoordinatorTest {
    private val scope = FakeScope()
    private val store = FakeStore()
    private val reads = FakeReads()
    private var runs = 0
    private val coordinator =
        GalleryCoordinator(store, reads.observer, reads.reader, scope, newRunId = { "run${++runs}" })

    @Test
    fun `no access reads nothing and claims nothing`(): Unit = runBlocking {
        scope.scope = GalleryScope.NONE
        val result = coordinator.start(snapshot(photos(3)))!!
        assertEquals(GalleryRunState.NO_ACCESS, result.state)
        assertEquals(0, result.counts.eligible)
        assertEquals(0, reads.observed + reads.decoded)
        assertTrue(result.partial)
        assertNull(result.grouping)
    }

    @Test
    fun `selected photos are analysed but always partial, full images and all files complete`(): Unit = runBlocking {
        for ((granted, partial) in listOf(
            GalleryScope.SELECTED to true,
            GalleryScope.FULL_IMAGES to false,
            GalleryScope.ALL_FILES to false,
        )) {
            scope.scope = granted
            val result = coordinator.start(snapshot(photos(3)))!!
            assertEquals(granted.name, GalleryRunState.COMPLETED, result.state)
            assertEquals(granted, result.scope)
            assertEquals(3, result.counts.analysed)
            assertEquals(granted.name, partial, result.partial)
            assertNotNull(result.grouping)
            assertEquals(partial, result.grouping!!.limits.isNotEmpty())
        }
    }

    @Test
    fun `an incomplete scan or an item without a reader is never a complete gallery`(): Unit = runBlocking {
        val incomplete = coordinator.start(snapshot(photos(2), complete = false))!!
        assertTrue(incomplete.partial)
        val walked = item(9).let {
            it.copy(item = it.item.copy(ref = KPath.Local(it.item.path), source = Source.FILE_WALK))
        }
        val result = coordinator.start(snapshot(photos(2) + walked))!!
        assertEquals(1, result.counts.skipped[GallerySkip.NO_READER])
        assertEquals(3, result.counts.eligible)
        assertEquals(2, result.counts.analysed)
        assertTrue(result.partial)
    }

    @Test
    fun `cache hit needs no decode and an unchanged reopen decodes nothing`(): Unit = runBlocking {
        val items = photos(4)
        val first = coordinator.start(snapshot(items))!!
        assertEquals(4, first.counts.newlyDecoded)
        assertEquals(4, reads.decoded)
        val again = coordinator.start(snapshot(items))!!
        assertEquals(4, again.counts.fromCache)
        assertEquals(0, again.counts.newlyDecoded)
        assertEquals(4, reads.decoded)
    }

    @Test
    fun `a changed source version is recomputed, the rest comes from the cache`(): Unit = runBlocking {
        val items = photos(3)
        coordinator.start(snapshot(items))
        reads.revision[items[1].item.path] = "r2"
        val again = coordinator.start(snapshot(items))!!
        assertEquals(2, again.counts.fromCache)
        assertEquals(1, again.counts.newlyDecoded)
        assertEquals(4, reads.decoded)
    }

    @Test
    fun `scope reduction mid-run stops reads, hides cached records and starts a new epoch`(): Unit = runBlocking {
        val items = photos(6)
        coordinator.start(snapshot(items))
        assertEquals(6, store.rows.size)
        var observedAtChange = -1
        var lookupsAtChange = -1
        reads.onRead = {
            if (reads.decoded == 8) {
                scope.scope = GalleryScope.SELECTED
                observedAtChange = reads.observed
                lookupsAtChange = store.gets
            }
        }
        reads.revision[items[0].item.path] = "r2" // force decodes from the start
        reads.revision[items[1].item.path] = "r2"
        val run = coordinator.start(snapshot(items))!!
        assertEquals(GalleryRunState.ACCESS_CHANGED, run.state)
        assertNull(run.grouping)
        assertEquals(8, reads.decoded) // nothing read after the change
        assertEquals(observedAtChange, reads.observed) // not even observed
        assertEquals(lookupsAtChange, store.gets) // and nothing looked up in the cache
        assertEquals(2L, store.epoch)
        assertTrue(store.rows.values.none { it.second == 1L })
        // A new run under the reduced scope never gets an old record.
        reads.onRead = {}
        val next = coordinator.start(snapshot(items))!!
        assertEquals(0, next.counts.fromCache)
        assertEquals(6, next.counts.newlyDecoded)
        assertTrue(next.partial)
        // Continue cannot resume a run whose authorization changed.
        assertEquals(GalleryRunState.COMPLETED, coordinator.continueRun()!!.state)
    }

    @Test
    fun `an epoch change between runs drops old records before anything is read`(): Unit = runBlocking {
        coordinator.start(snapshot(photos(2)))
        scope.scope = GalleryScope.SELECTED
        scope.scope = GalleryScope.FULL_IMAGES // narrowed and widened again: still a new epoch
        val result = coordinator.start(snapshot(photos(2)))!!
        assertEquals(3L, store.epoch)
        assertEquals(0, result.counts.fromCache)
    }

    @Test
    fun `a newer epoch in the cache than in the ledger clears the cache instead of trusting it`(): Unit = runBlocking {
        store.epoch = 9
        val result = coordinator.start(snapshot(photos(2)))!!
        assertEquals(1, store.clears)
        assertEquals(1L, store.epoch)
        assertEquals(2, store.rows.size)
        assertFalse(result.cacheWritesFailed)
    }

    @Test
    fun `low space pauses decoding and writing, honestly, and Continue resumes the same run`(): Unit = runBlocking {
        val items = photos(5)
        store.lowSpaceAfter = 2
        val paused = coordinator.start(snapshot(items))!!
        assertEquals(GalleryRunState.PAUSED_LOW_SPACE, paused.state)
        assertTrue(paused.cacheWritesPaused)
        assertTrue(paused.partial)
        assertEquals(2, store.rows.size) // the third record was refused, never written
        assertEquals(3, reads.decoded) // decoding stopped right after the refusal
        assertEquals(3, paused.counts.analysed)
        assertEquals(2, paused.counts.notReached)
        assertNotNull(paused.grouping)
        store.lowSpaceAfter = Int.MAX_VALUE
        val resumed = coordinator.continueRun()!!
        assertEquals(paused.run, resumed.run)
        assertEquals(GalleryRunState.COMPLETED, resumed.state)
        assertEquals(5, resumed.counts.analysed)
        assertEquals(5, reads.decoded) // completed records reused, not decoded again
    }

    @Test
    fun `a failing cache write keeps the run going in memory without further writes`(): Unit = runBlocking {
        store.failWrites = true
        val result = coordinator.start(snapshot(photos(3)))!!
        assertEquals(GalleryRunState.COMPLETED, result.state)
        assertTrue(result.cacheWritesFailed)
        assertEquals(3, result.counts.analysed)
        assertEquals(1, store.puts)
    }

    @Test
    fun `stop cancels the in-flight read promptly, leaves a partial result and Continue reuses completed`(): Unit =
        runBlocking {
            val items = photos(6)
            val blocked = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            reads.block = { path ->
                if (path == items[2].item.path && !blocked.isCompleted) {
                    entered.complete(Unit)
                    blocked.await()
                }
            }
            val running = async { coordinator.start(snapshot(items)) }
            entered.await()
            coordinator.stop()
            val stopped = running.await()!!
            assertEquals(GalleryRunState.STOPPED, stopped.state)
            assertEquals(2, stopped.counts.analysed)
            assertEquals(4, stopped.counts.notReached)
            assertTrue(stopped.partial)
            assertEquals(2, stopped.grouping!!.diagnostics.eligible)
            blocked.complete(Unit)
            val resumed = coordinator.continueRun()!!
            assertEquals(stopped.run, resumed.run)
            assertEquals(GalleryRunState.COMPLETED, resumed.state)
            assertEquals(6, resumed.counts.analysed)
            assertEquals(6, reads.decoded) // the two completed ones are not read again
        }

    @Test
    fun `a file action pauses gallery work and a second start is refused while running`(): Unit = runBlocking {
        val items = photos(4)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        reads.block = { path ->
            if (path == items[1].item.path && !gate.isCompleted) {
                entered.complete(Unit)
                gate.await()
            }
        }
        val running = async { coordinator.start(snapshot(items)) }
        entered.await()
        assertNull(coordinator.start(snapshot(items)))
        val hook: GalleryWorkGate = coordinator
        hook.pauseForFileAction()
        val paused = running.await()!!
        assertEquals(GalleryRunState.PAUSED_FOR_FILE_ACTION, paused.state)
        assertEquals(1, paused.counts.analysed)
        gate.complete(Unit)
        assertEquals(GalleryRunState.COMPLETED, coordinator.continueRun()!!.state)
    }

    @Test
    fun `protected, kept, favourite, trashed, junk, excluded and pending items are never read`(): Unit =
        runBlocking {
            val ok = item(1)
            val chat = item(2, path = "/storage/emulated/0/WhatsApp/Databases/msgstore.png")
            val keptRule = item(3).copy(ruleId = Rules.USER_KEPT)
            val keptPath = item(4, path = "/storage/emulated/0/Pictures/Mine/a.png")
            val favourite = item(5).let { it.copy(item = it.item.copy(isFavorite = true)) }
            val trashed = item(6).let { it.copy(item = it.item.copy(isTrashed = true)) }
            val junk = item(7).copy(category = Category.SAFE)
            val backup = item(8, path = "/storage/ABCD-1234/Backup/a.png")
            val trash = item(10, path = "/storage/emulated/0/.KeepClearTrash/a.png")
            val pending = item(11)
            reads.inaccessible += pending.item.path
            val video = item(12).let { it.copy(item = it.item.copy(mime = "video/mp4")) }
            val all = listOf(ok, chat, keptRule, keptPath, favourite, trashed, junk, backup, trash, pending, video)
            val result = coordinator.start(
                snapshot(all) {
                    GalleryExclusions(
                        ProtectedPaths(),
                        keptRoots = listOf("/storage/emulated/0/Pictures/Mine"),
                        excludedRoots = listOf("/storage/ABCD-1234"),
                    )
                },
            )!!
            assertEquals(setOf(ok.item.path), reads.decodedPaths)
            assertEquals(setOf(ok.item.path, pending.item.path), reads.observedPaths)
            assertEquals(10, result.counts.stillImages)
            assertEquals(2, result.counts.eligible)
            assertEquals(
                mapOf(
                    GalleryExclusion.PROTECTED to 2,
                    GalleryExclusion.KEPT to 3,
                    GalleryExclusion.TRASHED to 1,
                    GalleryExclusion.JUNK to 1,
                    GalleryExclusion.EXCLUDED_LOCATION to 1,
                ),
                result.counts.excluded,
            )
            assertEquals(mapOf(GallerySkip.INACCESSIBLE to 1), result.counts.skipped)
        }

    @Test
    fun `an item kept mid-run is not read`(): Unit = runBlocking {
        val items = photos(3)
        var kept = emptyList<String>()
        reads.onRead = { kept = listOf(items[1].item.path, items[2].item.path) }
        val result = coordinator.start(snapshot(items) { GalleryExclusions(ProtectedPaths(), keptRoots = kept) })!!
        assertEquals(setOf(items[0].item.path), reads.decodedPaths)
        assertEquals(2, result.counts.skipped[GallerySkip.INACCESSIBLE])
    }

    @Test
    fun `unsupported, damaged, empty and changed items are skipped with their reason, not analysed`(): Unit =
        runBlocking {
            val heic = item(1).let { it.copy(item = it.item.copy(mime = "image/heic")) }
            val damaged = item(2)
            val empty = item(3).let { it.copy(item = it.item.copy(size = 0)) }
            val changed = item(4)
            val ok = item(5)
            val unreadable = item(6)
            reads.unavailable += unreadable.item.path
            reads.unsupported[heic.item.path] = UnsupportedImage.FORMAT
            reads.unsupported[damaged.item.path] = UnsupportedImage.DAMAGED
            reads.changeDuringRead += changed.item.path
            val result = coordinator.start(snapshot(listOf(heic, damaged, empty, changed, ok, unreadable)))!!
            assertEquals(6, result.counts.eligible)
            assertEquals(1, result.counts.analysed)
            assertEquals(
                mapOf(
                    GallerySkip.UNSUPPORTED_FORMAT to 1,
                    GallerySkip.DAMAGED to 1,
                    GallerySkip.EMPTY to 1,
                    GallerySkip.CHANGED to 1,
                    GallerySkip.UNREADABLE to 1,
                ),
                result.counts.skipped,
            )
            assertEquals(1, store.rows.size)
        }

    @Test
    fun `one worker and a queue of at most eight refs`(): Unit = runBlocking {
        reads.onRead = { yield() }
        val result = coordinator.start(snapshot(photos(40)))!!
        assertEquals(40, result.counts.analysed)
        assertEquals(1, reads.maxInFlight)
        assertEquals(1, result.maxInFlight)
        assertTrue("queued ${result.maxQueued}", result.maxQueued in 1..QUEUE_CAPACITY)
    }

    @Test
    fun `grouping is advisory and covers completed records only`(): Unit = runBlocking {
        val items = photos(3)
        reads.hash[items[2].item.path] = 0x5555_5555_5555_5555L
        val result = coordinator.start(snapshot(items))!!
        val grouping = result.grouping!!
        assertEquals(1, grouping.groups.size)
        assertEquals(GroupEvidence.COMPLETE_LINK_VISUAL, grouping.groups.single().evidence)
        assertEquals(2, grouping.groups.single().version.members.size)
        val leaked = types(GalleryProgress::class.java).filter { type ->
            listOf("core.safety", "core.model", "engine.classify", "engine.trash", "engine.move").any { it in type }
        }
        assertEquals(emptyList<String>(), leaked)
    }

    /** Every class reachable from the result's fields, by name (generic arguments included). */
    private fun types(root: Class<*>): Set<String> {
        val seen = linkedSetOf<String>()

        fun visit(type: Type) {
            when (type) {
                is ParameterizedType -> {
                    visit(type.rawType)
                    type.actualTypeArguments.forEach(::visit)
                }

                is Class<*> -> {
                    if (type.isPrimitive || !seen.add(type.name) || !type.name.startsWith("io.github")) return
                    type.declaredFields.forEach { visit(it.genericType) }
                    type.permittedSubclasses?.forEach(::visit)
                }
            }
        }
        visit(root)
        return seen
    }

    private fun photos(n: Int) = (1..n).map { item(it) }

    private fun item(id: Int, path: String = "/storage/emulated/0/DCIM/Camera/p$id.png") = ClassifiedItem(
        ScanItem(
            ref = KPath.Media("external_primary", id.toLong()),
            path = path,
            size = 1_000L + id,
            mtimeMillis = 1_000_000L,
            mime = "image/png",
            mediaId = id.toLong(),
            source = Source.MEDIA_STORE,
        ),
        Category.MOVE,
        "camera",
        GroupKey("camera"),
        RiskChip.MIGHT_BE_MEMORY,
    )

    private fun snapshot(
        items: List<ClassifiedItem>,
        complete: Boolean = true,
        exclusions: () -> GalleryExclusions = { GalleryExclusions(ProtectedPaths()) },
    ) = GallerySnapshot("scan", items, complete, exclusions)

    private class FakeScope : GalleryScopeSource {
        var scope = GalleryScope.FULL_IMAGES
            set(value) {
                field = value
                current = PermissionEpoch.advance(current, value)
            }
        private var current: GalleryAuthorization? = null

        override suspend fun current(): GalleryAuthorization =
            PermissionEpoch.advance(current, scope).also { current = it }
    }

    /** Same matching contract as the Room store: key + version + epoch, writes only in the recorded epoch. */
    private class FakeStore : GalleryFeatureStore {
        val rows = linkedMapOf<SourceKey, Pair<FeatureRecord, Long>>()
        var epoch = 0L
        var lowSpaceAfter = Int.MAX_VALUE
        var failWrites = false
        var puts = 0
        var clears = 0
        var gets = 0

        // Like the Room store: with a revision the observation does not matter, and the requested version is returned.
        override suspend fun get(
            source: SourceVersion,
            version: FeatureVersion,
            permissionEpoch: Long,
        ): FeatureRecord? =
            rows.also { gets++ }[source.key]?.takeIf { (record, e) ->
                record.source.copy(observationId = "o") == source.copy(observationId = "o") &&
                    record.version == version && e == permissionEpoch && epoch == permissionEpoch
            }?.let { FeatureRecord(source, version, it.first.facts) }

        override suspend fun put(record: FeatureRecord, permissionEpoch: Long): StoreWrite {
            puts++
            if (rows.size >= lowSpaceAfter) return StoreWrite.PAUSED_LOW_SPACE
            if (permissionEpoch != epoch) return StoreWrite.STALE_EPOCH
            if (failWrites) return StoreWrite.FAILED
            rows[record.source.key] = record to permissionEpoch
            return StoreWrite.STORED
        }

        override suspend fun beginEpoch(permissionEpoch: Long): StoreWrite {
            if (permissionEpoch < epoch) return StoreWrite.STALE_EPOCH
            epoch = permissionEpoch
            rows.entries.removeAll { it.value.second != permissionEpoch }
            return StoreWrite.STORED
        }

        override suspend fun invalidate(key: SourceKey): StoreWrite = StoreWrite.STORED.also { rows.remove(key) }

        override suspend fun clear(): StoreWrite {
            clears++
            rows.clear()
            epoch = 0
            return StoreWrite.STORED
        }
    }

    private class FakeReads {
        val revision = mutableMapOf<String, String>()
        val hash = mutableMapOf<String, Long>()
        val inaccessible = mutableSetOf<String>()
        val unsupported = mutableMapOf<String, UnsupportedImage>()
        val changeDuringRead = mutableSetOf<String>()
        val unavailable = mutableSetOf<String>()
        val observedPaths = linkedSetOf<String>()
        val decodedPaths = linkedSetOf<String>()
        var observed = 0
        var decoded = 0
        var inFlight = 0
        var maxInFlight = 0
        var onRead: suspend () -> Unit = {}
        var block: suspend (String) -> Unit = {}

        private fun version(item: ClassifiedItem, run: GalleryRun) = SourceVersion(
            SourceKey("external_primary", (item.item.ref as KPath.Media).id.toString()),
            item.item.size,
            item.item.mtimeMillis,
            "media:" + revision.getOrDefault(item.item.path, "r1"),
            run.id,
        )

        val observer = GallerySourceObserver { item, run, _, gate ->
            observed++
            observedPaths += item.item.path
            if (item.item.path in inaccessible || !gate.allows(run, item)) null else version(item, run)
        }

        val reader = GalleryImageReader { item, run, featureVersion, _, gate ->
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                if (!gate.allows(run, item)) return@GalleryImageReader GalleryRead.Unavailable
                unsupported[item.item.path]?.let { return@GalleryImageReader GalleryRead.Unsupported(it) }
                block(item.item.path)
                decoded++
                decodedPaths += item.item.path
                onRead()
                if (item.item.path in unavailable) return@GalleryImageReader GalleryRead.Unavailable
                if (item.item.path in changeDuringRead) {
                    revision[item.item.path] = "moved"
                    return@GalleryImageReader GalleryRead.Unavailable
                }
                if (!gate.allows(run, item)) return@GalleryImageReader GalleryRead.Unavailable
                val h = hash.getOrDefault(item.item.path, 0x0F0F_3C3C_0F0F_3C3CL)
                GalleryRead.Read(
                    FeatureRecord(
                        version(item, run),
                        featureVersion,
                        VisualFacts(h, h, 1.0, false, QualityFacts(100, 1.0, 0.0)),
                    ),
                )
            } finally {
                inFlight--
            }
        }
    }
}
