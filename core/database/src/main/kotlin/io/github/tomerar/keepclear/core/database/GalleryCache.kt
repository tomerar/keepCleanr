// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import android.database.sqlite.SQLiteException
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import io.github.tomerar.keepclear.engine.gallery.FeatureRecord
import io.github.tomerar.keepclear.engine.gallery.FeatureVersion
import io.github.tomerar.keepclear.engine.gallery.GalleryFeatureStore
import io.github.tomerar.keepclear.engine.gallery.QualityFacts
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import io.github.tomerar.keepclear.engine.gallery.SourceVersion
import io.github.tomerar.keepclear.engine.gallery.StoreWrite
import io.github.tomerar.keepclear.engine.gallery.VisualFacts
import io.github.tomerar.keepclear.engine.scan.SpaceMode
import java.security.MessageDigest

/**
 * Recomputable gallery feature cache (schema 8, GAL-11/12/26). One fixed-size row per source: the key and both
 * versions are SHA-256 digests (64 hex characters), so no volume name, media ID, path or revision string is stored
 * and every row has the same upper bound (C206). Only compact numeric facts: no vectors, no thumbnails.
 */
@Entity(tableName = "gallery_feature", indices = [Index("storedSeq")])
data class GalleryFeatureEntity(
    @PrimaryKey val sourceKey: String,
    val sourceVersion: String,
    val featureVersion: String,
    val permissionEpoch: Long,
    val dHash: Long,
    val pHash: Long,
    val aspect: Double,
    val lowDetail: Boolean,
    val usablePixels: Long,
    val sharpness: Double,
    val clippedFraction: Double,
    /** Write order for eviction (least recently stored first); never a time. */
    val storedSeq: Long,
)

/** The single gallery cache state row: the current permission epoch (C207). Recomputable, cleared with the cache. */
@Entity(tableName = "gallery_state")
data class GalleryStateEntity(
    @PrimaryKey val id: Int = 0,
    val permissionEpoch: Long,
)

/** Touches only `gallery_*` tables (GAL-13; checked by `ArchitectureTest`). */
@Dao
interface GalleryDao {
    @Query("SELECT * FROM gallery_feature WHERE sourceKey = :key")
    suspend fun feature(key: String): GalleryFeatureEntity?

