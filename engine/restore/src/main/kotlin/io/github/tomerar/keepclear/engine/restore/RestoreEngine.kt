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
    /** The copy on the drive doesn't match the manifest (or is missing): nothing written. */
    val damaged: Int = 0,
    /** Put into the restore folder instead of the original place (unsafe, taken or on another volume). */
    val redirected: Int = 0,
    val failed: Int = 0,
    /** The drive went away mid-restore: what is done stays done, the rest can be restored again. */
    val interrupted: Boolean = false,
)

/**
 * MOVE-17: lists a backup from its manifest and copies selected files back to the phone.
 *
 * - The manifest is untrusted (SEC-3, E-SEC-5): only lines that parse strictly are offered.
 * - The original place is used only when [RestorePathValidator] accepts it (inside [volumeRoot], not PROTECTED,
 *   not the trash, real folders); otherwise the file goes to [restoreDirRel] (`Pictures/KeepClear Restored`).
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

    /** Where [entry] would go: its original path if that is safe, else the restore folder. */
    suspend fun destinationFor(entry: BackupEntry): Pair<String, Boolean> {
        val original = entry.line.src
        val onThisVolume = original.startsWith("$volumeRoot/")
        if (onThisVolume && validator.check(original, volumeRoot, phone) == null) return original to false
        return "$volumeRoot/$restoreDirRel/${entry.group}/${entry.name}" to true
    }

    suspend fun restore(entries: List<BackupEntry>, onProgress: (Int) -> Unit = {}): RestoreReport {
        val restored = mutableListOf<String>()
        var already = 0
        var damaged = 0
        var redirected = 0
        var failed = 0
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
                }
            } catch (e: StorageException.Disconnected) {
                return RestoreReport(restored, already, damaged, redirected, failed, interrupted = true)
            } catch (e: StorageException) {
                failed++
            }
            onProgress(index + 1)
        }
        return RestoreReport(restored, already, damaged, redirected, failed)
    }

    private sealed interface Outcome {
        data class Restored(val path: String, val redirected: Boolean) : Outcome

        data object AlreadyThere : Outcome

        data object Damaged : Outcome
    }

    private suspend fun restoreOne(entry: BackupEntry): Outcome {
        val expected = entry.line.sha256
        val onDrive = target.stat(entry.targetRel)
        if (onDrive == null || !onDrive.isFile || onDrive.size != entry.line.size) return Outcome.Damaged

        var (destination, redirected) = destinationFor(entry)
        val existing = phone.stat(KPath.Local(destination))
        if (existing != null) {
            if (existing.isFile && existing.size == entry.line.size && hashOnPhone(destination) == expected) {
                return Outcome.AlreadyThere
            }
            // Taken by something else: never overwrite, use the restore folder instead.
            destination = "$volumeRoot/$restoreDirRel/${entry.group}/${entry.name}"
            redirected = true
        }
        if (redirected) {
            val reason = validator.check(destination, volumeRoot, phone)
            if (reason != null) throw StorageException.Unsupported(KPath.Local(destination), reason)
        }
        val dest = KPath.Local(destination)
        val dir = mkdirs(dest.parent!!)
        val temp = ".kc-restore-${newId()}"
        val tempPath = dir.child(temp)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            phone.create(dir, temp).use { out ->
                target.openRead(entry.targetRel).use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
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
        } catch (e: StorageException) {
            runCatching { phone.delete(tempPath) }
            throw e
        }
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
