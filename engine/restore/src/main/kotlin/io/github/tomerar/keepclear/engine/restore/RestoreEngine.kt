// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.restore

import io.github.tomerar.keepclear.backup.BackupTarget
import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.engine.move.Manifest
import io.github.tomerar.keepclear.engine.trash.RestorePathValidator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** One file listed in a backup's manifest (MOVE-17). Everything in it is untrusted until checked. */
data class BackupEntry(
    /** The device folder on the target, e.g. `KeepClear/OnePlus 10T`. */
    val rootRel: String,
    val line: Manifest.Line,
) {
    val targetRel: String get() = "$rootRel/${line.rel}"

    /** The source group folder the backup used (`Camera`, `WhatsApp Images`, …): first segment of [Manifest.Line.rel]. */
    val group: String get() = line.rel.substringBefore('/')

    /** Year-month of the original file, for grouping (UTC). */
    val month: String get() = Instant.ofEpochMilli(line.mtime).atOffset(ZoneOffset.UTC).let {
        "%04d-%02d".format(it.year, it.monthValue)
    }

    val name: String get() = line.rel.substringAfterLast('/')
}

/** Result of restoring a selection (MOVE-17). */
data class RestoreReport(
    /** Absolute paths written on the phone (for MediaStore re-indexing). */
    val restored: List<String> = emptyList(),
    /** Already on the phone with the same content: nothing written. */
    val alreadyThere: Int = 0,
    /** The copy on the drive doesn't match the manifest (or is missing, or is longer than it says): nothing written. */
    val damaged: Int = 0,
    /** Put into the restore folder instead of the original place (unsafe, unknown, taken or on another volume). */
    val redirected: Int = 0,
    val failed: Int = 0,
    /** The drive went away mid-restore: what is done stays done, the rest can be restored again. */
    val interrupted: Boolean = false,
    /** D2: hidden files and programs in the manifest, never written anywhere. */
    val skipped: Int = 0,
    /** D1: the selection didn't fit before starting (or free space couldn't be measured): nothing was written. */
    val notEnoughRoom: RoomCheck? = null,
    /** D1: free space fell to the reserve mid-restore: what is done stays done, the rest was not started. */
    val stoppedLowSpace: Boolean = false,
    /** D1, SAFETY-9: test mode. Nothing was written; this is what would have happened. */
    val simulated: RestorePreview? = null,
)

/** Free space on the phone's shared storage (D1). Measured by the app (`StorageManager.getAllocatableBytes`). */
data class Space(val allocatable: Long, val total: Long) {
    /** D1: what restoring always leaves free, so the phone keeps working: 5% of the storage, at least 1 GB. */
    val reserve: Long get() = maxOf(total / 20, MIN_RESERVE)

    /** How much may be restored now without eating into [reserve]. */
    val room: Long get() = (allocatable - reserve).coerceAtLeast(0)

    companion object {
        /** Decimal, like Android's Settings (1 GB = 1000³ bytes). */
        const val MIN_RESERVE = 1_000_000_000L
    }
}

/** Port: measures [Space]; keeps the engine pure JVM (D1). Null when it can't be measured. */
fun interface SpaceProbe {
    suspend fun measure(): Space?
}

/**
 * D2 port: whether KeepClear on **this phone** moved a file away from [src] whose content had this [sha256] (a
 * MoveJob/MoveFile record). Only then is the manifest's `src` used as the place to put the file back.
 */
fun interface MoveRecords {
    suspend fun movedFrom(src: String, sha256: String): Boolean

    companion object {
        /** No records: every file goes to the restore folder. */
        val NONE = MoveRecords { _, _ -> false }
    }
}

/** D1: needed bytes against the room there is. [space] null = couldn't measure, which never fits. */
data class RoomCheck(val needed: Long, val space: Space?) {
    val fits: Boolean get() = space != null && needed <= space.room
}

/** D1 test mode: what restoring would do. [bytes] counts the files that would be written. */
data class RestorePreview(val toOriginal: Int, val toRestoreFolder: Int, val skipped: Int, val bytes: Long) {
    val files: Int get() = toOriginal + toRestoreFolder
}

/** Where one backed-up file would go (D2). */
enum class Where { ORIGINAL, RESTORE_FOLDER, SKIPPED }

/** Why a file from a backup is never written to the phone (D2). */
enum class RestoreSkip {
    /** A name that starts with a dot (`.nomedia` would hide a whole folder from the gallery). */
    HIDDEN_FILE,

    /** An app or program (`.apk`, `.sh`, `.exe`, …). */
    PROGRAM,
}

data class Placement(val entry: BackupEntry, val where: Where, val path: String?, val skip: RestoreSkip? = null)

/**
 * D2: which names a backup may put back. Pure and table-driven. Hidden files and programs are never written;
 * anything that isn't a known photo, video, audio or document type goes only to the restore folder.
 */
