// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.devcheck

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Device check (test builds only): the facts a run collects, and the plain-text report made from them.
 *
 * The report is meant to be pasted into a chat or an issue, so it carries counts and sizes only: no file paths,
 * no file or folder names, no volume ids. Volumes are "internal", "SD card 1", "USB drive 1"; recycle bins have a
 * fixed label from [OemBins]. Every string that could come from the system (model name, OS build, locale, mount
 * type) goes through [ReportText.clean], and the whole text gets a last pass that removes '/' and control
 * characters, so a path can't slip through even if a fact is filled wrongly.
 */

/** A file count and the bytes they hold. */
data class Count(val files: Long = 0, val bytes: Long = 0) {
    operator fun plus(other: Count) = Count(files + other.files, bytes + other.bytes)
}

enum class Level { PASS, INFO, WARN }

data class AppFacts(val versionName: String, val versionCode: Long, val flavor: String, val buildType: String)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val osBuild: String,
    val release: String,
    val sdk: Int,
    val securityPatch: String,
    val locale: String,
    val rtl: Boolean,
)

data class DisplayInfo(
    val fontScale: Float,
    val densityDpi: Int,
    val widthDp: Int,
    val heightDp: Int,
    val smallestWidthDp: Int,
    val night: Boolean,
)

data class AccessInfo(
    /** null in the `play` build, which never has All files access (ADR 0005). */
    val allFiles: Boolean?,
    /** NONE, PARTIAL or FULL ([io.github.tomerar.keepclear.core.storage.media.MediaAccess]). */
    val media: String,
    val notifications: Boolean,
    val usageAccess: Boolean,
    val grantedFolders: Int,
)

data class ScanFacts(
    val roots: Int,
    val scanMillis: Long,
    val classifyMillis: Long,
    val total: Count,
    val unreadable: Int,
    val volumesLost: Int,
    val complete: Boolean,
    val skippedSymlinks: Int,
    val skippedRestricted: Int,
    /** By category name (SAFE, REVIEW, MOVE, PROTECTED), in [CATEGORY_ORDER]. */
    val categories: Map<String, Count>,
    /** What the scan found on the phone's own (primary) storage. */
    val primary: Count,
    /** Items in cloned-app storage (MSG-3). */
    val cloned: Count,
    /** In Android's own trash (MediaStore IS_TRASHED), as Home's Trash screen shows it. */
    val androidTrash: Count,
    /** Files whose name starts with ".trashed-" (Android's trash as seen by walking the folders). */
    val trashedNamed: Count,
    /** Files a FAT32 drive can't hold (over 4 GiB). */
    val over4GiB: Count,
) {
    companion object {
        val CATEGORY_ORDER = listOf("SAFE", "REVIEW", "MOVE", "PROTECTED")
    }
}

data class VolumeFacts(
    /** "internal", "SD card 1", "USB drive 1", "other volume 1"; never a path or a uuid. */
    val label: String,
    val mounted: Boolean,
    val total: Long? = null,
    val available: Long? = null,
    val free: Long? = null,
    /** What Settings › Storage shows for the internal volume (StorageStatsManager); null elsewhere. */
    val settingsTotal: Long? = null,
    val settingsFree: Long? = null,
)

enum class CloneState { READABLE, HIDDEN, NOT_VISIBLE, NOT_CHECKED }

data class CloneUser(
    val id: Int,
    val state: CloneState,
    /** Whether Home scans this user's storage (only user 999, through ClonedApps). */
    val scannedByHome: Boolean = false,
    /** Fixed WhatsApp folder label to what it holds; only for a readable user. */
    val whatsApp: Map<String, Count> = emptyMap(),
)

data class CloneFacts(
    val myUser: Int,
    val profiles: Int,
    /** Whether the list of users' storage folders could be read at all. */
    val listedUsers: Boolean,
    val mainWhatsApp: Map<String, Count>,
    val users: List<CloneUser>,
)

enum class BinState { PRESENT, ABSENT, UNREADABLE }

data class BinFacts(val volume: String, val bin: String, val state: BinState, val count: Count = Count())

