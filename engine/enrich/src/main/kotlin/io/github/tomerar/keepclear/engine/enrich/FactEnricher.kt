// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.enrich

import io.github.tomerar.keepclear.core.hash.Sha256
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageException
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.core.storage.Streams.readUpTo
import io.github.tomerar.keepclear.core.storage.Streams.skipFully
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.classify.Fact
import io.github.tomerar.keepclear.engine.classify.Rules
import io.github.tomerar.keepclear.engine.scan.ScanItem
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Optional cache for full hashes, keyed by path and invalidated by size/mtime/identity (design.md §4). */
interface HashCache {
    suspend fun get(item: ScanItem): String?

    suspend fun put(item: ScanItem, sha256: String)
}

/**
 * Second-pass enrichers (design.md §4 step 2), run only on candidate subsets:
 * - **Twins** (SAFE-4): a messenger `Sent/` file is redundant only if a byte-identical copy (size →
 *   head/tail prehash → full SHA-256, never partial hashes for the decision) exists **outside** every
 *   messenger folder, in a place that is not itself SAFE to clean. Empty files are ignored.
 * - **Archives** (SAFE-3, SEC-2): a `.zip` is fully extracted when every file entry exists in the sibling
 *   folder (same relative path, size and CRC-32); partial when only some do. Unsafe names → no fact.
 *
 * Read-only; unreadable files simply produce no fact.
 */
class FactEnricher(
    private val gateway: StorageGateway,
    private val hashCache: HashCache? = null,
    private val now: Long = System.currentTimeMillis(),
    private val classifier: Classifier = Classifier(),
) {
    suspend fun facts(items: Collection<ScanItem>): Map<String, Set<Fact>> {
        val result = HashMap<String, MutableSet<Fact>>()
        twins(items).forEach { result.getOrPut(it) { mutableSetOf() } += Fact.HAS_TWIN_OUTSIDE_MESSENGER }
        for (item in items) {
            if (!item.path.lowercase().endsWith(".zip") || item.ref !is KPath.Local) continue
            archiveFact(item)?.let { result.getOrPut(item.path) { mutableSetOf() } += it }
        }
        return result
    }

    // ------------------------------------------------------------------------------------- twins

    private fun isMessenger(path: String): Boolean {
        val rel = Rules.relativeSegments(path)?.second ?: return true
        return rel.any { it == "whatsapp" || it == "whatsapp business" || it == "telegram" }
    }

    private fun isSentCopy(path: String): Boolean {
        val rel = Rules.relativeSegments(path)?.second ?: return false
        val root = rel.indexOfFirst { it == "whatsapp" || it == "whatsapp business" }
        return root >= 0 && rel.getOrNull(root + 1) == "media" && rel.dropLast(1).drop(root + 2).contains("sent")
    }

    private fun isRetained(item: ScanItem): Boolean = classifier.classify(item, now = now).category != Category.SAFE

    private suspend fun twins(items: Collection<ScanItem>): Set<String> {
        val candidates = items.filter { it.size > 0 && !it.isTrashed && isSentCopy(it.path) }
        if (candidates.isEmpty()) return emptySet()
        val sizes = candidates.map { it.size }.toSet()
        // A witness must be a copy that stays: never one that is itself SAFE to clean (another app's recycle
        // bin, junk, thumbnails), or "Clean now" could trash every copy at once (SAFETY-8).
        val originalsBySize = items.filter {
            it.size in sizes && !it.isTrashed && !isMessenger(it.path) && isRetained(it)
        }.groupBy { it.size }
        val found = HashSet<String>()
        for (candidate in candidates) {
            val originals = originalsBySize[candidate.size] ?: continue
            val candidatePre = prehash(candidate) ?: continue
            for (original in originals) {
                if (prehash(original) != candidatePre) continue
                val a = fullHash(candidate) ?: break
                val b = fullHash(original) ?: continue
                if (a == b) {
                    found += candidate.path
                    break
                }
            }
        }
        return found
    }

    private val prehashes = HashMap<String, String?>()

    /** First and last 4 KiB (czkawka-style pre-filter); never used alone for a decision. */
    private suspend fun prehash(item: ScanItem): String? = prehashes.getOrPut(item.path) {
        read(item) { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val head = input.readUpTo(4096)
            digest.update(head)
            if (item.size > 8192) {
                input.skipFully(item.size - 4096 - head.size)
                digest.update(input.readUpTo(4096))
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private suspend fun fullHash(item: ScanItem): String? {
        hashCache?.get(item)?.let { return it }
        val hash = read(item) { Sha256.of(it) } ?: return null
        hashCache?.put(item, hash)
        return hash
    }

    private suspend fun <T> read(item: ScanItem, block: (InputStream) -> T): T? = try {
        gateway.openRead(item.ref).use(block)
    } catch (e: StorageException) {
        null
    } catch (e: java.io.EOFException) {
        null
    }

    // ---------------------------------------------------------------------------------- archives

    private suspend fun archiveFact(zip: ScanItem): Fact? {
        val entries = try {
            ZipCentralDirectory.read(gateway, zip.ref, zip.size)
        } catch (e: StorageException) {
            null
        } ?: return null
        val files = entries.filterNot { it.isDirectory }
        if (files.isEmpty()) return null
        val relPaths = files.map { ZipCentralDirectory.safeRelativePath(it.name) ?: return null }
        val folder = KPath.Local(zip.path.dropLast(4)) // ".zip"
        val folderStat = gateway.stat(folder)
        if (folderStat?.isDirectory != true) return null
        var matched = 0
        for ((entry, rel) in files.zip(relPaths)) {
            val target = KPath.Local("${folder.absolute}/$rel")
            val stat = gateway.stat(target) ?: continue
            if (!stat.isFile || stat.size != entry.size) continue
            val crc = try {
                gateway.openRead(target).use { input ->
                    val c = CRC32()
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        c.update(buf, 0, n)
                    }
                    c.value
                }
            } catch (e: StorageException) {
                continue
            }
            if (crc == entry.crc32) matched++
        }
        return when (matched) {
            files.size -> Fact.ARCHIVE_FULLY_EXTRACTED
            0 -> null
            else -> Fact.ARCHIVE_PARTIALLY_EXTRACTED
        }
    }
}
