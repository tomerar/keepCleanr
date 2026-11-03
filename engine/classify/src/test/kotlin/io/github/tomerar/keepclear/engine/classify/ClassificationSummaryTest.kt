// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.classify

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ClassificationSummaryTest {
    private val classifier = Classifier()
    private val now = 1_800_000_000_000L
    private val root = "/storage/emulated/0"

    private fun item(rel: String, size: Long, mtime: Long) =
        classifier.classify(ScanItem(KPath.Local("$root/$rel"), "$root/$rel", size, mtime), now = now)

    private val items = listOf(
        item("Pictures/Screenshots/a.png", 100, 1_678_000_000_000), // 2023-03
        item("Pictures/Screenshots/b.png", 200, 1_678_100_000_000), // 2023-03
        item("Pictures/Screenshots/c.png", 300, 1_700_000_000_000), // 2023-11
        item("DCIM/Camera/IMG.jpg", 5_000, 1_700_000_000_000),
        item("DCIM/.thumbnails/t.jpg", 10, 1_600_000_000_000),
        item("Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14", 9_999, 1_700_000_000_000),
    )
    private val summary = ClassificationSummary.of(items)

    @Test
    fun `cleaned items leave every total at once (owner brief H2 item 6)`() {
        val after = summary.without(setOf("$root/DCIM/.thumbnails/t.jpg", "$root/Pictures/Screenshots/b.png"))
        after.total(Category.SAFE) shouldBe Total()
        after.phoneTotal(Category.SAFE) shouldBe Total()
        after.total(Category.REVIEW) shouldBe Total(2, 400)
        after.phoneTotal(Category.REVIEW) shouldBe Total(2, 400)
        after.groups(Category.SAFE) shouldBe emptyList()
        after.group("screenshots", Category.REVIEW)!!.items.map { it.item.path.substringAfterLast('/') } shouldBe
            listOf("c.png", "a.png")
        // Unknown paths and nothing change nothing.
        summary.without(setOf("$root/nope.jpg")) shouldBe summary
        summary.without(emptySet()) shouldBe summary
    }

    @Test
    fun `totals equal the sum of their items`() {
        summary.total(Category.REVIEW) shouldBe Total(3, 600)
        summary.total(Category.MOVE) shouldBe Total(1, 5_000)
        summary.total(Category.SAFE) shouldBe Total(1, 10)
        summary.total(Category.PROTECTED) shouldBe Total(1, 9_999)
        summary.groups.sumOf { it.total.bytes } shouldBe items.sumOf { it.item.size }
    }

    @Test
    fun `Home totals and areas count only the phone's own storage, never a card or USB drive (D5)`() {
        fun at(path: String, size: Long) =
            classifier.classify(ScanItem(KPath.Local(path), path, size, 1_600_000_000_000), now = now)
        val mixed = ClassificationSummary.of(
            items + listOf(
                at("/storage/1234-ABCD/DCIM/.thumbnails/t.jpg", 70),
                at("/storage/1234-ABCD/DCIM/Camera/IMG.jpg", 7_000),
                at("/storage/emulated/999/DCIM/Camera/clone.jpg", 300),
            ),
        )
        mixed.total(Category.SAFE) shouldBe Total(2, 80)
        mixed.phoneTotal(Category.SAFE) shouldBe Total(1, 10)
        mixed.total(Category.MOVE) shouldBe Total(3, 12_300)
        mixed.phoneTotal(Category.MOVE) shouldBe Total(2, 5_300)
        mixed.areas()[ClassificationSummary.Area.CAMERA] shouldBe 5_300
        mixed.areas()[ClassificationSummary.Area.JUNK] shouldBe 10
        PhoneStorage.contains("/sdcard/DCIM/a.jpg") shouldBe true
        PhoneStorage.contains("/storage/emulated/0") shouldBe false
        PhoneStorage.contains("/storage/1234-ABCD/x") shouldBe false
    }

    @Test
    fun `groups are largest first and buckets newest first`() {
        summary.groups.map { it.total.bytes } shouldBe summary.groups.map { it.total.bytes }.sortedDescending()
        val screenshots = summary.group("screenshots", Category.REVIEW)!!
        screenshots.buckets.map { it.first } shouldBe listOf("2023-11", "2023-03")
        screenshots.buckets.last().second.map { it.item.size } shouldBe listOf(200L, 100L)
    }

    @Test
    fun `areas split camera, WhatsApp, junk and other`() {
        val areas = summary.areas()
        areas[ClassificationSummary.Area.CAMERA] shouldBe 5_000
        areas[ClassificationSummary.Area.WHATSAPP] shouldBe 9_999
        areas[ClassificationSummary.Area.JUNK] shouldBe 10
        areas[ClassificationSummary.Area.OTHER] shouldBe 600
    }

    @Test
    fun `month buckets use ASCII digits on every device locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "bn-BD", "he-IL")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                item("Pictures/Screenshots/a.png", 100, 1_678_000_000_000).group.bucket shouldBe "2023-03"
            }
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `other apps' media folders are summed per app, largest first (SAFE-9)`() {
        val c = Classifier()

        fun i(rel: String, size: Long) = c.classify(
            io.github.tomerar.keepclear.engine.scan.ScanItem(
                io.github.tomerar.keepclear.core.storage.KPath.Local("/storage/emulated/0/$rel"),
                "/storage/emulated/0/$rel",
                size,
                1,
            ),
            now = 2,
        )
        val items = listOf(
            i("Android/media/com.spotify.music/d/a.ogg", 300),
            i("Android/media/com.spotify.music/d/b.ogg", 300),
            i("Android/media/org.telegram.messenger/Telegram/v.mp4", 900),
            i("DCIM/Camera/x.jpg", 5),
        )
        AppContent.byApp(items).map { it.first to it.second.bytes } shouldBe
            listOf("org.telegram.messenger" to 900L, "com.spotify.music" to 600L)
    }

    @Test
    fun `the app's package keeps its case, PackageManager needs it exactly (SAFE-9)`() {
        AppContent.packageOf("/storage/emulated/0/Android/media/com.Example.Player/downloads/a.bin") shouldBe
            "com.Example.Player"
        AppContent.packageOf("/storage/emulated/0/DCIM/a.jpg") shouldBe null
    }
}
