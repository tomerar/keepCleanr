// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.testing.fixtures

import io.github.tomerar.keepclear.core.storage.FakeStorageGateway
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.SplittableRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A synthetic storage tree shaped like the owner's OnePlus 10T (requirements §13, measured 28.10.2026),
 * built on [FakeStorageGateway] with virtual file content (tens of GB cost almost no memory).
 *
 * Deterministic for a given [seed]. [scale] shrinks the big file counts (1.0 = real counts) so unit
 * tests stay fast; every area keeps at least one file. Every generated path is recorded under a
 * [Tag], which later milestones use as the expected-classification oracle.
 */
object OwnerDevice {
    const val ROOT = "/storage/emulated/0"
    const val CLONE_ROOT = "/storage/emulated/999"
    const val WA = "$ROOT/Android/media/com.whatsapp/WhatsApp"
    const val W4B = "$ROOT/Android/media/com.whatsapp.w4b/WhatsApp Business"
    const val CLONE_WA = "$CLONE_ROOT/Android/media/com.whatsapp/WhatsApp"
    const val LEGACY_WA = "$ROOT/WhatsApp"

    enum class Tag(val protected: Boolean = false) {
        CAMERA,
        CAMERA_BIG_VIDEO,
        CAMERA_THUMBNAIL,
        SCREENSHOT,
        AI_IMAGE,
        WA_DB(protected = true),
        WA_BACKUP(protected = true),
        WA_RECEIVED,
        WA_SENT_DUPLICATE,
        WA_SENT_UNIQUE,
        WA_PRIVATE,
        WA_STATUS_OLD,
        WA_STATUS_FRESH,
        WA_DOCUMENT,
        W4B_DB(protected = true),
        W4B_MEDIA,
        CLONE_DB(protected = true),
        CLONE_MEDIA,
        LEGACY_WA_DB(protected = true),
        LEGACY_WA_MEDIA,
        MIUI_LEFTOVER,
        CALL_RECORDING,
        TELEGRAM_SAVED,
        TELEGRAM_DOCUMENT,
        TELEGRAM_LEGACY,
        ZIP_FULLY_EXTRACTED,
        ZIP_PARTIALLY_EXTRACTED,
        EXTRACTED_FILE,
        OTHER_APP_RECYCLE_BIN,
        UNICODE_NAME,
        TEMP_FILE,
        LOST_DIR,
        SYMLINK,
        HUGE_DIR_ENTRY,
    }

    class Fixture(val gateway: FakeStorageGateway, val tagged: Map<Tag, List<String>>) {
        fun paths(tag: Tag): List<String> = tagged[tag].orEmpty()

        val protectedPaths: List<String> get() = tagged.filterKeys { it.protected }.values.flatten()
    }