object RestoreNames {
    private val MEDIA_AND_DOCUMENTS = setOf(
        // Photos
        "jpg",
        "jpeg",
        "png",
        "gif",
        "webp",
        "heic",
        "heif",
        "avif",
        "bmp",
        "dng",
        "tif",
        "tiff",
        "cr2",
        "cr3",
        "nef",
        "arw",
        "orf",
        "rw2",
        "raf",
        // Videos
        "mp4",
        "m4v",
        "mov",
        "3gp",
        "3g2",
        "mkv",
        "webm",
        "avi",
        "mts",
        "m2ts",
        // Audio and voice notes
        "mp3",
        "m4a",
        "aac",
        "ogg",
        "oga",
        "opus",
        "wav",
        "flac",
        "amr",
        "awb",
        "mid",
        "midi",
        // Documents
        "pdf",
        "txt",
        "doc",
        "docx",
        "xls",
        "xlsx",
        "ppt",
        "pptx",
        "odt",
        "ods",
        "odp",
        "rtf",
        "csv",
        "epub",
        "vcf",
    )
    private val PROGRAMS = setOf(
        "apk",
        "apks",
        "apkm",
        "xapk",
        "aab",
        "dex",
        "odex",
        "oat",
        "jar",
        "so",
        "elf",
        "bin",
        "sh",
        "bash",
        "zsh",
        "py",
        "pl",
        "rb",
        "js",
        "mjs",
        "jsx",
        "vbs",
        "ps1",
        "bat",
        "cmd",
        "com",
        "exe",
        "msi",
        "scr",
        "dll",
        "app",
        "dmg",
        "pkg",
        "deb",
        "rpm",
        "run",
        "desktop",
        "lnk",
    )

    /** Lower-case extension, ignoring trailing dots and spaces ("x.apk." is an `apk`). */
    fun extension(name: String): String = name.trimEnd('.', ' ').substringAfterLast('.', "").lowercase()

    fun skipReason(name: String): RestoreSkip? = when {
        name.startsWith('.') -> RestoreSkip.HIDDEN_FILE
        extension(name) in PROGRAMS -> RestoreSkip.PROGRAM
        else -> null
    }

    fun isMediaOrDocument(name: String): Boolean = skipReason(name) == null && extension(name) in MEDIA_AND_DOCUMENTS
}

/**
 * MOVE-17: lists a backup from its manifest and copies selected files back to the phone.
 *
 * - The manifest is untrusted (SEC-3, E-SEC-5): only lines that parse strictly are offered.
 * - D2: the manifest's `src` is used only when KeepClear on this phone moved a file with the same SHA-256 away
 *   from there ([records]), its name equals the backed-up name, it is a photo/video/audio/document type and
 *   [RestorePathValidator] accepts it (inside [volumeRoot], not PROTECTED, not the trash, real folders);
 *   otherwise the file goes to [restoreDirRel] (`Pictures/KeepClear Restored`). Hidden files and programs are
 *   skipped ([RestoreNames]).
 * - D1: never fills the phone. The selection must fit with [Space.reserve] left over before anything starts,
 *   free space is measured again before each file (stop cleanly at the reserve), and a read from the drive
 *   never goes past the manifest's size for that file (a hostile server could stream forever).
 * - D1, SAFETY-9: in test mode ([isDryRun]) nothing is written; the report says what would have been.
 * - **Never overwrites:** a taken name gets a free one in the restore folder; a file already there with the same
 *   content is left alone.
 * - Each copy is checked twice: the bytes read from the drive must match the manifest's SHA-256 while copying,
 *   and the file on the phone is read back and must match again before it is given its final name.
 * - Nothing on the phone is deleted, except KeepClear's own temporary file of a failed copy.
 */
