// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.classify

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.scan.ScanEvent
import io.github.tomerar.keepclear.engine.scan.Scanner
import io.github.tomerar.keepclear.testing.fixtures.OwnerDevice
import io.github.tomerar.keepclear.testing.fixtures.OwnerDevice.Tag
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M4 DoD: classify the owner-shaped tree and compare every path with the category its fixture tag
 * implies (the oracle), then compare category totals with a committed snapshot (MIG-3).
 */
class ClassifierOracleTest {
    private val expected: Map<Tag, Pair<Category, String?>> = mapOf(
        Tag.CAMERA to (Category.MOVE to "camera"),
        Tag.CAMERA_BIG_VIDEO to (Category.MOVE to "camera"),
        Tag.CAMERA_THUMBNAIL to (Category.SAFE to "thumbnails"),
        Tag.SCREENSHOT to (Category.REVIEW to "screenshots"),
        Tag.AI_IMAGE to (Category.REVIEW to "ai_images"),
        Tag.WA_DB to (Category.PROTECTED to "protected"),
        Tag.WA_BACKUP to (Category.PROTECTED to "protected"),
        Tag.WA_RECEIVED to (Category.MOVE to "wa_received"),
        Tag.WA_SENT_DUPLICATE to (Category.SAFE to "wa_sent_copy"),
        Tag.WA_SENT_UNIQUE to (Category.REVIEW to "wa_sent"),
        Tag.WA_PRIVATE to (Category.REVIEW to "wa_private"),
        Tag.WA_STATUS_OLD to (Category.SAFE to "wa_status_old"),
        Tag.WA_STATUS_FRESH to (Category.REVIEW to "wa_status_fresh"),
        Tag.WA_DOCUMENT to (Category.REVIEW to "wa_other"),
        Tag.W4B_DB to (Category.PROTECTED to "protected"),
        Tag.W4B_MEDIA to (Category.MOVE to "wa_received"),
        Tag.CLONE_DB to (Category.PROTECTED to "protected"),
        Tag.CLONE_MEDIA to (Category.MOVE to "wa_received"),
        Tag.LEGACY_WA_DB to (Category.PROTECTED to "protected"),
        Tag.LEGACY_WA_MEDIA to (Category.REVIEW to "legacy_wa"),
        Tag.MIUI_LEFTOVER to (Category.REVIEW to "old_phone"),
        Tag.CALL_RECORDING to (Category.REVIEW to "call_recordings"),
        Tag.TELEGRAM_SAVED to (Category.MOVE to "telegram_saved"),
        Tag.TELEGRAM_DOCUMENT to (Category.REVIEW to "telegram_docs"),
        Tag.TELEGRAM_LEGACY to (Category.REVIEW to "telegram_legacy"),
        Tag.ZIP_FULLY_EXTRACTED to (Category.SAFE to "zip_extracted"),
        Tag.ZIP_PARTIALLY_EXTRACTED to (Category.REVIEW to "zip_partial"),
        Tag.EXTRACTED_FILE to (Category.REVIEW to null),
        Tag.OTHER_APP_RECYCLE_BIN to (Category.SAFE to "other_app_bin"),
        Tag.UNICODE_NAME to (Category.REVIEW to "unknown"),
        Tag.TEMP_FILE to (Category.SAFE to "temp_files"),
        Tag.LOST_DIR to (Category.SAFE to "lost_dir"),
    )

    private val fixture = OwnerDevice.build(seed = 42, scale = 0.01)
    private val classifier = Classifier()

    private fun factsFor(path: String): Set<Fact> = buildSet {
        if (path in fixture.paths(Tag.WA_SENT_DUPLICATE)) add(Fact.HAS_TWIN_OUTSIDE_MESSENGER)
        if (path in fixture.paths(Tag.ZIP_FULLY_EXTRACTED)) add(Fact.ARCHIVE_FULLY_EXTRACTED)
        if (path in fixture.paths(Tag.ZIP_PARTIALLY_EXTRACTED)) add(Fact.ARCHIVE_PARTIALLY_EXTRACTED)
    }

    private val classified: Map<String, ClassifiedItem> = runBlocking {
        Scanner(fixture.gateway)
            .scan(listOf(KPath.Local(OwnerDevice.ROOT), KPath.Local(OwnerDevice.CLONE_ROOT)))
            .filterIsInstance<ScanEvent.Batch>().toList().flatMap { it.items }
            .associate { it.path to classifier.classify(it, factsFor(it.path), OwnerDevice.NOW) }
    }

    @Test
    fun `every tagged path gets the category its tag implies`() {
        val mismatches = mutableListOf<String>()
        for ((tag, paths) in fixture.tagged) {
            val (category, rule) = expected[tag] ?: continue
            for (path in paths) {
                val c = classified[path] ?: continue
                val ok = (c.category == category || (category == Category.SAFE && c.keepSignal && c.category == Category.REVIEW)) &&
                    (rule == null || c.ruleId == rule)
                if (!ok) mismatches += "$tag $path -> ${c.category}/${c.ruleId}"
            }
        }
        mismatches shouldBe emptyList()
    }

    @Test
    fun `every expected tag is covered by the oracle`() {
        (Tag.entries.toSet() - expected.keys) shouldBe setOf(Tag.SYMLINK, Tag.HUGE_DIR_ENTRY)
    }

    @Test
    fun `protected paths are never anything but PROTECTED`() {
        fixture.protectedPaths.forEach { classified.getValue(it).category shouldBe Category.PROTECTED }
    }

    @Test
    fun `category totals match the committed snapshot (MIG-3)`() {
        val actual = buildString {
            appendLine("rulesVersion=${Rules.RULES_VERSION}")
            Category.entries.forEach { cat ->
                val items = classified.values.filter { it.category == cat }
                appendLine("$cat files=${items.size} bytes=${items.sumOf { it.item.size }}")
            }
            classified.values.groupBy { it.ruleId }.toSortedMap().forEach { (rule, items) ->
                appendLine("rule $rule files=${items.size} ${items.groupingBy { it.category }.eachCount().toSortedMap()}")
            }
        }
        val file = File("src/test/resources/owner-classification-snapshot.txt")
        if (System.getProperty("updateSnapshots") == "true" || !file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(actual)
        }
        actual shouldBe file.readText()
    }
}