    /** Fixed "now" for age-based rules: the measurement date. */
    val NOW: Long = LocalDateTime.of(2026, 10, 28, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    fun build(seed: Long = 42, scale: Double = 0.01, hugeDirEntries: Int = 0): Fixture =
        Builder(seed, scale, hugeDirEntries).build()

    private class Builder(seed: Long, private val scale: Double, private val hugeDirEntries: Int) {
        private val random = SplittableRandom(seed)
        private val fake = FakeStorageGateway(caseInsensitive = true, volumeRoots = listOf(ROOT, CLONE_ROOT))
        private val tagged = linkedMapOf<Tag, MutableList<String>>()
        private val seeds = HashMap<String, Long>()

        private fun count(real: Int) = maxOf(1, (real * scale).toInt())

        private fun time(fromYear: Int, toYear: Int): LocalDateTime {
            val from = LocalDateTime.of(fromYear, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC)
            val to = minOf(LocalDateTime.of(toYear, 12, 31, 23, 59).toEpochSecond(ZoneOffset.UTC), NOW / 1000)
            return LocalDateTime.ofEpochSecond(from + random.nextLong(to - from), 0, ZoneOffset.UTC)
        }

        private fun LocalDateTime.millis() = toInstant(ZoneOffset.UTC).toEpochMilli()

        private fun fmt(t: LocalDateTime, pattern: String) = t.format(DateTimeFormatter.ofPattern(pattern, Locale.US))

        private fun file(tag: Tag, path: String, size: Long, mtime: Long, contentSeed: Long = random.nextLong()) {
            if (fake.exists(path)) return
            fake.addFile(path, size = size, mtime = mtime, seed = contentSeed)
            seeds[path] = contentSeed
            tagged.getOrPut(tag) { mutableListOf() } += path
        }

        private fun bytes(tag: Tag, path: String, content: ByteArray, mtime: Long) {
            fake.addFile(path, content, mtime)
            tagged.getOrPut(tag) { mutableListOf() } += path
        }

        fun build(): Fixture {
            camera()
            screenshotsAndAi()
            whatsApp()
            whatsAppVariants()
            oldPhone()
            telegram()
            archives()
            misc()
            return Fixture(fake, tagged.mapValues { it.value.toList() })
        }

        private fun camera() {
            val n = count(10_472)
            repeat(n) { i ->
                val t = time(2019, 2026)
                val video = i % 8 == 0
                val name = if (video) "VID_${fmt(t, "yyyyMMdd_HHmmss")}.mp4" else "IMG_${fmt(t, "yyyyMMdd_HHmmss")}.jpg"
                val size = if (video) {
                    20_000_000L + random.nextLong(60_000_000)
                } else {
                    2_000_000L +
                        random.nextLong(4_000_000)
                }
                file(Tag.CAMERA, "$ROOT/DCIM/Camera/$name", size, t.millis())
            }
            repeat(count(81)) {
                val t = time(2019, 2026)
                file(
                    Tag.CAMERA_BIG_VIDEO,
                    "$ROOT/DCIM/Camera/VID_${fmt(t, "yyyyMMdd_HHmmss")}_big.mp4",
                    100_000_001L + random.nextLong(300_000_000),
                    t.millis(),
                )
            }
            repeat(3) {
                file(Tag.CAMERA_THUMBNAIL, "$ROOT/DCIM/.thumbnails/$it.jpg", 20_000, time(2024, 2026).millis())
            }
        }

        private fun screenshotsAndAi() {
            repeat(count(2_324)) {
                val t = time(2021, 2026)
                file(
                    Tag.SCREENSHOT,
                    "$ROOT/Pictures/Screenshots/Screenshot_${fmt(t, "yyyy-MM-dd-HH-mm-ss")}-$it.png",
                    300_000L + random.nextLong(900_000),
                    t.millis(),
                )
            }
            repeat(count(526)) {
                val t = time(2024, 2026)
                file(
                    Tag.AI_IMAGE,
                    "$ROOT/Pictures/ChatGPT/ChatGPT Image ${fmt(t, "MMM d, yyyy, hh_mm_ss a")} $it.png",
                    1_500_000L + random.nextLong(1_500_000),
                    t.millis(),
                )
            }
        }

        /** Scoped WhatsApp (`Android/media/com.whatsapp`), including Sent copies of camera photos. */
        private fun whatsApp() {
            val db = time(2026, 2026).millis()
            file(Tag.WA_DB, "$WA/Databases/msgstore.db.crypt14", 450_000_000, db)
            file(Tag.WA_DB, "$WA/Databases/msgstore-2026-10-27.1.db.crypt14", 449_000_000, db)
            file(Tag.WA_DB, "$WA/Databases/wa.db.crypt14", 2_000_000, db)
            file(Tag.WA_BACKUP, "$WA/Backups/chatsettingsbackup.db.crypt14", 10_000, db)
            file(Tag.WA_BACKUP, "$WA/Backups/stickers.db.crypt14", 300_000, db)
            fake.addFile("$WA/Media/WhatsApp Images/Sent/.nomedia", size = 0)
            fake.addFile("$WA/Media/WhatsApp Images/Private/.nomedia", size = 0)
            repeat(count(9_000)) {
                val t = time(2019, 2026)
                file(
                    Tag.WA_RECEIVED,
                    "$WA/Media/WhatsApp Images/IMG-${fmt(t, "yyyyMMdd")}-WA${"%04d".format(it)}.jpg",
                    100_000L + random.nextLong(400_000),
                    t.millis(),
                )
            }
            repeat(count(900)) {
                val t = time(2019, 2026)
                file(
                    Tag.WA_RECEIVED,
                    "$WA/Media/WhatsApp Video/VID-${fmt(t, "yyyyMMdd")}-WA${"%04d".format(it)}.mp4",
                    2_000_000L + random.nextLong(30_000_000),
                    t.millis(),
                )
            }
            repeat(count(1_500)) {
                val t = time(2019, 2026)
                file(
                    Tag.WA_RECEIVED,
                    "$WA/Media/WhatsApp Voice Notes/${fmt(
                        t,
                        "yyyyww",
                    )}/PTT-${fmt(t, "yyyyMMdd")}-WA${"%04d".format(it)}.opus",
                    20_000L + random.nextLong(400_000),
                    t.millis(),
                )
            }
            // Sent copies: byte-identical twins of camera photos (same content seed and size) → SAFE candidates.
            val cameraPhotos = tagged.getValue(Tag.CAMERA).filter { it.endsWith(".jpg") }
            cameraPhotos.take(maxOf(1, cameraPhotos.size / 4)).forEachIndexed { i, original ->
                val content = fake.contentOf(original) as FakeStorageGateway.Content.Virtual
                val t = time(2019, 2026)
                file(
                    Tag.WA_SENT_DUPLICATE,
                    "$WA/Media/WhatsApp Images/Sent/IMG-${fmt(t, "yyyyMMdd")}-WA9${"%03d".format(i)}.jpg",
                    content.size,
                    t.millis(),
                    contentSeed = content.seed,
                )
            }
            repeat(count(1_000)) {
                val t = time(2019, 2026)
                file(
                    Tag.WA_SENT_UNIQUE,
                    "$WA/Media/WhatsApp Images/Sent/IMG-${fmt(t, "yyyyMMdd")}-WA5${"%03d".format(it)}.jpg",
                    150_000L + random.nextLong(300_000),
                    t.millis(),
                )
            }
            repeat(count(400)) {
                val t = time(2020, 2026)
                file(
                    Tag.WA_PRIVATE,
                    "$WA/Media/WhatsApp Images/Private/IMG-${fmt(t, "yyyyMMdd")}-WA7${"%03d".format(it)}.jpg",
                    200_000L + random.nextLong(3_000_000),
                    t.millis(),
                )
            }
            repeat(5) {
                file(Tag.WA_STATUS_OLD, "$WA/Media/.Statuses/old$it.jpg", 200_000, NOW - (3 + it) * DAY)
            }
            file(Tag.WA_STATUS_FRESH, "$WA/Media/.Statuses/fresh.mp4", 3_000_000, NOW - HOUR)
            repeat(count(300)) {
                val t = time(2020, 2026)
                file(
                    Tag.WA_DOCUMENT,
                    "$WA/Media/WhatsApp Documents/doc_$it.pdf",
                    100_000L + random.nextLong(2_000_000),
                    t.millis(),
                )
            }
        }

