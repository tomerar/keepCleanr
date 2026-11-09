// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.engine.gallery.FeatureRecord
import io.github.tomerar.keepclear.engine.gallery.FeatureVersion
import io.github.tomerar.keepclear.engine.gallery.QualityFacts
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import io.github.tomerar.keepclear.engine.gallery.SourceVersion
import io.github.tomerar.keepclear.engine.gallery.StoreWrite
import io.github.tomerar.keepclear.engine.gallery.VisualFacts
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * C206: measures the real database file (schema 8, on disk, Robolectric's SQLite) at the row cap after churn that
 * evicts half again as many rows and scattered invalidations, with the longest legal source key and revision. Robolectric's SQLite is not a
 * phone: the on-device figure (including the WAL under other tables' writes) is still to be measured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GalleryCacheQuotaTest {
    @Test
    fun `the gallery cache at its row cap stays within 16 MB including the log allowance`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("quota.db")
        val db = Room.databaseBuilder(context, KeepClearDatabase::class.java, file.absolutePath).build()
        val store = RoomGalleryFeatureStore(db.galleryDao(), { 10_000_000_000L })
        store.beginEpoch(Long.MAX_VALUE)
        val wal = File(file.path + "-wal")
        var maxWal = 0L
        val random = java.util.Random(8)
        val total = GalleryCacheQuota.MAX_ROWS + GalleryCacheQuota.MAX_ROWS / 2
        for (i in 0 until total) {
            val record = FeatureRecord(
                SourceVersion(
                    SourceKey("v".repeat(512), i.toString().padStart(4096, '9')),
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                    "r".repeat(4096),
                    "o".repeat(4096),
                ),
                FeatureVersion.V1,
                VisualFacts(
                    Long.MIN_VALUE,
                    Long.MAX_VALUE,
                    Double.MAX_VALUE,
                    true,
                    QualityFacts(Long.MAX_VALUE, Double.MAX_VALUE, 1.0),
                ),
            )
            store.put(record, Long.MAX_VALUE) shouldBe StoreWrite.STORED
            // Scattered invalidations leave free space inside pages, as source changes would.
            if (i % 7 == 0 && i in 1 until GalleryCacheQuota.MAX_ROWS) {
                store.invalidate(SourceKey("v".repeat(512), random.nextInt(i).toString().padStart(4096, '9')))
            }
            if (i % 500 == 0) maxWal = maxOf(maxWal, wal.length())
        }
        maxWal = maxOf(maxWal, wal.length())
        db.galleryDao().count() shouldBe GalleryCacheQuota.MAX_ROWS
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val main = file.length()
        db.close()
        println("gallery quota: rows=${GalleryCacheQuota.MAX_ROWS} main=$main maxWal=$maxWal bytes")
        main shouldBeLessThanOrEqual GalleryCacheQuota.BUDGET_BYTES - GalleryCacheQuota.WAL_ALLOWANCE_BYTES
        maxWal shouldBeLessThanOrEqual GalleryCacheQuota.WAL_ALLOWANCE_BYTES
    }
}