data class DriveFacts(
    val label: String,
    /** A [io.github.tomerar.keepclear.backup.saf.DriveFormat] name. */
    val format: String,
    /** The type in the mount table, e.g. "exfat" or "sdfat"; null when no line was found. */
    val mountType: String?,
    val maxFileSize: Long?,
    val total: Long?,
    val available: Long?,
)

data class OwnState(
    val testMode: Boolean,
    /** Trash entries by state (ACTIVE, …). */
    val trash: Map<String, Count>,
    /** MOVE jobs by state (READY, RUNNING, PAUSED, DONE). */
    val jobs: Map<String, Int>,
    val pauseReasons: Map<String, Int>,
    val kept: Int,
    val junkFolders: Int,
    val networkDrive: Boolean,
    val reminder: Boolean,
)

/** One section that could not be checked: only the exception's class name, never its message (paths). */
data class Failure(val section: String, val kind: String)

data class DeviceFacts(
    val takenAtMillis: Long,
    val zone: String,
    val app: AppFacts,
    val device: DeviceInfo,
    val display: DisplayInfo,
    val access: AccessInfo,
    /** null when there was nothing KeepClear may scan (no access). */
    val scan: ScanFacts?,
    val volumes: List<VolumeFacts>,
    val clones: CloneFacts?,
    val bins: List<BinFacts>,
    val drives: List<DriveFacts>,
    val own: OwnState?,
    val failures: List<Failure> = emptyList(),
)

/** Makes system strings safe for the report. */
object ReportText {
    private const val MAX = 64

    /** Strips '/', '\', control and formatting characters (bidi marks too) and collapses spaces. */
    fun clean(value: String?): String {
        if (value == null) return "unknown"
        val kept = value.map { c ->
            when {
                c == '/' || c == '\\' -> ' '
                Character.isISOControl(c) -> ' '
                Character.getType(c) == Character.FORMAT.toInt() -> ' '
                else -> c
            }
        }.joinToString("")
        val collapsed = kept.replace(Regex("\\s+"), " ").trim()
        return collapsed.take(MAX).ifEmpty { "unknown" }
    }

    /** Only letters, digits, '-', '_' and '.' (mount types, enum names). */
    fun token(value: String?): String = value?.filter { it.isLetterOrDigit() || it in "-_." }?.take(MAX)
        ?.ifEmpty { null } ?: "unknown"

    /** The last pass over the whole report: no '/' and no control characters other than new lines. */
    fun finalPass(text: String): String = text.map { c ->
        when {
            c == '\n' -> c
            c == '/' || c == '\\' -> ' '
            Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt() -> ' '
            else -> c
        }
    }.joinToString("")
}

/** Renders [DeviceFacts] as the shareable text report (English: it is a report for the developer). */
object DeviceReport {
    const val FOOTER = "No file paths are included."

    /** D3-1: a full scan should finish within this. */
    const val SCAN_LIMIT_MILLIS = 60_000L

    private val us = Locale.US