        private fun whatsAppVariants() {
            val t = time(2025, 2026).millis()
            file(Tag.W4B_DB, "$W4B/Databases/msgstore.db.crypt14", 20_000_000, t)
            file(Tag.W4B_DB, "$W4B/Backups/chatsettingsbackup.db.crypt14", 5_000, t)
            repeat(count(500)) {
                file(
                    Tag.W4B_MEDIA,
                    "$W4B/Media/WhatsApp Business Images/IMG-20250101-WA${"%04d".format(it)}.jpg",
                    150_000,
                    time(2024, 2026).millis(),
                )
            }
            file(Tag.CLONE_DB, "$CLONE_WA/Databases/msgstore.db.crypt14", 30_000_000, t)
            repeat(count(300)) {
                file(
                    Tag.CLONE_MEDIA,
                    "$CLONE_WA/Media/WhatsApp Images/IMG-20240101-WA${"%04d".format(it)}.jpg",
                    150_000,
                    time(2023, 2026).millis(),
                )
            }
        }

        private fun oldPhone() {
            file(
                Tag.LEGACY_WA_DB,
                "$LEGACY_WA/Databases/msgstore-2021-12-01.1.db.crypt14",
                300_000_000,
                time(2021, 2021).millis(),
            )
            repeat(count(2_000)) {
                val t = time(2019, 2021)
                file(
                    Tag.LEGACY_WA_MEDIA,
                    "$LEGACY_WA/Media/WhatsApp Images/IMG-${fmt(t, "yyyyMMdd")}-WA${"%04d".format(it)}.jpg",
                    150_000L + random.nextLong(1_000_000),
                    t.millis(),
                )
            }
            repeat(count(1_200)) {
                file(
                    Tag.MIUI_LEFTOVER,
                    "$ROOT/MIUI/ShareMe/received_$it.jpg",
                    500_000L + random.nextLong(3_000_000),
                    time(2019, 2021).millis(),
                )
            }
            repeat(count(400)) {
                val t = time(2019, 2021)
                file(
                    Tag.CALL_RECORDING,
                    "$ROOT/MIUI/sound_recorder/call_rec/Call@+972500000$it(${fmt(t, "yyyyMMddHHmmss")}).mp3",
                    500_000L + random.nextLong(5_000_000),
                    t.millis(),
                )
            }
        }

