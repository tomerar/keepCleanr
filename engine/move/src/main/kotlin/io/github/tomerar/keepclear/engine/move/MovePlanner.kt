// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.backup.TargetCaps
import io.github.tomerar.keepclear.backup.TargetNames
import io.github.tomerar.keepclear.engine.scan.ScanItem
import java.time.Instant
import java.time.ZoneOffset

/**
 * Turns a selection into target paths (MOVE-7) and checks the target before copying (MOVE-5).
 * Layout: `KeepClear/<device>/<source>/<YYYY>/<MM>/<name>`; names sanitised, NFC, collisions get " (2)".
 */
object MovePlanner {
    fun rootRel(deviceName: String) = "KeepClear/${TargetNames.sanitize(deviceName)}"

    fun plan(
        jobId: String,
        items: List<ScanItem>,
        deviceName: String,
        sourceLabel: String,
        caps: TargetCaps,
    ): List<MoveFile> {
        val used = HashSet<String>()
        val base = "${rootRel(deviceName)}/${TargetNames.sanitize(sourceLabel)}"
        return items.sortedBy { it.mtimeMillis }.mapIndexed { index, item ->
            val time = Instant.ofEpochMilli(item.mtimeMillis).atZone(ZoneOffset.UTC)
            // Locale.ROOT: folder names use ASCII digits on every device locale.
            val dir = "$base/%04d/%02d".format(java.util.Locale.ROOT, time.year, time.monthValue)
            val original = item.path.substringAfterLast('/')
            var name = TargetNames.sanitize(original)
            caps.maxPathChars?.let { max ->
                val room = max - dir.length - 1
                if (name.length > room && room > 20) name = TargetNames.fitBytes(name, room)
            }
            var candidate = name
            var n = 2
            val maxChars = caps.maxPathChars?.let { it - dir.length - 1 } ?: Int.MAX_VALUE
            while (!used.add(TargetNames.key("$dir/$candidate", caps.caseSensitive))) {
                candidate = TargetNames.withSuffix(name, n++, maxChars = maxChars)
            }
            MoveFile(
                jobId = jobId,
                index = index,
                source = item.ref,
                sourcePath = item.path,
                sourceSize = item.size,
                sourceMtime = item.mtimeMillis,
                sourceIdentity = item.identity,
                relTarget = "$dir/$candidate",
                nameChanged = candidate != original,
            )
        }
    }

    sealed interface Issue {
        val blocking: Boolean

        data class NotEnoughSpace(val needed: Long, val free: Long) : Issue {
            override val blocking = true
        }

        data class TooLargeForTarget(val files: List<MoveFile>) : Issue {
            override val blocking = false // the rest can still be copied
        }

        data object TargetIsSource : Issue {
            override val blocking = true
        }

        data object BatteryLow : Issue {
            override val blocking = true
        }

        data class NamesChanged(val count: Int) : Issue {
            override val blocking = false
        }

        data object CannotVerify : Issue {
            override val blocking = false // copy allowed, deleting originals is not (MOVE-14)
        }
    }

    /** "primary" for `/storage/emulated/<user>/…`, the UUID for `/storage/<uuid>/…`, else null. */
    fun volumeOf(path: String): String? {
        val segs = path.split('/').filter { it.isNotEmpty() }
        return when {
            segs.size < 2 || segs[0] != "storage" -> null
            segs[1] == "emulated" || segs[1] == "self" -> "primary"
            else -> segs[1]
        }
    }

    suspend fun preflight(
        files: List<MoveFile>,
        target: BackupTarget,
        sourceVolumeIds: Set<String>,
        batteryPercent: Int,
        charging: Boolean,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        // H3: compare volumes, not ids; the source volumes come from the files themselves too.
        val sources = sourceVolumeIds + files.mapNotNull { volumeOf(it.sourcePath) }
        if (target.volumeId != null && target.volumeId in sources) issues += Issue.TargetIsSource
        val tooLarge = target.caps.maxFileSize?.let { max -> files.filter { it.sourceSize > max } }.orEmpty()
        if (tooLarge.isNotEmpty()) issues += Issue.TooLargeForTarget(tooLarge)
        val needed = files.filterNot { it in tooLarge }.sumOf { it.sourceSize }
        val withMargin = needed + needed / 20
        target.freeBytes()?.let { free -> if (free < withMargin) issues += Issue.NotEnoughSpace(withMargin, free) }
        if (batteryPercent < 20 && !charging) issues += Issue.BatteryLow
        files.count { it.nameChanged }.takeIf { it > 0 }?.let { issues += Issue.NamesChanged(it) }
        if (!target.caps.canReadBack) issues += Issue.CannotVerify
        return issues
    }
}
