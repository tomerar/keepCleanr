// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.devcheck

import io.github.tomerar.keepclear.ClonedApps
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.scan.ScanEvent
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.scan.Scanner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList

/*
 * The device check's read-only probes. They only list folders and read sizes through [StorageGateway.list]
 * (never open, write, rename or delete anything; ArchitectureTest checks this file set), and they return counts
 * and fixed labels, never paths. Android-free, so they run on fakes in unit tests.
 */

/** A known recycle-bin folder, relative to a volume root, with the fixed label the report shows. */
data class BinSpec(val label: String, val segments: List<String>)

/**
 * Other apps' recycle bins on shared storage. Seeded from research 03 §8 (SD Maid SE's `db_trash_files.json`,
 * GPL-3.0) plus Samsung's `Android/.Trash` (owner's request; path unverified, research 03 §8 says it is not in
 * SD Maid SE's list). Names are matched ignoring case, like Android's shared storage does, and a folder found
 * under two names is reported once (the first label wins).
 */
object OemBins {
    val ALL = listOf(
        BinSpec("OnePlus and OPPO file manager bin", listOf(".FileManagerRecycler")),
        BinSpec("Files by Google bin", listOf(".FilesByGoogleTrash")),
        BinSpec("Xiaomi Gallery bin", listOf("MIUI", "Gallery", "cloud", ".trashBin")),
        BinSpec("Samsung My Files and Gallery bin (unverified)", listOf("Android", ".Trash")),
        BinSpec("Generic bin (.Trash)", listOf(".Trash")),
        BinSpec("Generic bin (.trashfiles)", listOf(".trashfiles")),
        BinSpec("Generic bin (.trashbin)", listOf(".trashbin")),
        BinSpec("Generic bin (.recycle)", listOf(".recycle")),
        BinSpec("Generic bin (.recyclebin)", listOf(".recyclebin")),
        BinSpec("Generic bin (.garbage)", listOf(".garbage")),
    )

    /** Every bin of [specs] on the volume at [root], labelled [volumeLabel]. */
    suspend fun check(
        gateway: StorageGateway,
        volumeLabel: String,
        root: String,
        specs: List<BinSpec> = ALL,
        cap: Long = Tree.DEFAULT_CAP,
    ): List<BinFacts> {
        val seen = HashSet<String>()
        return specs.map { spec ->
            when (val found = Tree.resolve(gateway, KPath.Local(root), spec.segments)) {
                null -> BinFacts(volumeLabel, spec.label, BinState.ABSENT)

                else -> if (!seen.add(found.absolute.lowercase())) {
                    // The same folder under another spelling: already counted above.
                    BinFacts(volumeLabel, spec.label, BinState.ABSENT)
                } else {
                    val tree = Tree.count(gateway, found, cap)
                    BinFacts(
                        volumeLabel,
                        spec.label,
                        when {
                            !tree.readable -> BinState.UNREADABLE
                            tree.complete -> BinState.PRESENT
                            else -> BinState.PRESENT_PARTIAL
                        },
                        tree.count,
                        tree.unreadableFolders,
                    )
                }
            }
        }
    }
}

/** WhatsApp's folders on shared storage, by fixed label (MSG-3: what a cloned WhatsApp would hold). */
object WhatsAppFolders {
    val ALL = listOf(
        "WhatsApp" to listOf("Android", "media", "com.whatsapp"),
        "WhatsApp Business" to listOf("Android", "media", "com.whatsapp.w4b"),
        "WhatsApp (old folder)" to listOf("WhatsApp"),
    )

    /** Only the folders that exist. */
    suspend fun measure(gateway: StorageGateway, root: String, cap: Long = Tree.DEFAULT_CAP): Map<String, Count> {
        val result = linkedMapOf<String, Count>()
        for ((label, segments) in ALL) {
            val found = Tree.resolve(gateway, KPath.Local(root), segments) ?: continue
            result[label] = Tree.count(gateway, found, cap).count
        }
        return result
    }
}

