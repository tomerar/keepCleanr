// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.scan

/**
 * Difference between two scans (SCAN-5): only [added] and [changed] items need hashing or
 * re-classification. Keyed by path; a file counts as changed when size, mtime or identity differ.
 */
data class ScanDiff(val added: List<ScanItem>, val changed: List<ScanItem>, val removed: List<String>, val unchanged: Int) {
    companion object {
        fun compute(previous: Map<String, ScanItem>, current: Collection<ScanItem>): ScanDiff {
            val added = mutableListOf<ScanItem>()
            val changed = mutableListOf<ScanItem>()
            var unchanged = 0
            val seen = HashSet<String>(current.size)
            for (item in current) {
                seen += item.path
                val old = previous[item.path]
                when {
                    old == null -> added += item
                    old.size != item.size || old.mtimeMillis != item.mtimeMillis || old.identity != item.identity -> changed += item
                    else -> unchanged++
                }
            }
            val removed = previous.keys.filterNot { it in seen }
            return ScanDiff(added, changed, removed, unchanged)
        }
    }
}
