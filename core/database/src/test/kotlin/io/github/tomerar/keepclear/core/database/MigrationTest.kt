// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * MIG-1 harness: every exported schema must be openable and migrate to the current version with no
 * row loss. With only version 1 so far, this checks the schema export exists and the database can be
 * created from it; each new version adds a migrate-and-validate case here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KeepClearDatabase::class.java,
        emptyList(),
        androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `schema 1 is exported and creatable`() {
        File("schemas/${KeepClearDatabase::class.java.name}/1.json").exists() shouldBe true
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL(
                "INSERT INTO scan_item (path, refKind, refValue, size, mtimeMillis, identity, mime, mediaId, " +
                    "isFavorite, isTrashed, source, scanRunId) " +
                    "VALUES ('/a', 'local', '/a', 1, 1, NULL, NULL, NULL, 0, 0, 'FILE_WALK', 1)",
            )
        }
        helper.runMigrationsAndValidate("migration-test", 3, true, *KeepClearDatabase.MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM scan_item").use { c ->
                c.moveToFirst()
                c.getInt(0) shouldBe 1
            }
        }
    }

    @Test
    fun `1 to 2 adds the trash table and keeps scan rows`() {
        helper.createDatabase("m12", 1).use { db ->
            db.execSQL(
                "INSERT INTO scan_item (path, refKind, refValue, size, mtimeMillis, identity, mime, mediaId, " +
                    "isFavorite, isTrashed, source, scanRunId) VALUES ('/b', 'local', '/b', 2, 2, NULL, NULL, NULL, 0, 0, 'FILE_WALK', 1)",
            )
        }
        helper.runMigrationsAndValidate("m12", 3, true, *KeepClearDatabase.MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM scan_item").use { c ->
                c.moveToFirst()
                c.getInt(0) shouldBe 1
            }
            db.query("SELECT COUNT(*) FROM trash_entry").use { c ->
                c.moveToFirst()
                c.getInt(0) shouldBe 0
            }
        }
    }

    @Test
    fun `2 to 3 adds the move tables and keeps trash rows`() {
        helper.createDatabase("m23", 2).use { db ->
            db.execSQL(
                "INSERT INTO trash_entry (id, originalPath, trashedPath, volumeRoot, size, mtimeMillis, sha256, " +
                    "ruleId, reason, deletedAt, expiresAt, deletedAtElapsed, bootId, state, trusted, verifiedCopy) " +
                    "VALUES ('t1', '/o', '/t', '/v', 1, 1, NULL, NULL, 'r', 1, 2, 1, 'b', 'ACTIVE', 1, NULL)",
            )
        }
        helper.runMigrationsAndValidate("m23", 3, true, *KeepClearDatabase.MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM trash_entry").use { c ->
                c.moveToFirst()
                c.getInt(0) shouldBe 1
            }
            db.query("SELECT COUNT(*) FROM move_file").use { c ->
                c.moveToFirst()
                c.getInt(0) shouldBe 0
            }
        }
    }

    @Test
    fun `destructive fallback is never configured`() {
        val source = File("src/main/kotlin/io/github/tomerar/keepclear/core/database/KeepClearDatabase.kt").readText()
        source.contains("fallbackToDestructive" + "Migration(") shouldBe false
    }
}
