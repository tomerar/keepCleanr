// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.safety.ActionHandler
import io.github.tomerar.keepclear.core.safety.CanonicalPath
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.safety.SkipReason
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.core.storage.Streams.readUpTo
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
    /** Called when a purge run is refused because the wall clock jumped (E-DEL-10). */
    private val onClockJump: (ClockJump) -> Unit = {},
) : ActionHandler {
    private val validator = RestorePathValidator(protectedPaths)

    override suspend fun perform(op: PlannedOp, gateway: StorageGateway) {
        when (op.kind) {
            OpKind.TRASH, OpKind.MOVE_DELETE_ORIGINAL -> trash(op)
            OpKind.RESTORE -> restore(entryFor(op))
            OpKind.RESTORE_CHANGED -> restoreChanged(entryFor(op))
            OpKind.PURGE -> purge(entryFor(op))
        }
    }

    /**
     * Owner decision 1.3: before trashing a file, look for a trashed item that was deleted **because this file
     * existed** (it lists this path as a witness) with the same content. Then this file is the last copy and
     * needs the user's explicit confirmation. Same size + SHA-256 when the record has a hash; when the sizes
     * differ (an archive whose extracted file this is) it is blocked too (decided conservatively).
     */
    override suspend fun veto(op: PlannedOp, gateway: StorageGateway): SkipReason? {
        if (op.lastCopyConfirmed) return null
        // MOVE originals are freed only with a checked copy on the drive, so they are never the last copy.
        if (op.kind != OpKind.TRASH) return null
        val path = CanonicalPath.of(op.displayPath) ?: return null
        val holders = index.all().filter { e ->
            e.state == TrashState.ACTIVE && e.witnessPaths.any { CanonicalPath.of(it) == path }
        }
        if (holders.isEmpty()) return null
        var hash: String? = null
        for (e in holders) {
            if (e.size != op.expectedSize) return SkipReason.LAST_COPY
            val sha = e.sha256 ?: return SkipReason.LAST_COPY
            if (hash == null) hash = runCatching { gateway.openRead(op.ref).use { Sha256.of(it) } }.getOrNull()
            if (hash == null || hash == sha) return SkipReason.LAST_COPY
        }
        return null
    }

    /**
     * Owner decision 1.2: true when the file in the trash is no longer the file that was trashed (size; with
     * [fullCheck] also the SHA-256 when the record has one). Such items are shown as "changed since deleted".
     */
    suspend fun isChanged(entry: TrashEntry, fullCheck: Boolean = false): Boolean {
        val stat = runCatching { gateway.stat(KPath.Local(entry.trashedPath)) }.getOrNull() ?: return false
        if (!stat.isFile) return false
        if (stat.size != entry.size) return true
        val sha = entry.sha256 ?: return false
        if (!fullCheck) return false
        return runCatching { gateway.openRead(stat.path).use { Sha256.of(it) } != sha }.getOrDefault(true)
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
            // A duplicate's content hash is known from the scan ("dup:<sha>"), so restore can check it (T1.6).
            sha256 = op.expectedSha256 ?: op.duplicateGroup?.removePrefix("dup:")?.takeIf { Sha256.isValidHex(it) },
            ruleId = op.ruleId,
            reason = op.kind.name,
            deletedAt = now,
            expiresAt = now + TrashPolicy.RETENTION_MILLIS,
            deletedAtElapsed = clock.elapsedMillis(),
            bootId = clock.bootId(),
            deletedAtUptime = clock.uptimeMillis(),
            state = TrashState.PENDING,
            verifiedCopy = op.verifiedCopy,
            witnessPaths = op.witnesses.map { it.path },
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
        if (ok) return original
        val candidates = fallbackCandidates(entry)
        return candidates.firstOrNull { validator.check(it, entry.volumeRoot, gateway) == null } ?: candidates.first()
    }

    /**
     * `Download/KeepClear Restored/<name>`, named after the original, or else after the file in our trash (an
     * untrusted sidecar may name a PROTECTED file such as `msgstore.db.crypt14`).
     */
    private fun fallbackCandidates(entry: TrashEntry): List<String> =
        listOf(entry.originalPath.substringAfterLast('/'), entry.trashedPath.substringAfterLast('/'))
            .map { "${entry.volumeRoot}/Download/KeepClear Restored/${safeName(it)}" }
            .distinct()

    private suspend fun restore(entry: TrashEntry) {
        val trashed = KPath.Local(entry.trashedPath)
        val trashedStat = gateway.stat(trashed)
        if (trashedStat == null) {
            forget(entry) // TRASH-6: the trashed file is gone; drop the entry, report missing
            throw StorageException.NotFound(trashed)
        }
        // T1.6: the file in the trash must still be the file that was trashed (size, and content when known);
        // otherwise something swapped it and it isn't put back anywhere.
        if (trashedStat.isFile) {
            if (trashedStat.size != entry.size) {
                throw StorageException.Unsupported(trashed, "changed in the trash: size differs from the record")
            }
            val sha = entry.sha256
            if (sha != null && gateway.openRead(trashed).use { Sha256.of(it) } != sha) {
                throw StorageException.Unsupported(trashed, "changed in the trash: content differs from the record")
            }
        }
        val destination = KPath.Local(restoreDestination(entry))
        // SEC-1: whatever was chosen, original or fallback, must pass validation (e.g. no symlinked Download/).
        if (validator.check(destination.absolute, entry.volumeRoot, gateway) != null) {
            throw StorageException.Unsupported(destination, "unsafe restore destination")
        }
        val parent = mkdirs(destination.parent!!, entry.volumeRoot)
        val name = freeName(parent, destination.name)
        gateway.rename(trashed, parent, name)
        forget(entry)
    }

    /** Where a changed item goes: a separate folder, so it never replaces anything (owner decision 1.2). */
    fun changedDestinationDir(entry: TrashEntry): String = "${entry.volumeRoot}/KeepClear/Restored-changed"

    private suspend fun restoreChanged(entry: TrashEntry) {
        val trashed = KPath.Local(entry.trashedPath)
        if (gateway.stat(trashed) == null) {
            forget(entry)
            throw StorageException.NotFound(trashed)
        }
        val name = safeName(entry.originalPath.substringAfterLast('/'))
        val destination = "${changedDestinationDir(entry)}/$name"
        if (validator.check(destination, entry.volumeRoot, gateway) != null) {
            throw StorageException.Unsupported(KPath.Local(destination), "unsafe restore destination")
        }
        val parent = mkdirs(KPath.Local(changedDestinationDir(entry)), entry.volumeRoot)
        gateway.rename(trashed, parent, freeName(parent, name))
        forget(entry)
    }

    // ------------------------------------------------------------------------------------- purge

    private suspend fun purge(entry: TrashEntry) {
        val trashed = KPath.Local(entry.trashedPath)
        val root = trashRoot(entry.volumeRoot, create = false)
        require(trashed.isInside(root)) { "purge outside the trash: $trashed" } // defence in depth
        // Canonical containment: the trash and every folder between it and the item must be real folders. A
        // symlink would make the OS resolve the path somewhere else (review Low).
        requireRealFolders(root, trashed)
        gateway.stat(trashed)?.let { deleteTreeInsideTrash(it.path as KPath.Local, root) }
        forget(entry)
    }

    /**
     * TRASH-6: drops entries whose trashed file has gone (deleted by another app or the user), on volumes that
     * are present. Returns how many were dropped, so the UI can say so instead of silently doing nothing.
     */
    suspend fun forgetMissing(entries: List<TrashEntry>): Int {
        var dropped = 0
        for (entry in entries) {
            if (volumeMissing(entry.volumeRoot)) continue
            if (gateway.stat(KPath.Local(entry.trashedPath)) == null) {
                forget(entry)
                dropped++
            }
        }
        return dropped
    }

    /**
     * TRASH-2: permanently deletes ACTIVE entries past their expiry. Pre-consented by the original
     * confirmation ("waits 30 days"). Clock safety (E-DEL-10, M2): an entry is purged only when **both** the wall
     * clock and the persisted uptime say 30 days have passed, so setting the clock forward, with or without a
     * reboot, can't empty the trash early. A run in which the wall clock jumped against the uptime is refused
     * as a whole and reported through [onClockJump]. Volumes that are missing are skipped (E-STO-2).
     * Returns the bytes purged.
     */
    suspend fun purgeExpired(): Long {
        val now = clock.wallMillis()
        val uptime = clock.uptimeMillis()
        val boot = clock.bootId()
        val jump = clock.lastMark()?.let { jumpSince(it, now, uptime, boot) }
        clock.saveMark(ClockMark(now, uptime, boot))
        if (jump != null) {
            onClockJump(jump)
            return 0
        }
        var bytes = 0L
        for (stored in index.all()) {
            if (stored.state != TrashState.ACTIVE) continue
            val entry = withUptime(stored, now, uptime)
            if (now < entry.expiresAt) continue
            // SEC-1: an untrusted (unauthenticated) recovered entry's expiry is attacker-controlled. Such entries
            // are never purged automatically; only the user's explicit "Empty now" removes them.
            if (!entry.trusted) continue
            if (uptime - entry.deletedAtUptime!! < TrashPolicy.RETENTION_MILLIS) continue
            val sameBoot = entry.bootId == boot
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

    private fun jumpSince(mark: ClockMark, now: Long, uptime: Long, boot: String): ClockJump? {
        val dWall = now - mark.wallMillis
        val dUp = uptime - mark.uptimeMillis
        val sameBoot = mark.bootId == boot
        val backwards = dWall < -TrashPolicy.CLOCK_JUMP_TOLERANCE || dUp < 0
        // Across a reboot the phone may have been off, so only a jump within one boot is suspicious going forward.
        val forward = sameBoot && dWall > dUp + TrashPolicy.CLOCK_JUMP_TOLERANCE
        return if (backwards || forward) ClockJump(dWall, dUp, sameBoot) else null
    }

    /**
     * Entries without [TrashEntry.deletedAtUptime] (rebuilt from a sidecar, or from an older version) get one now:
     * the wall-clock age, capped so that at least [TrashPolicy.MIN_ELAPSED_BEFORE_PURGE] of real time still has to
     * pass. Stored, so the wait can't be reset by a later clock change.
     */
    private suspend fun withUptime(entry: TrashEntry, now: Long, uptime: Long): TrashEntry {
        if (entry.deletedAtUptime != null) return entry
        val credited = (now - entry.deletedAt).coerceIn(
            0,
            TrashPolicy.RETENTION_MILLIS - TrashPolicy.MIN_ELAPSED_BEFORE_PURGE,
        )
        return entry.copy(deletedAtUptime = uptime - credited).also { index.put(it) }
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
                    val bytes = gateway.openRead(file.path).use { it.readUpTo(Sidecar.MAX_BYTES + 1) }
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

    private suspend fun requireRealFolders(root: KPath.Local, path: KPath.Local) {
        var dir = path.parent
        while (dir != null) {
            val stat = gateway.stat(dir)
            if (stat != null && !stat.isDirectory) {
                throw StorageException.Unsupported(dir, "not a real folder inside the trash")
            }
            if (dir == root) return
            dir = dir.parent
        }
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