/** Folder walking for the probes: iterative, never follows links, stops at a file cap. */
object Tree {
    const val DEFAULT_CAP = 2_000_000L

    /**
     * [readable] is false when [root] itself can't be listed; [unreadableFolders] counts folders inside it that
     * couldn't be listed (PR #38 review), so a partial count is never shown as a complete one.
     */
    data class Result(
        val count: Count,
        val readable: Boolean,
        val capped: Boolean,
        val unreadableFolders: Int = 0,
    ) {
        val complete: Boolean get() = readable && !capped && unreadableFolders == 0
    }

    /** Finds [segments] under [root], each matched ignoring case; null when any is missing or unreadable. */
    suspend fun resolve(gateway: StorageGateway, root: KPath.Local, segments: List<String>): KPath.Local? {
        var current = root
        for (segment in segments) {
            val children = listOrNull(gateway, current) ?: return null
            val match = children.firstOrNull { it.isDirectory && it.name == segment }
                ?: children.firstOrNull { it.isDirectory && it.name.equals(segment, ignoreCase = true) }
                ?: return null
            current = match.path as? KPath.Local ?: return null
        }
        return current
    }

    suspend fun count(gateway: StorageGateway, root: KPath.Local, cap: Long = DEFAULT_CAP): Result {
        var files = 0L
        var bytes = 0L
        var readable = true
        var unreadableFolders = 0
        val stack = ArrayDeque<KPath.Local>().apply { add(root) }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val children = listOrNull(gateway, dir)
            if (children == null) {
                if (dir == root) readable = false else unreadableFolders++
                continue
            }
            for (child in children) {
                val path = child.path as? KPath.Local ?: continue
                // Links are never followed (SCAN-6).
                if (child.isSymlink) continue
                when {
                    child.isDirectory -> {
                        stack += path
                    }

                    else -> {
                        files++
                        bytes += child.size
                        if (files >= cap) return Result(Count(files, bytes), readable, capped = true, unreadableFolders)
                    }
                }
            }
        }
        return Result(Count(files, bytes), readable, capped = false, unreadableFolders)
    }

    /** Numeric folder names directly under [dir] (Android user ids under the emulated storage); null if unreadable. */
    suspend fun numericChildren(gateway: StorageGateway, dir: String): List<Int>? =
        listOrNull(gateway, KPath.Local(dir))?.mapNotNull { it.name.toIntOrNull() }?.sorted()

    private suspend fun listOrNull(gateway: StorageGateway, dir: KPath.Local) = try {
        gateway.list(dir).toList()
    } catch (e: StorageException) {
        null
    } catch (e: SecurityException) {
        null
    }
}

/**
 * MSG-3 / D16a-1: which Android users have shared storage under the emulated storage folder. User 999 is judged
 * by [ClonedApps.probe], exactly as Home does, so the two never disagree.
 */
object CloneProbe {
    val CANDIDATES = listOf(10, 95, 150, 999)

    const val EMULATED = "/storage/emulated"

    suspend fun users(
        gateway: StorageGateway,
        allFiles: Boolean,
        exists: (String) -> Boolean,
        canList: (String) -> Boolean,
        cap: Long = Tree.DEFAULT_CAP,
    ): Pair<Boolean, List<CloneUser>> {
        val listed = if (allFiles) Tree.numericChildren(gateway, EMULATED) else null
        val ids = (CANDIDATES + listed.orEmpty()).filter { it != 0 }.distinct().sorted()
        if (!allFiles) return false to ids.map { CloneUser(it, CloneState.NOT_CHECKED) }
        val home = ClonedApps.probe(allFiles = true, exists = exists, canList = canList)
        return (listed != null) to ids.map { id ->
            val root = ClonedApps.root(id)
            val state = if (id in ClonedApps.CANDIDATE_USERS) {
                when {
                    root in home.readable -> CloneState.READABLE
                    exists(root) -> CloneState.HIDDEN
                    else -> CloneState.NOT_VISIBLE
                }
            } else {
                when {
                    !exists(root) -> CloneState.NOT_VISIBLE
                    canList(root) -> CloneState.READABLE
                    else -> CloneState.HIDDEN
                }
            }
            CloneUser(
                id = id,
                state = state,
                scannedByHome = root in home.readable,
                whatsApp = if (state ==
                    CloneState.READABLE
                ) {
                    WhatsAppFolders.measure(gateway, root, cap)
                } else {
                    emptyMap()
                },
            )
        }
    }
}

