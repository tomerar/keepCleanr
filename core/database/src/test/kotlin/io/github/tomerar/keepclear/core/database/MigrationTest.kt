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
        helper.runMigrationsAndValidate("migration-test", 1, true, *KeepClearDatabase.MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM scan_item").use { c ->
                c.moveToFirst()
                c.getInt(0) shouldBe 1
            }
        }
    }

    @Test
    fun `destructive fallback is never configured`() {
        val source = File("src/main/kotlin/io/github/tomerar/keepclear/core/database/KeepClearDatabase.kt").readText()
        source.contains("fallbackToDestructive" + "Migration(") shouldBe false
    }
}