    fun render(f: DeviceFacts): String {
        val out = StringBuilder()

        fun line(text: String) = out.append(text).append('\n')

        fun item(level: Level?, text: String) = line(if (level == null) "  $text" else "[$level] $text")

        fun section(title: String) {
            out.append('\n')
            line("== $title ==")
        }

        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx", us)
            .format(Instant.ofEpochMilli(f.takenAtMillis).atZone(zoneOf(f.zone)))
        line("KeepClear device check")
        line(
            "App: ${ReportText.clean(f.app.versionName)} (${f.app.versionCode}), " +
                "${ReportText.token(f.app.flavor)} ${ReportText.token(f.app.buildType)}",
        )
        line("Taken: $time")
        line(
            "Device: ${ReportText.clean(f.device.manufacturer)} ${ReportText.clean(f.device.model)}, " +
                "Android ${ReportText.clean(f.device.release)} (SDK ${f.device.sdk})",
        )
        line("Levels: PASS = as expected, WARN = look at this, INFO = a number to compare.")

        section("Device (D0-1)")
        item(Level.INFO, "Manufacturer: ${ReportText.clean(f.device.manufacturer)}")
        item(Level.INFO, "Model: ${ReportText.clean(f.device.model)}")
        item(Level.INFO, "OS build: ${ReportText.clean(f.device.osBuild)}")
        item(Level.INFO, "Android ${ReportText.clean(f.device.release)}, SDK ${f.device.sdk}")
        item(Level.INFO, "Security patch: ${ReportText.clean(f.device.securityPatch)}")
        item(
            Level.INFO,
            "KeepClear ${ReportText.clean(f.app.versionName)}, version code ${f.app.versionCode}, " +
                "build ${ReportText.token(f.app.flavor)} ${ReportText.token(f.app.buildType)}",
        )
        item(Level.INFO, "Language: ${ReportText.token(f.device.locale)}")
        item(
            Level.INFO,
            "Layout direction: ${if (f.device.rtl) "right to left" else "left to right"}" +
                " (Hebrew should be right to left)",
        )

        section("Display (D18-1, D0-1)")
        val d = f.display
        val fontLevel = if (d.fontScale >= 1.3f) Level.WARN else Level.INFO
        item(
            fontLevel,
            "Font scale: ${"%.2f".format(us, d.fontScale)}" +
                if (d.fontScale >= 1.3f) " (large: check no number is cut off, D18-1)" else "",
        )
        item(Level.INFO, "Density: ${d.densityDpi} dpi")
        item(Level.INFO, "Screen: ${d.widthDp} x ${d.heightDp} dp, smallest width ${d.smallestWidthDp} dp")
        item(Level.INFO, "Window size class: ${sizeClass(d.widthDp)}")
        item(Level.INFO, "Dark mode: ${onOff(d.night)} (KeepClear should follow it)")

        section("Permissions and access (D3-2, D12-1, D13-1)")
        val a = f.access
        when (a.allFiles) {
            null -> item(Level.INFO, "All files access: not in this build (play)")
            true -> item(Level.PASS, "All files access: on")
            false -> item(Level.WARN, "All files access: off (the scan sees photos and videos only)")
        }
        item(
            when (a.media) {
                "FULL" -> Level.PASS
                "PARTIAL" -> Level.WARN
                else -> if (a.allFiles == true) Level.INFO else Level.WARN
            },
            "Photos and videos access: ${mediaText(a.media)}",
        )
        item(Level.INFO, "Notifications allowed: ${yesNo(a.notifications)}")
        item(Level.INFO, "Usage access (app sizes, D13-1): ${yesNo(a.usageAccess)}")
        item(Level.INFO, "Folders granted through Android's picker: ${a.grantedFolders}")

        section("Scan (D3-1)")
        val s = f.scan
        if (s == null) {
            item(Level.WARN, "Not run: KeepClear has no access to scan anything yet")
        } else {
            item(
                if (s.scanMillis <= SCAN_LIMIT_MILLIS) Level.PASS else Level.WARN,
                "Scan time: ${seconds(s.scanMillis)} for ${s.roots} storage areas (D3-1: 60 s or less)",
            )
            item(Level.INFO, "Sorting time (same rules as Home): ${seconds(s.classifyMillis)}")
            item(Level.INFO, "Found: ${count(s.total)}")
            item(if (s.complete) Level.PASS else Level.WARN, "Complete: ${yesNo(s.complete)}")
            item(if (s.unreadable == 0) Level.PASS else Level.WARN, "Folders that could not be read: ${s.unreadable}")
            item(if (s.volumesLost == 0) Level.PASS else Level.WARN, "Storage lost during the scan: ${s.volumesLost}")
            item(
                Level.INFO,
                "Links skipped: ${s.skippedSymlinks}; other apps' private folders skipped: ${s.skippedRestricted}",
            )
            ScanFacts.CATEGORY_ORDER.forEach { name ->
                item(Level.INFO, "${categoryText(name)}: ${count(s.categories[name] ?: Count())}")
            }
            s.categories.keys.filter { it !in ScanFacts.CATEGORY_ORDER }.sorted().forEach { name ->
                item(Level.INFO, "${ReportText.token(name)}: ${count(s.categories.getValue(name))}")
            }
            item(Level.INFO, "In Android's own trash: ${count(s.androidTrash)}")
            item(Level.INFO, "Files named .trashed- (Android's trash, seen in folders): ${count(s.trashedNamed)}")
            item(Level.INFO, "In cloned-app storage: ${count(s.cloned)}")
            item(Level.INFO, "Files over 4 GB (too big for a FAT32 drive): ${count(s.over4GiB)}")
            val internal = f.volumes.firstOrNull { it.label == INTERNAL }
            val used = internal?.let { v -> v.total?.let { t -> v.available?.let { t - it } } }
            item(Level.INFO, "Scanned on internal storage: ${bytes(s.primary.bytes)}")
            if (used != null && used > 0) {
                val pct = 100.0 * s.primary.bytes / used
                item(
                    Level.INFO,
                    "Internal used (total minus available): ${bytes(used)}; scanned is ${"%.1f".format(us, pct)}% " +
                        "of used. Lower is expected: apps and their private data can't be seen.",
                )
            }
        }

        section("Storage (D3-1)")
        if (f.volumes.isEmpty()) item(Level.WARN, "No storage volumes found")
        f.volumes.forEach { v ->
            val label = ReportText.clean(v.label)
            if (!v.mounted) {
                item(Level.INFO, "$label: present, not mounted")
                return@forEach
            }
            val total = v.total
            val available = v.available
            if (total == null || available == null) {
                item(Level.WARN, "$label: size could not be read")
            } else {
                item(
                    Level.INFO,
                    "$label: total ${bytes(total)}, available ${bytes(available)}, used ${bytes(total - available)}",
                )
                v.free?.let { item(null, "free including space reserved for Android: ${bytes(it)}") }
            }
            if (v.settingsTotal != null && v.settingsFree != null) {
                item(
                    Level.INFO,
                    "$label as Settings › Storage counts it: total ${bytes(v.settingsTotal)}, " +
                        "free ${bytes(v.settingsFree)}, used ${bytes(v.settingsTotal - v.settingsFree)} " +
                        "(compare with Settings; Home should be within 1%)",
                )
            }
        }

        section("Cloned apps (D3-3, D16a-1)")
        val c = f.clones
        if (c == null) {
            item(Level.WARN, "Could not be checked")
        } else {
            item(Level.INFO, "KeepClear runs as Android user ${c.myUser}; profiles it can see: ${c.profiles}")
            item(
                Level.INFO,
                "List of users' storage: ${if (c.listedUsers) "readable" else "not readable (only known numbers tried)"}",
            )
            whatsApp("Main user", c.mainWhatsApp, ::item)
            c.users.forEach { u ->
                val home = if (u.scannedByHome) " (Home scans it)" else ""
                when (u.state) {
                    CloneState.READABLE -> item(
                        if (u.id == CLONE_USER) Level.PASS else Level.INFO,
                        "User ${u.id}: present and readable$home",
                    )

                    CloneState.HIDDEN -> item(
                        Level.WARN,
                        "User ${u.id}: present but not readable" +
                            if (u.id == CLONE_USER) " (Home shows the Cloned apps card)" else "",
                    )

                    CloneState.NOT_VISIBLE -> item(Level.INFO, "User ${u.id}: not visible")

                    CloneState.NOT_CHECKED -> item(Level.INFO, "User ${u.id}: not checked (needs All files access)")
                }
                if (u.state == CloneState.READABLE) whatsApp("User ${u.id}", u.whatsApp, ::item)
            }
        }

        section("Other apps' recycle bins (D7-1)")
        if (f.bins.isEmpty()) item(Level.INFO, "Not checked (needs All files access)")
        f.bins.forEach { b ->
            val where = "${ReportText.clean(b.volume)}, ${ReportText.clean(b.bin)}"
            when (b.state) {
                BinState.PRESENT -> item(Level.INFO, "$where: present, ${count(b.count)}")
                BinState.ABSENT -> item(Level.INFO, "$where: not found")
                BinState.UNREADABLE -> item(Level.WARN, "$where: present but not readable")
            }
        }
        item(null, "Bins inside other apps' private folders (e.g. OnePlus Gallery) can't be seen by any app.")

        section("Removable drives (D10-1)")
        if (f.drives.isEmpty()) item(Level.INFO, "No drive attached")
        f.drives.forEach { dr ->
            val format = ReportText.token(dr.format)
            val seen = dr.mountType?.let { " (mount type ${ReportText.token(it)})" } ?: " (no mount line found)"
            val level = when {
                format == "UNKNOWN" -> Level.WARN
                dr.maxFileSize == null -> Level.PASS
                else -> Level.INFO
            }
            item(level, "${ReportText.clean(dr.label)}: format $format$seen")
            item(
                null,
                when {
                    format == "UNKNOWN" -> "Format unknown: treated like FAT32, files over 4 GB are not copied"
                    dr.maxFileSize != null -> "Files over 4 GB can't be copied to this drive (FAT32 limit)"
                    else -> "No 4 GB limit: big videos can be copied"
                },
            )
            if (dr.total != null && dr.available != null) {
                item(null, "total ${bytes(dr.total)}, available ${bytes(dr.available)}")
            }
        }

        section("KeepClear's own state")
        val o = f.own
        if (o == null) {
            item(Level.WARN, "Could not be read")
        } else {
            item(Level.INFO, "Trial mode: ${onOff(o.testMode)}")
            if (o.trash.isEmpty()) item(Level.INFO, "KeepClear's trash: empty")
            o.trash.toSortedMap().forEach { (state, n) ->
                item(Level.INFO, "KeepClear's trash, ${ReportText.token(state)}: ${count(n)}")
            }
            if (o.jobs.isEmpty()) item(Level.INFO, "Backup jobs: none")
            o.jobs.toSortedMap().forEach { (state, n) ->
                item(Level.INFO, "Backup jobs ${ReportText.token(state)}: $n")
            }
            o.pauseReasons.toSortedMap().forEach { (reason, n) ->
                item(null, "paused because ${ReportText.token(reason)}: $n")
            }
            item(Level.INFO, "Kept by you: ${o.kept}; folders always treated as junk: ${o.junkFolders}")
            item(Level.INFO, "Network drive set up: ${yesNo(o.networkDrive)}")
            item(Level.INFO, "Space reminder: ${onOff(o.reminder)}")
        }

        if (f.failures.isNotEmpty()) {
            section("Could not check")
            f.failures.forEach { item(Level.WARN, "${ReportText.clean(it.section)}: ${ReportText.token(it.kind)}") }
        }

        out.append('\n')
        line(FOOTER)
        return ReportText.finalPass(out.toString())
    }

