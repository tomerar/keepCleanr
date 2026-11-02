// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.engine.enrich.HashCache
import io.github.tomerar.keepclear.engine.scan.ScanItem

/**
 * E13: the full SHA-256 of files the classification already read (WhatsApp "Sent" copies and their originals), so
 * the next scan doesn't read gigabytes again. A hash counts only for the same path, size, date and file identity;
 * anything else reads the file again. In memory only (LOW-1), at most [max] files. The executor still re-checks the
 * copy that stays, by its content, before anything is deleted (D6), so a hash from here never deletes on its own.
 */
class MemoryHashCache(private val max: Int = 50_000) : HashCache {
    private data class Key(val path: String, val size: Long, val mtime: Long, val identity: String?)

    private val map = object : LinkedHashMap<Key, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?) = size > max
    }

    private fun key(item: ScanItem) = Key(item.path, item.size, item.mtimeMillis, item.identity)

    override suspend fun get(item: ScanItem): String? = synchronized(map) { map[key(item)] }

    override suspend fun put(item: ScanItem, sha256: String) {
        synchronized(map) { map[key(item)] = sha256 }
    }
}
