// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.classify.AppInfo
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * F3: a phone shaped like the owner's (owner review of 2026-11-02): about 7.9 GB that can be cleaned safely, 2,324
 * screenshots, 526 AI images and 81 big videos to choose from, and the camera roll to back up, on a 128 GB phone with
 * 16.8 GB free. The items go through the real [Classifier], so every screen shows what the app would show for such a
 * phone. Used by the screenshot tests and the store screenshots; sizes are spread so the totals come out exact.
 */
object OwnerPhone {
    /**
     * 2026-11-01 12:00 UTC, the owner's review week; every date on screen is worked out from this, so the screenshots
     * never change by themselves. Design QA item 7: every file, trash entry and backup in the fixture is older than
     * this clock, so no screen shows a month that hasn't happened yet ([OwnerPhoneClockTest]).
     */
    const val NOW = 1_793_534_400_000L

    /** H1c: 14 July 2021, 18:12 UTC and 4 March 2024, noon UTC (the trashed camera files' and video's own dates). */
    const val JULY_2021 = 1_626_286_323_000L
    const val MARCH_2024 = 1_709_553_600_000L
    const val DAY = 86_400_000L
    const val TOTAL_BYTES = 128_000_000_000L
    const val FREE_BYTES = 16_800_000_000L
    private const val ROOT = "/storage/emulated/0"