class RestoreEngine(
    private val target: BackupTarget,
    private val phone: StorageGateway,
    private val protectedPaths: ProtectedPaths,
    /** The phone's shared storage, e.g. `/storage/emulated/0`. */
    private val volumeRoot: String,
    /** D1: free space on [volumeRoot]. */
    private val space: SpaceProbe,
    /** D2: what KeepClear moved off this phone. */
    private val records: MoveRecords = MoveRecords.NONE,
    /** D1, SAFETY-9: test mode, read at the start of every [restore]. */
    private val isDryRun: () -> Boolean = { false },
    private val restoreDirRel: String = RESTORE_DIR,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val validator = RestorePathValidator(protectedPaths)

    /** Device folders on the target that hold a manifest (a drive may hold several phones' backups). */
    suspend fun backups(): List<String> = runCatching { target.list(ROOT) }.getOrDefault(emptyList())
        .filter { it.isDirectory }
        .map { "$ROOT/${it.name}" }
        .filter { target.stat("$it/${Manifest.JSONL}")?.isFile == true }
        .sorted()

    /** The valid entries of one backup's manifest; lines that fail the strict parse are dropped (E-SEC-5). */
    suspend fun catalog(rootRel: String): List<BackupEntry>? {
        val lines = runCatching { target.openRead("$rootRel/${Manifest.JSONL}").use { Manifest.readLines(it) } }
            .getOrNull() ?: return null
        return lines.drop(1).mapNotNull { raw -> Manifest.parseLine(raw, rootRel)?.let { BackupEntry(rootRel, it) } }
            .distinctBy { it.line.rel }
    }

    /** D2: where [entry] would go: its original path only when every check passes, else the restore folder. */
    suspend fun placement(entry: BackupEntry): Placement {
        RestoreNames.skipReason(entry.name)?.let { return Placement(entry, Where.SKIPPED, null, it) }
        val original = entry.line.src
        val honoured = original.startsWith("$volumeRoot/") &&
            original.substringAfterLast('/') == entry.name &&
            RestoreNames.isMediaOrDocument(entry.name) &&
            validator.check(original, volumeRoot, phone) == null &&
            records.movedFrom(original, entry.line.sha256)
        return if (honoured) {
            Placement(entry, Where.ORIGINAL, original)
        } else {
            Placement(entry, Where.RESTORE_FOLDER, restoreFolderPath(entry))
        }
    }

    /** Where every entry would go, for the catalog (D2). */
    suspend fun plan(entries: List<BackupEntry>): List<Placement> = entries.map { placement(it) }

    /** D1: whether [entries] fit with the reserve left free. Skipped files need no room. */
    suspend fun roomCheck(entries: List<BackupEntry>): RoomCheck = RoomCheck(
        needed = entries.filter { RestoreNames.skipReason(it.name) == null }.sumOf { it.line.size },
        space = runCatching { space.measure() }.getOrNull(),
    )

    /** D1 test mode: what [restore] would do, without writing anything. */
    suspend fun preview(entries: List<BackupEntry>): RestorePreview {
        val placements = plan(entries)
        return RestorePreview(
            toOriginal = placements.count { it.where == Where.ORIGINAL },
            toRestoreFolder = placements.count { it.where == Where.RESTORE_FOLDER },
            skipped = placements.count { it.where == Where.SKIPPED },
            bytes = placements.filter { it.where != Where.SKIPPED }.sumOf { it.entry.line.size },
        )
    }

    suspend fun restore(entries: List<BackupEntry>, onProgress: (Int) -> Unit = {}): RestoreReport {
        if (isDryRun()) return RestoreReport(simulated = preview(entries))
        val room = roomCheck(entries)
        if (!room.fits) return RestoreReport(notEnoughRoom = room)
        val restored = mutableListOf<String>()
        var already = 0
        var damaged = 0
        var redirected = 0
        var failed = 0
        var skipped = 0

        fun report(interrupted: Boolean = false, lowSpace: Boolean = false) = RestoreReport(
            restored,
            already,
            damaged,
            redirected,
            failed,
            interrupted = interrupted,
            skipped = skipped,
            stoppedLowSpace = lowSpace,
        )
        for ((index, entry) in entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                when (val outcome = restoreOne(entry)) {
                    is Outcome.Restored -> {
                        restored += outcome.path
                        if (outcome.redirected) redirected++
                    }

                    Outcome.AlreadyThere -> {
                        already++
                    }

                    Outcome.Damaged -> {
                        damaged++
                    }

                    Outcome.Skipped -> {
                        skipped++
                    }
                }
            } catch (e: StorageException.Disconnected) {
                return report(interrupted = true)
            } catch (e: OutOfRoom) {
                return report(lowSpace = true)
            } catch (e: java.io.IOException) {
                // Any other storage or stream failure (e.g. the phone is full): this file failed, go on (PR #26).
                failed++
            }
            onProgress(index + 1)
        }
        return report()
    }

    private sealed interface Outcome {
        data class Restored(val path: String, val redirected: Boolean) : Outcome

        data object AlreadyThere : Outcome

        data object Damaged : Outcome

        data object Skipped : Outcome
    }

    /** D1: free space reached the reserve; the restore stops before this file. */
    private class OutOfRoom : Exception()

    /** D1: the drive sent more bytes than the manifest says the file has. */
    private class TooLong : Exception()

    private suspend fun restoreOne(entry: BackupEntry): Outcome {
        val expected = entry.line.sha256
        val placement = placement(entry)
        if (placement.where == Where.SKIPPED) return Outcome.Skipped
        val onDrive = target.stat(entry.targetRel)
        if (onDrive == null || !onDrive.isFile || onDrive.size != entry.line.size) return Outcome.Damaged

        var destination = placement.path!!
        var redirected = placement.where == Where.RESTORE_FOLDER
        val existing = phone.stat(KPath.Local(destination))
        if (existing != null) {
            if (existing.isFile && existing.size == entry.line.size && hashOnPhone(destination) == expected) {
                return Outcome.AlreadyThere
            }
            // Taken by something else: never overwrite, use the restore folder instead.
            destination = restoreFolderPath(entry)
            redirected = true
        }
        // Restored there before (an earlier or interrupted run)? Then it is already there, not copied again.
        if (redirected && alreadyInRestoreFolder(destination, entry)) return Outcome.AlreadyThere
        if (redirected) {
            val reason = validator.check(destination, volumeRoot, phone)
            if (reason != null) throw StorageException.Unsupported(KPath.Local(destination), reason)
        }
        // D1: measured again right before writing; stop cleanly instead of eating into the reserve.
        val now = runCatching { space.measure() }.getOrNull()
        if (now == null || entry.line.size > now.room) throw OutOfRoom()
        val dest = KPath.Local(destination)
        val dir = mkdirs(dest.parent!!)
        val temp = ".kc-restore-${newId()}"
        val tempPath = dir.child(temp)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            phone.create(dir, temp).use { out ->
                target.openRead(entry.targetRel).use { input ->
                    val buf = ByteArray(BUFFER)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        // D1: never write past the size the manifest gives (a hostile server can stream forever).
                        if (total > entry.line.size) throw TooLong()
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val copied = digest.digest().joinToString("") { "%02x".format(it) }
            // Read from the drive must match the manifest, and the file on the phone must match again.
            if (copied != expected || hashOnPhone(tempPath.absolute) != expected) {
                runCatching { phone.delete(tempPath) }
                return Outcome.Damaged
            }
            val final = rename(tempPath, dir, dest.name)
            return Outcome.Restored(final, redirected)
        } catch (e: TooLong) {
            runCatching { phone.delete(tempPath) }
            return Outcome.Damaged
        } catch (e: java.io.IOException) {
            // Includes raw stream failures (a full phone): never leave a partial temporary file behind.
            runCatching { phone.delete(tempPath) }
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { phone.delete(tempPath) }
            throw e
        }
    }

    /** `Pictures/KeepClear Restored/<group>/<name>`; a group name can't make the folder hidden. */
    private fun restoreFolderPath(entry: BackupEntry): String {
        val group = entry.group.trimStart('.').ifEmpty { "Other" }
        return "$volumeRoot/$restoreDirRel/$group/${entry.name}"
    }

    /** Whether [destination] or one of its numbered names ("x (2).jpg") already holds this exact file. */
    private suspend fun alreadyInRestoreFolder(destination: String, entry: BackupEntry): Boolean {
        val dest = KPath.Local(destination)
        val dir = dest.parent ?: return false
        for (attempt in 1..MAX_NAME_TRIES) {
            val candidate = dir.child(if (attempt == 1) dest.name else numbered(dest.name, attempt))
            val stat = phone.stat(candidate) ?: return false
            if (stat.isFile && stat.size == entry.line.size && hashOnPhone(candidate.absolute) == entry.line.sha256) {
                return true
            }
        }
        return false
    }

    /** Gives the temp file its final name, or the next free one ("name (2).jpg"); never overwrites. */
    private suspend fun rename(temp: KPath.Local, dir: KPath.Local, name: String): String {
        for (attempt in 1..MAX_NAME_TRIES) {
            val candidate = if (attempt == 1) name else numbered(name, attempt)
            try {
                return (phone.rename(temp, dir, candidate) as KPath.Local).absolute
            } catch (_: StorageException.AlreadyExists) {
                continue
            }
        }
        throw StorageException.AlreadyExists(dir.child(name))
    }

    private fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        return if (dot == null) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
    }

    private suspend fun mkdirs(dir: KPath.Local): KPath.Local {
        val root = KPath.Local(volumeRoot)
        if (!dir.isInside(root)) throw StorageException.Unsupported(dir, "outside the phone's storage")
        var current = root
        for (segment in dir.absolute.removePrefix("$volumeRoot/").split('/')) {
            val next = current.child(segment)
            val stat = phone.stat(next)
            when {
                stat == null -> phone.mkdir(current, segment)
                !stat.isDirectory -> throw StorageException.NotADirectory(next)
            }
            current = next
        }
        return current
    }

    private suspend fun hashOnPhone(path: String): String? =
        runCatching { phone.openRead(KPath.Local(path)).use { Sha256.of(it) } }.getOrNull()

    companion object {
        const val ROOT = "KeepClear"
        const val RESTORE_DIR = "Pictures/KeepClear Restored"
        private const val BUFFER = 256 * 1024
        private const val MAX_NAME_TRIES = 100
    }
}