        private fun telegram() {
            repeat(count(200)) {
                file(Tag.TELEGRAM_SAVED, "$ROOT/Pictures/Telegram/IMG_$it.jpg", 300_000, time(2022, 2026).millis())
            }
            repeat(count(50)) {
                file(Tag.TELEGRAM_SAVED, "$ROOT/Movies/Telegram/VID_$it.mp4", 8_000_000, time(2022, 2026).millis())
            }
            repeat(count(100)) {
                file(Tag.TELEGRAM_DOCUMENT, "$ROOT/Download/Telegram/file_$it.pdf", 400_000, time(2022, 2026).millis())
            }
            repeat(count(100)) {
                file(Tag.TELEGRAM_LEGACY, "$ROOT/Telegram/Telegram Images/$it.jpg", 200_000, time(2019, 2020).millis())
            }
        }

        /** Real zip bytes, so M7 can read central directories. */
        private fun archives() {
            val t = time(2024, 2025).millis()
            val members = (1..5).associate { "photo_$it.jpg" to ByteArray(10_000 + it) { b -> (b * it).toByte() } }
            bytes(Tag.ZIP_FULLY_EXTRACTED, "$ROOT/Download/trip.zip", zip(members), t)
            members.forEach { (name, data) -> bytes(Tag.EXTRACTED_FILE, "$ROOT/Download/trip/$name", data, t) }

            val more = (1..4).associate { "scan_$it.pdf" to ByteArray(5_000 + it) { b -> (b + it).toByte() } }
            bytes(Tag.ZIP_PARTIALLY_EXTRACTED, "$ROOT/Download/scans.zip", zip(more), t)
            more.entries.take(2).forEach { (name, data) ->
                bytes(Tag.EXTRACTED_FILE, "$ROOT/Download/scans/$name", data, t)
            }
        }

        private fun misc() {
            repeat(count(800)) {
                file(
                    Tag.OTHER_APP_RECYCLE_BIN,
                    "$ROOT/.FileManagerRecycler/deleted_$it",
                    500_000L + random.nextLong(9_000_000),
                    time(2026, 2026).millis(),
                )
            }
            val t = time(2025, 2026).millis()
            file(Tag.UNICODE_NAME, "$ROOT/Documents/תמונה של אמא 🎂.jpg", 1_000_000, t)
            file(Tag.UNICODE_NAME, "$ROOT/Documents/été.pdf", 50_000, t) // NFD
            file(Tag.UNICODE_NAME, "$ROOT/Documents/חשבונית‏ 2025.pdf", 60_000, t) // RLM inside
            file(Tag.TEMP_FILE, "$ROOT/Download/upload.tmp", 4_000, t)
            file(Tag.LOST_DIR, "$ROOT/LOST.DIR/1234", 70_000, t)
            fake.addSymlink("$ROOT/Download/loop", "$ROOT/Download")
            tagged.getOrPut(Tag.SYMLINK) { mutableListOf() } += "$ROOT/Download/loop"
            repeat(hugeDirEntries) {
                fake.addFile("$ROOT/Pictures/.thumbnails/t$it.jpg", size = 1_000, seed = it.toLong())
            }
            if (hugeDirEntries >
                0
            ) {
                tagged.getOrPut(Tag.HUGE_DIR_ENTRY) { mutableListOf() } += "$ROOT/Pictures/.thumbnails"
            }
        }

        private fun zip(members: Map<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                members.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }
    }

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
}