    private val day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    private val time = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC)
    private val dash = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    /** One group of files: [count] files totalling exactly [bytes], newest [newest] days ago, one every [every] h. */
    private class Spec(
        val count: Int,
        val bytes: Long,
        val newest: Int,
        val everyHours: Long,
        val name: (index: Int, mtime: Long) -> String,
    )

    private fun stamp(t: Long) = Instant.ofEpochMilli(t).let { day.format(it) + "_" + time.format(it) }

    private val specs = listOf(
        // SAFE: 7.9 GB.
        Spec(96, 3_000_000_000, 45, 30) { i, _ -> "Pictures/.trashed-1735${100_000 + i}-VID_${2024_0300 + i}.mp4" },
        Spec(640, 2_100_000_000, 2, 9) { i, _ ->
            "Android/media/com.whatsapp/WhatsApp/Media/.Statuses/${"%032x".format(i * 7_919L)}.mp4"
        },
        Spec(4_210, 1_400_000_000, 1, 2) { i, _ -> "DCIM/.thumbnails/${1_700_000_000 + i}.jpg" },
        Spec(37, 1_400_000_000, 20, 300) { i, _ -> "Download/update_$i.tmp" },
        // REVIEW: screenshots, AI images, big videos.
        Spec(2_324, 2_900_000_000, 1, 14) { _, t -> "Pictures/Screenshots/Screenshot_${stamp(t)}.png" },
        Spec(526, 1_300_000_000, 4, 60) { _, t ->
            "Pictures/ChatGPT/ChatGPT Image ${dash.format(Instant.ofEpochMilli(t))}.png"
        },
        Spec(81, 15_400_000_000, 6, 260) { _, t -> "Movies/VID_${stamp(t)}.mp4" },
        // MOVE: the camera roll and photos received in WhatsApp, 31.9 GB.
        Spec(6_830, 31_000_000_000, 1, 5) { i, t ->
            if (i % 17 == 0) "DCIM/Camera/VID_${stamp(t)}.mp4" else "DCIM/Camera/IMG_${stamp(t)}.jpg"
        },
        Spec(3_120, 900_000_000, 1, 11) { i, t ->
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-${day.format(Instant.ofEpochMilli(t))}" +
                "-WA${"%04d".format(i % 10_000)}.jpg"
        },
    )

    val items: List<ClassifiedItem> by lazy {
        val classifier = Classifier()
        specs.flatMap { spec ->
            val base = spec.bytes / spec.count
            val extra = spec.bytes % spec.count
            (0 until spec.count).map { i ->
                val mtime = NOW - spec.newest * DAY - i * spec.everyHours * 3_600_000L
                val size = base + if (i < extra) 1 else 0
                val path = "$ROOT/${spec.name(i, mtime)}"
                classifier.classify(ScanItem(KPath.Local(path), path, size, mtime), now = NOW)
            }
        }
    }

    val summary: ClassificationSummary by lazy { ClassificationSummary.of(items) }

    /** Home after the scan. */
    val home: HomeUiState by lazy {
        HomeUiState(
            totalBytes = TOTAL_BYTES,
            freeBytes = FREE_BYTES,
            mediaAccess = MediaAccess.FULL,
            allFiles = true,
            scan = ScanState.Done(files = items.size, bytes = items.sumOf { it.item.size }, complete = true),
            summary = summary,
        )
    }

    /** Home before any access: the phone's space only. */
    val beforeAccess = HomeUiState(totalBytes = TOTAL_BYTES, freeBytes = FREE_BYTES)

    private fun trashEntry(
        id: String,
        rel: String,
        size: Long,
        ruleId: String?,
        daysAgo: Int,
        copy: String? = null,
        // H1c: the file's own date, which the Trash grid sorts by month.
        mtime: Long = NOW - 400 * DAY,
    ) =
        TrashEntry(
            id = id,
            originalPath = "$ROOT/$rel",
            trashedPath = "$ROOT/.KeepClearTrash/${day.format(Instant.ofEpochMilli(NOW - daysAgo * DAY))}/$id/" +
                rel.substringAfterLast('/'),
            volumeRoot = ROOT,
            size = size,
            mtimeMillis = mtime,
            ruleId = ruleId,
            reason = if (copy != null) "MOVE_DELETE_ORIGINAL" else "TRASH",
            deletedAt = NOW - daysAgo * DAY,
            expiresAt = NOW + (30 - daysAgo) * DAY,
            deletedAtElapsed = 0,
            bootId = "b",
            state = TrashState.ACTIVE,
            verifiedCopy = copy,
        )

    /** The trash a week after cleaning: junk, a few screenshots, and camera originals with a checked copy. */
    val trash: TrashUi by lazy {
        val entries = listOf(
            trashEntry("t1", "Download/update_3.tmp", 37_837_838, "temp_files", 2, mtime = NOW - 20 * DAY),
            trashEntry(
                "t2",
                "Pictures/.trashed-1735100004-VID_20240304.mp4",
                31_250_000,
                "other_app_bin",
                2,
                mtime = MARCH_2024,
            ),
            trashEntry(
                "s1",
                "Pictures/Screenshots/Screenshot_${stamp(NOW - 9 * DAY)}.png",
                1_250_000,
                "screenshots",
                7,
                mtime = NOW - 9 * DAY,
            ),
            trashEntry(
                "s2",
                "Pictures/Screenshots/Screenshot_${stamp(NOW - 12 * DAY)}.png",
                1_180_000,
                "screenshots",
                7,
                mtime = NOW - 12 * DAY,
            ),
            trashEntry(
                "c1",
                "DCIM/Camera/IMG_20210714_181203.jpg",
                4_480_000,
                "camera",
                1,
                "USB:KeepClear/Phone/IMG",
                mtime = JULY_2021,
            ),
            trashEntry(
                "c2",
                "DCIM/Camera/VID_20210714_181540.mp4",
                182_000_000,
                "camera",
                1,
                "USB:KeepClear/Phone/VID",
                mtime = JULY_2021 + 217_000,
            ),
        )
        TrashUi(entries, releasable = setOf("c1", "c2"))
    }

    /** A few apps for the Apps tab (no real icons in tests: each shows its placeholder). */
    val apps = listOf(
        AppInfo("com.whatsapp", "WhatsApp", 180_000_000, 4_200_000_000, 900_000_000, NOW - DAY, NOW - 900 * DAY),
        AppInfo("com.instagram.android", "Instagram", 310_000_000, 1_900_000_000, 600_000_000, NOW, NOW - 700 * DAY),
        AppInfo("com.spotify.music", "Spotify", 120_000_000, 2_600_000_000, 300_000_000, NOW, NOW - 600 * DAY),
        AppInfo("com.example.oldgame", "Old game", 1_700_000_000, 300_000_000, 0, NOW - 260 * DAY, NOW - 800 * DAY),
        AppInfo("com.example.scanner", "Scanner", 90_000_000, 40_000_000, 0, NOW - 220 * DAY, NOW - 500 * DAY),
    )

    /**
     * Pictures for the thumbnails: a soft two-colour gradient per file (no real photos in the repository). The same
     * file always gets the same picture.
     */
    val thumbnails = object : ThumbnailLoader(null) {
        private val palette = listOf(
            0xFF9DB4C0,
            0xFFC2A878,
            0xFF7D9A84,
            0xFFB59AA8,
            0xFF8E9FBF,
            0xFFD3B8A0,
            0xFF6F8C8A,
            0xFFBFA5C9,
        ).map { it.toInt() }

        override suspend fun load(item: ScanItem, sizePx: Int): Bitmap = gradient(item.path, sizePx)

        // H1c: the Trash grid (files by path) and the restore grid (a drive's documents) get pictures too.
        override suspend fun loadFile(path: String, mtimeMillis: Long, sizePx: Int): Bitmap? =
            if (FileKinds.hasPicture(path.substringAfterLast('/'))) gradient(path, sizePx) else null

        override suspend fun loadDocument(uri: android.net.Uri, sizePx: Int): Bitmap = gradient(uri.toString(), sizePx)

        private fun gradient(path: String, sizePx: Int): Bitmap {
            val h = path.hashCode().let { if (it < 0) -it else it }
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    sizePx.toFloat(),
                    sizePx.toFloat(),
                    palette[h % palette.size],
                    palette[(h / palette.size) % palette.size],
                    Shader.TileMode.CLAMP,
                )
            }
            Canvas(bitmap).drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
            return bitmap
        }
    }

    /**
     * H1c: a backup on a USB drive as the restore grid shows it: camera photos and a few videos over three months,
     * newest first, each with its own date (no real photos: the thumbnails are gradients).
     */
    val restoreCatalog: RestoreStep.Catalog by lazy {
        val months = listOf(Triple(2022, 12, 14), Triple(2021, 8, 9), Triple(2021, 7, 12))
        val files = months.flatMap { (y, m, n) ->
            (1..n).map { d ->
                val t = java.time.LocalDateTime.of(y, m, d, 10 + d % 8, 7 * d % 60).toInstant(java.time.ZoneOffset.UTC)
                    .toEpochMilli()
                val name = if (d % 5 == 0) "VID_${stamp(t)}.mp4" else "IMG_${stamp(t)}.jpg"
                RestoreFile(
                    "Camera/$name",
                    "Camera",
                    "%04d-%02d".format(y, m),
                    t,
                    if (d % 5 == 0) 180_000_000L + d * 1_000_000L else 4_000_000L + d * 37_000L,
                    io.github.tomerar.keepclear.engine.restore.Where.ORIGINAL,
                )
            }
        }
        val groups = files.groupBy { it.month }.map { (month, list) ->
            RestoreGroup("Camera", month, list.sumOf { it.size }, toOriginal = list.size, toRestoreFolder = 0)
        }.sortedBy { it.month }
        RestoreStep.Catalog(
            "KeepClear/Phone",
            groups,
            io.github.tomerar.keepclear.engine.restore.Space(allocatable = 16_800_000_000, total = 128_000_000_000),
            files = files,
            thumbnails = true,
        )
    }
}
