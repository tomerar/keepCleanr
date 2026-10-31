// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import io.github.tomerar.keepclear.core.safety.ActionHandler
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import kotlinx.coroutines.flow.toList
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * KeepClear's 30-day trash (ADR 0006, ADR 0010, TRASH-*). It is the [ActionHandler] behind the
 * `ActionExecutor`, so every trash, restore and "Empty now" goes through the guard first.
 *
 * Layout per volume: `<volume>/.KeepClearTrash/.nomedia`, `…/<yyyyMMdd>/<id>/<original name>` and the
 * signed sidecar `…/<yyyyMMdd>/<id>.json`.
 *
 * Crash safety (LOW-3, no-loss invariant): the index row (PENDING) and the sidecar are written
 * **before** the rename; [recover] resolves PENDING rows after a crash by looking where the file is.
 */
class TrashManager(
    private val gateway: StorageGateway,
    private val index: TrashIndex,
    private val signer: SidecarSigner,
    private val clock: TrashClock,
    private val protectedPaths: ProtectedPaths,
    /** Volume roots that may hold a trash, e.g. `/storage/emulated/0`, `/storage/1234-ABCD`. */
    private val volumeRoots: List<String>,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ActionHandler {
    private val validator = RestorePathValidator(protectedPaths)

    override suspend fun perform(op: PlannedOp, gateway: StorageGateway) {
        when (op.kind) {
            OpKind.TRASH, OpKind.MOVE_DELETE_ORIGINAL -> trash(op)
            OpKind.RESTORE -> restore(entryFor(op))
            OpKind.PURGE -> purge(entryFor(op))
        }
    }

    // ------------------------------------------------------------------------------------- trash

    private suspend fun trash(op: PlannedOp) {
        val source = op.ref as? KPath.Local ?: throw StorageException.Unsupported(op.ref, "folder trash needs a path")
        val volume = volumeOf(source.absolute) ?: throw StorageException.Unsupported(source, "not on a known volume")
        val trashRoot = trashRoot(volume, create = true)
        val now = clock.wallMillis()
        val dayDir = ensureDir(trashRoot, day(now))
        val id = newId()
        val entryDir = gateway.mkdir(dayDir, id) as KPath.Local
        val entry = TrashEntry(
            id = id,
            originalPath = source.absolute,
            trashedPath = entryDir.child(source.name).absolute,
            volumeRoot = volume,
            size = op.expectedSize,
            mtimeMillis = op.expectedMtime,
            sha256 = op.expectedSha256,
            ruleId = op.ruleId,
            reason = op.kind.name,
            deletedAt = now,
            expiresAt = now + TrashPolicy.RETENTION_MILLIS,
            deletedAtElapsed = clock.elapsedMillis(),
            bootId = clock.bootId(),
            state = TrashState.PENDING,
            verifiedCopy = op.verifiedCopy,
        )
        // Write-ahead: journal + sidecar before the file moves (LOW-3). If these fail, nothing moved.
        index.put(entry)
        try {
            gateway.create(dayDir, "$id.json").use { it.write(Sidecar.encode(entry.toSidecar(), signer)) }
        } catch (e: StorageException) {
            index.remove(id)
            runCatching { gateway.delete(entryDir) }
            throw e
        }
        try {
            gateway.rename(source, entryDir, source.name)
        } catch (e: StorageException) {
            runCatching { gateway.delete(dayDir.child("$id.json")) }
            runCatching { gateway.delete(entryDir) }
            index.remove(id)
            throw e
        }
        index.put(entry.copy(state = TrashState.ACTIVE))
    }

    // ----------------------------------------------------------------------------------- restore

    /** Where [entry] would be restored, for the confirmation UI (SEC-1: shown before restoring). */
    suspend fun restoreDestination(entry: TrashEntry): String {
        val original = entry.originalPath
        val ok = entry.trusted && validator.check(original, entry.volumeRoot, gateway) == null
        return if (ok) original else fallbackDestination(entry)
    }

    private fun fallbackDestination(entry: TrashEntry): String =
        "${entry.volumeRoot}/Download/KeepClear Restored/${safeName(entry.originalPath.substringAfterLast('/'))}"

    private suspend fun restore(entry: TrashEntry) {
        val trashed = KPath.Local(entry.trashedPath)
        val trashedStat = gateway.stat(trashed)
        if (trashedStat == null) {
            forget(entry) // TRASH-6: the trashed file is gone; drop the entry, report missing
            throw StorageException.NotFound(trashed)
        }
        var destination = KPath.Local(restoreDestination(entry))
        if (validator.check(destination.absolute, entry.volumeRoot, gateway) != null) {
            destination = KPath.Local(fallbackDestination(entry))
        }
        val parent = mkdirs(destination.parent!!, entry.volumeRoot)
        val name = freeName(parent, destination.name)
        gateway.rename(trashed, parent, name)
        forget(entry)
    }

    // ------------------------------------------------------------------------------------- purge

    private suspend fun purge(entry: TrashEntry) {
        val trashed = KPath.Local(entry.trashedPath)
        val root = trashRoot(entry.volumeRoot, create = false)
        require(trashed.isInside(root)) { "purge outside the trash: $trashed" } // defence in depth
        gateway.stat(trashed)?.let { deleteTreeInsideTrash(it.path as KPath.Local, root) }
        forget(entry)
    }

    /**
     * TRASH-2: permanently deletes ACTIVE entries past their expiry. Pre-consented by the original
     * confirmation ("waits 30 days"); never touches entries trashed less than 24 h ago in this boot,
     * so a clock jump can't empty the trash early (E-DEL-10). Volumes that are missing are skipped
     * (E-STO-2). Returns the bytes purged.
     */
    suspend fun purgeExpired(): Long {
        val now = clock.wallMillis()
        var bytes = 0L
        for (entry in index.all()) {
            if (entry.state != TrashState.ACTIVE || now < entry.expiresAt) continue
            val sameBoot = entry.bootId == clock.bootId()
            if (sameBoot &&
                clock.elapsedMillis() - entry.deletedAtElapsed < TrashPolicy.MIN_ELAPSED_BEFORE_PURGE
            ) {
                continue
            }
            if (volumeMissing(entry.volumeRoot)) continue
            try {
                purge(entry)
                bytes += entry.size
            } catch (e: StorageException) {
                // Leave it for the next run.
            }
        }
        return bytes
    }

    // ----------------------------------------------------------------------------------- recover

    data class RecoveryReport(
        val resolvedPending: Int,
        val rebuiltFromSidecars: Int,
        val untrusted: Int,
        val missingDropped: Int,
    )

    /**
     * Runs on app start (E-DEL-8/9, E-SEC-2/3): resolves PENDING rows, rebuilds lost index rows from
     * sidecars (unverified MAC → untrusted), and drops rows whose files vanished.
     */
    suspend fun recover(): RecoveryReport {
        var pending = 0
        var rebuilt = 0
        var untrusted = 0
        var missing = 0
        for (entry in index.all()) {
            if (volumeMissing(entry.volumeRoot)) continue
            val inTrash = gateway.stat(KPath.Local(entry.trashedPath)) != null
            when {
                entry.state == TrashState.PENDING && inTrash -> {
                    index.put(entry.copy(state = TrashState.ACTIVE))
                    pending++
                }

                entry.state == TrashState.PENDING -> {
                    // Crash before the rename: the original never moved. Remove the journal only.
                    forget(entry)
                    pending++
                }

                !inTrash -> {
                    forget(entry).also { missing++ }
                }
            }
        }
        for (volume in volumeRoots) {
            if (volumeMissing(volume)) continue
            val root = trashRoot(volume, create = false)
            if (gateway.stat(root)?.isDirectory != true) continue
            for (dayDir in gateway.list(root).toList().filter { it.isDirectory }) {
                for (file in gateway.list(dayDir.path).toList().filter { it.isFile && it.name.endsWith(".json") }) {
                    val id = file.name.removeSuffix(".json")
                    if (!isUuid(id) || index.get(id) != null) continue
                    val bytes = gateway.openRead(file.path).use { it.readNBytes(Sidecar.MAX_BYTES + 1) }
                    val decoded = Sidecar.decode(bytes, signer) as? Sidecar.Companion.Decoded.Ok ?: continue
                    val s = decoded.sidecar
                    if (s.id != id || !KPath.isValidName(s.trashedName)) continue
                    val trashedPath = (dayDir.path as KPath.Local).child(id).child(s.trashedName)
                    if (gateway.stat(trashedPath) == null) continue
                    index.put(s.toEntry(trashedPath.absolute, volume, decoded.trusted))
                    rebuilt++
                    if (!decoded.trusted) untrusted++
                }
            }
        }
        return RecoveryReport(pending, rebuilt, untrusted, missing)
    }

    // ------------------------------------------------------------------------------------- query

    suspend fun entries(): List<TrashEntry> = index.all().filter { it.state == TrashState.ACTIVE }

    // ----------------------------------------------------------------------------------- helpers

    private suspend fun entryFor(op: PlannedOp): TrashEntry =
        index.byTrashedPath((op.ref as? KPath.Local)?.absolute ?: op.displayPath)
            ?: throw StorageException.NotFound(op.ref)

    private suspend fun forget(entry: TrashEntry) {
        val trashed = KPath.Local(entry.trashedPath)
        val entryDir = trashed.parent!!
        val sidecar = entryDir.parent!!.child("${entry.id}.json")
        runCatching { if (gateway.stat(sidecar) != null) gateway.delete(sidecar) }
        runCatching {
            val emptyDir = gateway.stat(entryDir)?.isDirectory == true && gateway.list(entryDir).toList().isEmpty()
            if (emptyDir) gateway.delete(entryDir)
        }
        index.remove(entry.id)
    }

    private suspend fun deleteTreeInsideTrash(path: KPath.Local, root: KPath.Local) {
        check(path.isInside(root)) { "refusing to delete outside the trash: $path" }
        val stat = gateway.stat(path) ?: return
        if (stat.isDirectory) {
            for (child in gateway.list(path).toList()) deleteTreeInsideTrash(child.path as KPath.Local, root)
        }
        gateway.delete(path)
    }

    private fun volumeOf(path: String): String? =
        volumeRoots.filter { path.startsWith("$it/") }.maxByOrNull { it.length }

    private suspend fun volumeMissing(volume: String): Boolean = try {
        gateway.stat(KPath.Local(volume)) == null
    } catch (e: StorageException.Disconnected) {
        true
    }

    private suspend fun trashRoot(volume: String, create: Boolean): KPath.Local {
        val root = KPath.Local(volume).child(TrashPolicy.TRASH_DIR)
        if (create) {
            ensureDir(KPath.Local(volume), TrashPolicy.TRASH_DIR)
            if (gateway.stat(root.child(".nomedia")) == null) gateway.create(root, ".nomedia").close()
        }
        return root
    }

    private suspend fun ensureDir(parent: KPath.Local, name: String): KPath.Local {
        val dir = parent.child(name)
        val stat = gateway.stat(dir)
        return when {
            stat == null -> gateway.mkdir(parent, name) as KPath.Local
            stat.isDirectory -> dir
            else -> throw StorageException.NotADirectory(dir)
        }
    }

    /** Recreates missing folders on the way to a validated restore destination (E-DEL-7). */
    private suspend fun mkdirs(dir: KPath.Local, volume: String): KPath.Local {
        if (dir.absolute == volume) return dir
        val stat = gateway.stat(dir)
        if (stat != null) {
            if (!stat.isDirectory) throw StorageException.NotADirectory(dir)
            return dir
        }
        val parent = mkdirs(dir.parent!!, volume)
        return ensureDir(parent, dir.name)
    }

    /** Never overwrite (E-DEL-6): "name (restored).ext", "name (restored 2).ext", … */
    private suspend fun freeName(dir: KPath.Local, name: String): String {
        if (gateway.stat(dir.child(name)) == null) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val stem = dot?.let { name.substring(0, it) } ?: name
        val ext = dot?.let { name.substring(it) } ?: ""
        for (n in 1..999) {
            val candidate = if (n == 1) "$stem (restored)$ext" else "$stem (restored $n)$ext"
            if (KPath.isValidName(candidate) && gateway.stat(dir.child(candidate)) == null) return candidate
        }
        throw StorageException.AlreadyExists(dir.child(name))
    }

    private fun safeName(name: String): String =
        name.filterNot { it.isISOControl() || it == '/' || it == '\\' }.ifEmpty { "restored" }.take(200)

    private fun day(now: Long): String = DAY.format(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC))

    private fun isUuid(s: String) = runCatching { UUID.fromString(s).toString() == s }.getOrDefault(false)

    private fun TrashEntry.toSidecar() = Sidecar(
        id = id,
        originalPath = originalPath,
        trashedName = trashedPath.substringAfterLast('/'),
        size = size,
        mtimeMillis = mtimeMillis,
        sha256 = sha256,
        ruleId = ruleId,
        reason = reason,
        deletedAt = deletedAt,
        expiresAt = expiresAt,
        verifiedCopy = verifiedCopy,
    )

    private fun Sidecar.toEntry(trashedPath: String, volume: String, trusted: Boolean) = TrashEntry(
        id = id,
        originalPath = originalPath,
        trashedPath = trashedPath,
        volumeRoot = volume,
        size = size,
        mtimeMillis = mtimeMillis,
        sha256 = sha256,
        ruleId = ruleId,
        reason = reason,
        deletedAt = deletedAt,
        expiresAt = expiresAt,
        deletedAtElapsed = 0,
        bootId = "recovered",
        state = TrashState.ACTIVE,
        trusted = trusted,
        verifiedCopy = verifiedCopy,
    )

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