    private fun whatsApp(who: String, folders: Map<String, Count>, item: (Level?, String) -> Unit) {
        if (folders.isEmpty()) {
            item(Level.INFO, "$who, WhatsApp folders: none found")
            return
        }
        folders.forEach { (label, n) -> item(Level.INFO, "$who, ${ReportText.clean(label)}: ${count(n)}") }
    }

    const val INTERNAL = "internal"
    const val CLONE_USER = 999

    private fun zoneOf(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.of("UTC"))

    private fun sizeClass(widthDp: Int) = when {
        widthDp < 600 -> "compact"
        widthDp < 840 -> "medium"
        else -> "expanded"
    }

    private fun mediaText(media: String) = when (media) {
        "FULL" -> "all"
        "PARTIAL" -> "only some (selected photos, or not all kinds)"
        "NONE" -> "none"
        else -> ReportText.token(media)
    }

    private fun categoryText(name: String) = when (name) {
        "SAFE" -> "SAFE (ready to clean)"
        "REVIEW" -> "REVIEW (look first)"
        "MOVE" -> "MOVE (memories, back up first)"
        "PROTECTED" -> "PROTECTED (never touched)"
        else -> ReportText.token(name)
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private fun seconds(millis: Long) = "%.1f s".format(us, millis / 1000.0)

    fun count(c: Count) = "${"%,d".format(us, c.files)} files, ${bytes(c.bytes)}"

    /** Exact bytes and a decimal size like Android Settings (1 GB = 10^9 bytes). */
    fun bytes(b: Long): String {
        val exact = "%,d bytes".format(us, b)
        if (b < 1000) return exact
        val units = listOf("KB", "MB", "GB", "TB")
        var value = b / 1000.0
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        return "$exact (${"%.1f".format(us, value)} ${units[unit]})"
    }
}
