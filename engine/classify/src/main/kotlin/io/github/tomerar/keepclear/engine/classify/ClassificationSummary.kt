// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.classify

import io.github.tomerar.keepclear.core.model.Category

/** Count and bytes of a set of items. */
data class Total(val files: Int = 0, val bytes: Long = 0) {
    operator fun plus(item: ClassifiedItem) = Total(files + 1, bytes + item.item.size)
}

/** One rule's items, split into buckets (e.g. months, newest first). */
data class GroupSummary(
    val ruleId: String,
    val category: Category,
    val total: Total,
    val buckets: List<Pair<String?, List<ClassifiedItem>>>,
) {
    val items: List<ClassifiedItem> get() = buckets.flatMap { it.second }
}

/**
 * What Home and the Review/Move/Clean lists show (SCAN-2, REV-1). Totals never include PROTECTED
 * items in any action total. Groups are sorted by bytes, largest first; buckets newest first.
 * D5: [phoneTotals] count only the phone's own storage ([PhoneStorage]); Home shows those next to the phone's
 * free space, while the lists still show everything that was scanned (an SD card too).
 */
data class ClassificationSummary(
    val totals: Map<Category, Total>,
    val groups: List<GroupSummary>,
    val phoneTotals: Map<Category, Total> = totals,
) {
    fun total(category: Category): Total = totals[category] ?: Total()

    /** D5: like [total], counting only items on the phone's own storage (Home). */
    fun phoneTotal(category: Category): Total = phoneTotals[category] ?: Total()

    fun groups(category: Category): List<GroupSummary> = groups.filter { it.category == category }

    fun group(ruleId: String, category: Category): GroupSummary? =
        groups.firstOrNull { it.ruleId == ruleId && it.category == category }

    /** Bytes per broad area for "Where your space is" (camera, WhatsApp, junk, other), on the phone only (D5). */
    fun areas(): Map<Area, Long> {
        val result = linkedMapOf(Area.CAMERA to 0L, Area.WHATSAPP to 0L, Area.JUNK to 0L, Area.OTHER to 0L)
        for (group in groups) {
            val area = when {
                group.category == Category.SAFE -> Area.JUNK
                group.ruleId == "camera" -> Area.CAMERA
                group.ruleId.startsWith("wa_") || group.ruleId == "legacy_wa" -> Area.WHATSAPP
                group.ruleId == Rules.PROTECTED_RULE_ID -> Area.WHATSAPP
                else -> Area.OTHER
            }
            val bytes = group.items.filter { PhoneStorage.contains(it.item.path) }.sumOf { it.item.size }
            result[area] = result.getValue(area) + bytes
        }
        return result
    }

    enum class Area { CAMERA, WHATSAPP, JUNK, OTHER }

    companion object {
        fun of(items: Collection<ClassifiedItem>): ClassificationSummary {
            val totals = items.groupBy { it.category }.mapValues { (_, list) -> list.fold(Total()) { t, i -> t + i } }
            val phoneTotals = items.filter { PhoneStorage.contains(it.item.path) }.groupBy { it.category }
                .mapValues { (_, list) -> list.fold(Total()) { t, i -> t + i } }
            val groups = items.groupBy { it.ruleId to it.category }.map { (key, list) ->
                val buckets = list.groupBy { it.group.bucket }
                    .toList()
                    .sortedWith(compareByDescending<Pair<String?, List<ClassifiedItem>>> { it.first ?: "" })
                    .map { (bucket, bucketItems) -> bucket to bucketItems.sortedByDescending { it.item.mtimeMillis } }
                GroupSummary(key.first, key.second, list.fold(Total()) { t, i -> t + i }, buckets)
            }.sortedByDescending { it.total.bytes }
            return ClassificationSummary(totals, groups, phoneTotals)
        }
    }
}

/** D5: the phone's own shared storage, for every user (`/storage/emulated/<n>/…`), never a card or USB drive. */
object PhoneStorage {
    fun contains(path: String): Boolean {
        val segments = io.github.tomerar.keepclear.core.safety.CanonicalPath.of(path)?.split('/')
            ?.filter { it.isNotEmpty() } ?: return false
        return segments.size >= 4 && segments[0] == "storage" && segments[1] == "emulated" &&
            segments[2].all { it.isDigit() }
    }
}
