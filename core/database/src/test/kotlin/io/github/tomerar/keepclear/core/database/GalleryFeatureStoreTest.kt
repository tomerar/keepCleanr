// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.engine.gallery.FeatureRecord
import io.github.tomerar.keepclear.engine.gallery.FeatureVersion
import io.github.tomerar.keepclear.engine.gallery.QualityFacts
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import io.github.tomerar.keepclear.engine.gallery.SourceVersion
import io.github.tomerar.keepclear.engine.gallery.StoreWrite
import io.github.tomerar.keepclear.engine.gallery.VisualFacts
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** G1b cache: GAL-11/12/13/26, AC-G7/8/15, E-G07/10/14/15/21 on the real schema-8 database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GalleryFeatureStoreTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        KeepClearDatabase::class.java,
    )
        .allowMainThreadQueries()
        .build()
    private var free = 10_000_000_000L
    private val store = RoomGalleryFeatureStore(db.galleryDao(), { free })

    @After
    fun close() = db.close()

    private fun source(
        id: Int = 1,
        size: Long = 100,
        mtime: Long = 5_000,
        revision: String? = "media:4:abcd:7",
        observation: String = "run-1",
    ) = SourceVersion(SourceKey("external_primary", id.toString()), size, mtime, revision, observation)

    private fun record(source: SourceVersion = source(), version: FeatureVersion = FeatureVersion.V1, d: Long = 7) =
        FeatureRecord(source, version, VisualFacts(d, d.inv(), 1.5, false, QualityFacts(1_000_000, 2.5, 0.25)))

    private fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    @Test
    fun `a completed record round-trips with its exact facts`(): Unit = runBlocking {
        store.beginEpoch(1) shouldBe StoreWrite.STORED
        val r = record()
        store.put(r, 1) shouldBe StoreWrite.STORED
        store.get(r.source, r.version, 1) shouldBe r
    }

    @Test
    fun `any source or feature version difference is a miss (GAL-12, E-G07, E-G10)`(): Unit = runBlocking {
        store.beginEpoch(1)
        val r = record()
        store.put(r, 1)
        val v = r.version
        store.get(source(id = 2), v, 1) shouldBe null
        store.get(source(size = 101), v, 1) shouldBe null
        store.get(source(mtime = 5_001), v, 1) shouldBe null
        store.get(source(revision = "media:4:abcd:8"), v, 1) shouldBe null
        store.get(source(revision = null), v, 1) shouldBe null
        store.get(r.source, v.copy(preprocessing = "canonical-still-v2"), 1) shouldBe null
        store.get(r.source, v.copy(algorithm = "dhash64-phash64-quality-v2"), 1) shouldBe null
        store.get(SourceVersion(SourceKey("1234-5678", "1"), 100, 5_000, "media:4:abcd:7", "run-1"), v, 1) shouldBe null
        // A provider revision lets an unchanged source be reused by a later run (AC-G15) ...
        store.get(source(observation = "run-2"), v, 1) shouldBe record(source(observation = "run-2"))
    }

    @Test
    fun `without a revision a record is bound to the observation that read it (E-G07)`(): Unit = runBlocking {
        store.beginEpoch(1)
        val r = record(source(revision = null))
        store.put(r, 1) shouldBe StoreWrite.STORED
        store.get(r.source, r.version, 1) shouldBe r
        // Same size and time in a new run are never enough.
        store.get(source(revision = null, observation = "run-2"), r.version, 1) shouldBe null
    }

    @Test
    fun `a new permission epoch hides old records at once and then drops them (GAL-12, E-G02)`(): Unit = runBlocking {
        store.beginEpoch(1)
        val r = record()
        store.put(r, 1)
        // Hidden before anything is written: the caller passes its live epoch.
        store.get(r.source, r.version, 2) shouldBe null
        store.beginEpoch(2) shouldBe StoreWrite.STORED
        count("gallery_feature") shouldBe 0
        store.get(r.source, r.version, 1) shouldBe null
        // An old run can neither write nor turn the epoch back.
        store.put(r, 1) shouldBe StoreWrite.STALE_EPOCH
        store.beginEpoch(1) shouldBe StoreWrite.STALE_EPOCH
        count("gallery_feature") shouldBe 0
        store.put(r, 2) shouldBe StoreWrite.STORED
        store.get(r.source, r.version, 2) shouldBe r
    }

    @Test
    fun `nothing is written before an epoch is recorded`(): Unit = runBlocking {
        store.put(record(), 0) shouldBe StoreWrite.STALE_EPOCH
        count("gallery_feature") shouldBe 0
    }

    @Test
    fun `invalidating one source removes only that source`(): Unit = runBlocking {
        store.beginEpoch(1)
        val a = record(source(id = 1))
        val b = record(source(id = 2))
        store.put(a, 1)
        store.put(b, 1)
        store.invalidate(a.source.key) shouldBe StoreWrite.STORED
        store.get(a.source, a.version, 1) shouldBe null
        store.get(b.source, b.version, 1) shouldBe b
    }

    @Test
    fun `invalid facts and corrupt rows are never cached or returned (E-G10)`(): Unit = runBlocking {
        store.beginEpoch(1)
        val r = record()
        store.put(r.copy(facts = r.facts.copy(aspect = Double.NaN)), 1) shouldBe StoreWrite.REJECTED
        store.put(r, -1) shouldBe StoreWrite.REJECTED
        count("gallery_feature") shouldBe 0
        store.put(r, 1)
        val stored = db.galleryDao().feature(RoomGalleryFeatureStore.keyDigest(r.source.key))!!
        db.galleryDao().putFeature(stored.copy(clippedFraction = 2.0))
        store.get(r.source, r.version, 1) shouldBe null
    }

    @Test
    fun `no key, ref, path or revision text is stored (GAL-11)`(): Unit = runBlocking {
        store.beginEpoch(1)
        val secret = SourceVersion(
            SourceKey("external_primary", "/storage/emulated/0/DCIM/secret.jpg"),
            1,
            2,
            "media:3:xyz:9",
            "run-secret",
        )
        store.put(record(secret), 1) shouldBe StoreWrite.STORED
        db.openHelper.readableDatabase.query("SELECT * FROM gallery_feature").use { c ->
            c.moveToFirst() shouldBe true
            (0 until c.columnCount).map { c.getString(it) }.forEach { value ->
                listOf("external_primary", "secret", "storage", "xyz", "run-secret", "canonical").forEach {
                    value.contains(it) shouldBe false
                }
            }
        }
    }

    @Test
    fun `clearing the gallery keeps trash, backup proofs, move jobs, scan and hash data (GAL-13, E-G21)`(): Unit =
        runBlocking {
            val raw = db.openHelper.writableDatabase
            AuthoritativeRows.insert(raw)
            val before = AuthoritativeRows.dump(raw)
            store.beginEpoch(3)
            repeat(5) { store.put(record(source(id = it)), 3) }
            count("gallery_feature") shouldBe 5
            store.clear() shouldBe StoreWrite.STORED
            count("gallery_feature") shouldBe 0
            count("gallery_state") shouldBe 0
            AuthoritativeRows.dump(raw) shouldBe before
            // The recorded epoch went too: a stale run cannot write until a run records its epoch again.
            store.put(record(), 3) shouldBe StoreWrite.STALE_EPOCH
        }

    @Test
    fun `at quota the least recently stored valid row is evicted and becomes a miss`(): Unit = runBlocking {
        val small = RoomGalleryFeatureStore(db.galleryDao(), { free }, maxRows = 3)
        small.beginEpoch(1)
        val rs = (1..4).map { record(source(id = it)) }
        rs.forEach { small.put(it, 1) shouldBe StoreWrite.STORED }
        count("gallery_feature") shouldBe 3
        small.get(rs[0].source, rs[0].version, 1) shouldBe null
        rs.drop(1).forEach { small.get(it.source, it.version, 1) shouldBe it }
    }

    @Test
    fun `rows of another feature version are evicted before current ones`(): Unit = runBlocking {
        val small = RoomGalleryFeatureStore(db.galleryDao(), { free }, maxRows = 2)
        small.beginEpoch(1)
        val current = record(source(id = 1))
        val old = record(source(id = 2), FeatureVersion("canonical-still-v0", "old"))
        small.put(current, 1)
        small.put(old, 1)
        val next = record(source(id = 3))
        small.put(next, 1) shouldBe StoreWrite.STORED
        small.get(current.source, current.version, 1) shouldBe current
        small.get(next.source, next.version, 1) shouldBe next
        small.get(old.source, old.version, 1) shouldBe null
    }

    @Test
    fun `no gallery writes below 500 MB free or in MINIMAL (LOW-1, LOW-2, E-G15)`(): Unit = runBlocking {
        store.beginEpoch(1)
        val r = record()
        free = 499_999_999
        store.put(r, 1) shouldBe StoreWrite.PAUSED_LOW_SPACE
        free = 99_999_999
        store.put(r, 1) shouldBe StoreWrite.PAUSED_LOW_SPACE
        count("gallery_feature") shouldBe 0
        val broken = RoomGalleryFeatureStore(db.galleryDao(), { error("no probe") })
        broken.put(r, 1) shouldBe StoreWrite.PAUSED_LOW_SPACE
        // Removals still run: they only shrink the cache.
        store.beginEpoch(2) shouldBe StoreWrite.STORED
        free = 500_000_000
        store.put(r, 2) shouldBe StoreWrite.STORED
    }

    @Test
    fun `a full database aborts only the gallery write and keeps authoritative rows (E-G14, LOW-4)`(): Unit =
        runBlocking {
            val raw = db.openHelper.writableDatabase
            AuthoritativeRows.insert(raw)
            val before = AuthoritativeRows.dump(raw)
            store.beginEpoch(1)
            val pages = raw.query("PRAGMA page_count").use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
            raw.query("PRAGMA max_page_count = $pages").use { it.moveToFirst() }
            // Real SQLITE_FULL: keep writing until a new page is needed.
            val results = (1..500).map { store.put(record(source(id = it)), 1) }
            val firstFail = results.indexOf(StoreWrite.FAILED)
            firstFail shouldNotBe -1
            results.take(firstFail).all { it == StoreWrite.STORED } shouldBe true
            val failed = record(source(id = firstFail + 1))
            store.get(failed.source, failed.version, 1) shouldBe null
            count("gallery_feature") shouldBe results.count { it == StoreWrite.STORED }
            AuthoritativeRows.dump(raw) shouldBe before
            raw.query("PRAGMA max_page_count = 1073741823").use { it.moveToFirst() }
            store.put(failed, 1) shouldBe StoreWrite.STORED
            store.get(failed.source, failed.version, 1) shouldBe failed
        }
}
