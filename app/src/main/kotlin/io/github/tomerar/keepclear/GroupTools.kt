// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.GroupSummary
import io.github.tomerar.keepclear.engine.classify.Total
import java.time.Instant
import java.time.ZoneId

/** PR H5: how a review group's pictures are ordered. Newest first is the default (the month sections as before). */
enum class GroupSort { NEWEST, OLDEST, LARGEST }

/** PR H5: a group's sort, year filter and search; the default shows the group exactly as before. */
data class GroupView(val sort: GroupSort = GroupSort.NEWEST, val year: Int? = null, val query: String = "") {
    /** A year or a search hides part of the group. */
    val filtered: Boolean get() = year != null || query.isNotBlank()
}

/** One header and its pictures. [byMonth] sections are months (or the rule's buckets); otherwise one sorted list. */
data class GroupSection(val bucket: String?, val items: List<ClassifiedItem>, val byMonth: Boolean = true)

/**
 * PR H5 (power users): sort, filter by year, search and "select the N largest" in a review group. Pure logic, so it
 * is tested with tables; the group screen only lays it out. Nothing here selects anything by itself: the screen calls
 * [largest] only when the user taps it (SAFETY rule 7), and the plan still goes through [CleanPlanner] and the choke
 * point, which keep the last copy of a duplicate (SAFETY-8).
 */
object GroupTools {
    /** The choices offered for "select the N largest"; only those smaller than what is shown. */
    val LARGEST_CHOICES = listOf(10, 25, 50, 100)

    /** The years the group's files are from, newest first. */
    fun years(items: List<ClassifiedItem>, zone: ZoneId): List<Int> =
        items.map { yearOf(it, zone) }.distinct().sortedDescending()

    /** What [view] shows of [group], in sections. */
    fun sections(group: GroupSummary, view: GroupView, zone: ZoneId): List<GroupSection> {
        val keep: (ClassifiedItem) -> Boolean = { item ->
            (view.year == null || yearOf(item, zone) == view.year) &&
                GridSearch.matches(item.item.path.substringAfterLast('/'), item.item.mtimeMillis, view.query, zone)
        }
        return when (view.sort) {
            GroupSort.NEWEST -> {
                group.buckets.map { (bucket, items) -> GroupSection(bucket, items.filter(keep)) }
                    .filter { it.items.isNotEmpty() }
            }

            GroupSort.OLDEST -> {
                group.buckets.asReversed().map { (bucket, items) ->
                    GroupSection(
                        bucket,
                        items.filter(keep).sortedWith(
                            compareBy({
                                it.item.mtimeMillis
                            }, { it.item.path }),
                        ),
                    )
                }.filter { it.items.isNotEmpty() }
            }

            GroupSort.LARGEST -> {
                val items = group.items.filter(keep).sortedWith(bySize)
                if (items.isEmpty()) emptyList() else listOf(GroupSection(null, items, byMonth = false))
            }
        }
    }

    /** Every file [sections] show, in order. */
    fun visible(sections: List<GroupSection>): List<ClassifiedItem> = sections.flatMap { it.items }

    /**
     * "Select the N largest": the paths of the [n] largest files of [visible] (what is on screen now, after the
     * filter), ties by path so the same list always gives the same choice.
     */
    fun largest(visible: List<ClassifiedItem>, n: Int): Set<String> =
        visible.sortedWith(bySize).take(n.coerceAtLeast(0)).mapTo(LinkedHashSet()) { it.item.path }

    /** The N choices for [visibleCount] files: only those that leave something out (otherwise "select all"). */
    fun choices(visibleCount: Int): List<Int> = LARGEST_CHOICES.filter { it < visibleCount }

    /** Count and size of the files at [paths] among [items]. */
    fun total(items: List<ClassifiedItem>, paths: Set<String>): Total =
        items.filter { it.item.path in paths }.fold(Total()) { t, i -> t + i }

    private val bySize = compareByDescending<ClassifiedItem> { it.item.size }.thenBy { it.item.path }

    private fun yearOf(item: ClassifiedItem, zone: ZoneId): Int =
        Instant.ofEpochMilli(item.item.mtimeMillis).atZone(zone).year
}
