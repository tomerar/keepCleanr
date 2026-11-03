// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Owner decisions P2 and P4 (2026-11-03): the free step's two pre-ticked options **keep** favourites and the last 30
 * days on the phone (they were backed up like everything else), and the confirmation names WhatsApp media. Tables with
 * positive and negative rows.
 */
class FreePlanTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val cam = "/storage/emulated/0/DCIM/Camera"

    private fun c(name: String, ageDays: Long, favourite: Boolean?, dir: String = cam) =
        FreeCandidate("$dir/$name", size = 1_000, mtime = now - ageDays * day, favourite = favourite)

    /** Candidate → freed with (keep favourites, keep recent) = (on,on), (on,off), (off,on), (off,off). */
    private val table = listOf(
        c("old.jpg", 400, false) to listOf(true, true, true, true),
        c("fav-old.jpg", 400, true) to listOf(false, false, true, true),
        c("recent.jpg", 3, false) to listOf(false, true, false, true),
        c("day29.jpg", 29, false) to listOf(false, true, false, true),
        c("day30.jpg", 30, false) to listOf(true, true, true, true),
        c("fav-recent.jpg", 3, true) to listOf(false, false, false, true),
        // Not in the latest scan: counts as a favourite (C119).
        c("unknown.jpg", 400, null) to listOf(false, false, true, true),
    )
    private val options = listOf(
        FreeOptions(true, true),
        FreeOptions(true, false),
        FreeOptions(false, true),
        FreeOptions(false, false),
    )

    @Test
    fun `the keep options leave exactly their files on the phone`() {
        for ((i, o) in options.withIndex()) {
            val plan = FreePlan.of(table.map { it.first }, o, now)
            val expected = table.filter { it.second[i] }.map { it.first.path }.toSet()
            plan.paths shouldBe expected
            plan.bytes shouldBe expected.size * 1_000L
            plan.count shouldBe expected.size
        }
        // Default: both ticked (P2).
        FreeOptions() shouldBe FreeOptions(keepFavourites = true, keepRecent = true)
        val default = FreePlan.of(table.map { it.first }, FreeOptions(), now)
        default.keptFavourites shouldBe 3
        default.keptRecent shouldBe 2
    }

    /** Path → WhatsApp media (P4). */
    private val whatsApp = listOf(
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg" to true,
        "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/x.jpg" to true,
        "/storage/emulated/0/WhatsApp/Media/WhatsApp Video/VID-1.mp4" to true,
        "/storage/emulated/0/whatsapp/Media/a.jpg" to true,
        "/storage/emulated/0/DCIM/Camera/IMG_1.jpg" to false,
        "/storage/emulated/0/Pictures/WhatsApp-export.jpg" to false,
        "/storage/emulated/0/Telegram/Telegram Images/a.jpg" to false,
    )

    @Test
    fun `WhatsApp media is recognised by its folder`() {
        for ((path, expected) in whatsApp) FreePlan.isWhatsAppMedia(path) shouldBe expected
    }

    @Test
    fun `the plan says WhatsApp only when WhatsApp media is actually freed`() {
        val wa = c("IMG-1.jpg", 400, false, dir = "/storage/emulated/0/WhatsApp/Media/WhatsApp Images")
        val waFav = c("IMG-2.jpg", 400, true, dir = "/storage/emulated/0/WhatsApp/Media/WhatsApp Images")
        FreePlan.of(listOf(c("a.jpg", 400, false), wa), FreeOptions(), now).hasWhatsApp shouldBe true
        FreePlan.of(listOf(c("a.jpg", 400, false)), FreeOptions(), now).hasWhatsApp shouldBe false
        // A WhatsApp favourite kept on the phone isn't freed, so it isn't mentioned.
        FreePlan.of(listOf(c("a.jpg", 400, false), waFav), FreeOptions(), now).hasWhatsApp shouldBe false
        FreePlan.of(listOf(waFav), FreeOptions(keepFavourites = false), now).hasWhatsApp shouldBe true
    }
}