/** Runs one scan with Home's scanner and roots, and Home's classification, without storing anything. */
object ScanCheck {
    private const val TRASHED_PREFIX = ".trashed-"

    suspend fun run(
        scanner: Scanner,
        roots: List<KPath.Local>,
        primaryRoot: String?,
        classify: suspend (List<ScanItem>) -> ClassificationSummary,
        clock: () -> Long,
        fat32Max: Long,
    ): ScanFacts {
        val items = ArrayList<ScanItem>()
        var unreadable = 0
        var lost = 0
        var done: ScanEvent.Done? = null
        val start = clock()
        scanner.scan(roots).collect { event ->
            when (event) {
                is ScanEvent.Batch -> items += event.items
                is ScanEvent.Unreadable -> unreadable++
                is ScanEvent.VolumeLost -> lost++
                is ScanEvent.Done -> done = event
                is ScanEvent.Progress -> Unit
            }
        }
        val scanMillis = clock() - start
        val classifyStart = clock()
        val summary = classify(items)
        val classifyMillis = clock() - classifyStart

        fun sum(filter: (ScanItem) -> Boolean) =
            items.filter(filter).fold(Count()) { acc, it -> acc + Count(1, it.size) }
        val end = done
        return ScanFacts(
            roots = roots.size,
            scanMillis = scanMillis,
            classifyMillis = classifyMillis,
            total = Count(items.size.toLong(), items.sumOf { it.size }),
            unreadable = unreadable,
            volumesLost = lost,
            complete = end?.complete == true && lost == 0,
            skippedSymlinks = end?.skippedSymlinks ?: 0,
            skippedRestricted = end?.skippedRestricted?.size ?: 0,
            categories = summary.totals.entries.associate { (cat, t) -> cat.name to Count(t.files.toLong(), t.bytes) },
            primary = if (primaryRoot == null) Count() else sum { it.path.startsWith("$primaryRoot/") },
            cloned = sum { ClonedApps.isCloned(it.path) },
            androidTrash = sum { it.isTrashed },
            trashedNamed = sum { it.path.substringAfterLast('/').startsWith(TRASHED_PREFIX) },
            over4GiB = sum { it.size > fat32Max },
        )
    }
}

/** The file-system type of a removable volume in the mount table, read the way DriveFormat reads it. */
object MountTable {
    /** The single type mounted for [volumeId], lower case letters and digits only; null if none or ambiguous. */
    fun typeOf(volumeId: String, mounts: String?): String? {
        if (mounts == null || volumeId.isBlank() || '/' in volumeId) return null
        val types = mounts.lineSequence().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 3) return@mapNotNull null
            fields[2].takeIf { fields[1] == "/mnt/media_rw/$volumeId" }
        }.toSet()
        return types.singleOrNull()?.lowercase()?.filter { it.isLetterOrDigit() }?.ifEmpty { null }
    }
}

/** Fixed labels for volumes: "internal", "SD card 1", "USB drive 1", "other volume 1". */
class VolumeLabels {
    private var sd = 0
    private var usb = 0
    private var other = 0

    fun next(primary: Boolean, removable: Boolean, usbHint: Boolean): String = when {
        primary -> DeviceReport.INTERNAL
        removable && usbHint -> "USB drive ${++usb}"
        removable -> "SD card ${++sd}"
        else -> "other volume ${++other}"
    }
}