    @Query("SELECT permissionEpoch FROM gallery_state WHERE id = 0")
    suspend fun epoch(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: GalleryStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFeature(feature: GalleryFeatureEntity)

    @Query("SELECT COALESCE(MAX(storedSeq), 0) FROM gallery_feature")
    suspend fun maxSeq(): Long

    @Query("SELECT COUNT(*) FROM gallery_feature")
    suspend fun count(): Int

    /** Other feature versions first (already useless), then the least recently stored. */
    @Query(
        "DELETE FROM gallery_feature WHERE sourceKey IN (SELECT sourceKey FROM gallery_feature " +
            "ORDER BY featureVersion = :current, storedSeq LIMIT :n)",
    )
    suspend fun evict(current: String, n: Int): Int

    @Query("DELETE FROM gallery_feature WHERE permissionEpoch != :epoch")
    suspend fun dropOtherEpochs(epoch: Long): Int

    @Query("DELETE FROM gallery_feature WHERE sourceKey = :key")
    suspend fun remove(key: String): Int

    @Query("DELETE FROM gallery_feature")
    suspend fun clearFeatures(): Int

    @Query("DELETE FROM gallery_state")
    suspend fun clearState(): Int

    /** One completed record, or nothing: refused unless [feature]'s epoch is the recorded current one. */
    @Transaction
    suspend fun store(feature: GalleryFeatureEntity, maxRows: Int): Boolean {
        if (epoch() != feature.permissionEpoch) return false
        putFeature(feature.copy(storedSeq = maxSeq() + 1))
        val excess = count() - maxRows
        if (excess > 0) evict(feature.featureVersion, excess)
        return true
    }

    /** Records [epoch] and drops every other epoch's rows; an older epoch than the recorded one is refused. */
    @Transaction
    suspend fun begin(epoch: Long): Boolean {
        val current = epoch()
        if (current != null && epoch < current) return false
        putState(GalleryStateEntity(permissionEpoch = epoch))
        dropOtherEpochs(epoch)
        return true
    }

    @Transaction
    suspend fun clearAll() {
        clearFeatures()
        clearState()
    }
}

/**
 * Deterministic size bound (C206): [BUDGET_BYTES] minus a write-ahead-log/journal allowance, divided by a per-row
 * upper bound that covers the table row, its primary-key index entry, the eviction index entry and B-tree page
 * slack. `GalleryFeatureStoreTest` measures the real file at [MAX_ROWS] after eviction churn.
 */
object GalleryCacheQuota {
    const val BUDGET_BYTES = 16_000_000L
    const val WAL_ALLOWANCE_BYTES = 4_000_000L
    const val ROW_BOUND_BYTES = 512L
    const val MAX_ROWS = ((BUDGET_BYTES - WAL_ALLOWANCE_BYTES) / ROW_BOUND_BYTES).toInt()
}

/**
 * Room implementation of [GalleryFeatureStore]. It only holds [GalleryDao], so it cannot reach trash, move, scan
 * or hash-cache data (GAL-13). Writes are refused unless free space is NORMAL (LOW-1/2, E-G15); a database error
 * during a write rolls back that write only and reports [StoreWrite.FAILED] (LOW-4, E-G14). Reads that fail or
 * find corrupt facts are misses (E-G10). Never logs keys or facts.
 */
class RoomGalleryFeatureStore(
    private val dao: GalleryDao,
    private val freeBytes: () -> Long,
    private val maxRows: Int = GalleryCacheQuota.MAX_ROWS,
) : GalleryFeatureStore {
    init {
        require(maxRows in 1..GalleryCacheQuota.MAX_ROWS)
    }

    override suspend fun get(source: SourceVersion, version: FeatureVersion, permissionEpoch: Long): FeatureRecord? {
        val row = try {
            dao.feature(keyDigest(source.key))
        } catch (_: SQLiteException) {
            null
        } ?: return null
        if (row.sourceVersion != versionDigest(source) || row.featureVersion != featureDigest(version) ||
            row.permissionEpoch != permissionEpoch
        ) {
            return null
        }
        val facts = VisualFacts(
            dHash = row.dHash,
            pHash = row.pHash,
            aspect = row.aspect,
            lowDetail = row.lowDetail,
            quality = QualityFacts(row.usablePixels, row.sharpness, row.clippedFraction),
        )
        return if (facts.valid()) FeatureRecord(source, version, facts) else null
    }

    override suspend fun put(record: FeatureRecord, permissionEpoch: Long): StoreWrite {
        if (permissionEpoch < 0 || !record.facts.valid()) return StoreWrite.REJECTED
        if (!writable()) return StoreWrite.PAUSED_LOW_SPACE
        val facts = record.facts
        val row = GalleryFeatureEntity(
            sourceKey = keyDigest(record.source.key),
            sourceVersion = versionDigest(record.source),
            featureVersion = featureDigest(record.version),
            permissionEpoch = permissionEpoch,
            dHash = facts.dHash,
            pHash = facts.pHash,
            aspect = facts.aspect,
            lowDetail = facts.lowDetail,
            usablePixels = facts.quality.usablePixels,
            sharpness = facts.quality.sharpness,
            clippedFraction = facts.quality.clippedFraction,
            storedSeq = 0,
        )
        return write { if (dao.store(row, maxRows)) StoreWrite.STORED else StoreWrite.STALE_EPOCH }
    }

    /** Removals only shrink the cache, so they run in every space mode; [get] hides by epoch even if this fails. */
    override suspend fun beginEpoch(permissionEpoch: Long): StoreWrite {
        if (permissionEpoch < 0) return StoreWrite.REJECTED
        return write { if (dao.begin(permissionEpoch)) StoreWrite.STORED else StoreWrite.STALE_EPOCH }
    }

    override suspend fun invalidate(key: SourceKey): StoreWrite = write {
        dao.remove(keyDigest(key))
        StoreWrite.STORED
    }

    override suspend fun clear(): StoreWrite = write {
        dao.clearAll()
        StoreWrite.STORED
    }

    private fun writable(): Boolean = try {
        SpaceMode.of(freeBytes()) == SpaceMode.NORMAL
    } catch (_: RuntimeException) {
        false
    }

    private inline fun write(block: () -> StoreWrite): StoreWrite = try {
        block()
    } catch (_: SQLiteException) {
        StoreWrite.FAILED
    }

    internal companion object {
        /** Length-prefixed fields under a domain tag: no delimiter collisions between different inputs. */
        private fun digest(tag: String, vararg fields: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            (listOf(tag) + fields).forEach { field ->
                val bytes = field.toByteArray(Charsets.UTF_8)
                md.update(
                    byteArrayOf(
                        (bytes.size ushr 24).toByte(),
                        (bytes.size ushr 16).toByte(),
                        (bytes.size ushr 8).toByte(),
                        bytes.size.toByte(),
                    ),
                )
                md.update(bytes)
            }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        fun keyDigest(key: SourceKey): String = digest("gallery-key-v1", key.volume, key.ref)

        /**
         * E-G07: size and time never permit reuse by themselves. With a provider revision the record survives runs;
         * without one it is bound to the observation that read it (fresh per authorized run).
         */
        fun versionDigest(source: SourceVersion): String {
            val revision = source.revision
            return if (revision != null) {
                digest(
                    "gallery-version-v1",
                    source.size.toString(),
                    source.mtimeMillis.toString(),
                    "revision",
                    revision,
                )
            } else {
                digest(
                    "gallery-version-v1",
                    source.size.toString(),
                    source.mtimeMillis.toString(),
                    "observation",
                    source.observationId,
                )
            }
        }

        fun featureDigest(version: FeatureVersion): String =
            digest("gallery-feature-v1", version.preprocessing, version.algorithm)
    }
}
