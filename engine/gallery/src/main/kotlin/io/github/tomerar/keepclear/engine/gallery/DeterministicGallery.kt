// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max

/** Internal/test entry only. G1b supplies real authorized reads; nothing in the app calls this yet. */
internal class DeterministicGallery(private val policy: GalleryPolicy = GalleryPolicy()) {
    suspend fun analyze(
        run: GalleryRun,
        inventory: List<GallerySource>,
        port: GalleryReadPort,
        scopeComplete: Boolean = true,
        probe: CandidateProbe? = null,
    ): GalleryResult {
        require(inventory.map { it.version.key }.distinct().size == inventory.size) { "duplicate source key" }
        val sources = inventory.sortedBy { it.version.key }
        val limits = linkedSetOf<AnalysisLimit>()
        if (!scopeComplete) limits += AnalysisLimit.PARTIAL_SCOPE
        val unavailable = linkedSetOf<SourceVersion>()
        val facts = linkedMapOf<SourceVersion, VisualFacts>()
        val groups = mutableListOf<MutableGroup>()
        val membership = mutableMapOf<SourceVersion, MutableGroup>()
        val ambiguous = mutableListOf<Pair<SourceVersion, SourceVersion>>()
        val exact = mutableMapOf<Pair<Long, String>, MutableGroup>()
        var exactComparisons = 0
        var visualComparisons = 0
        var visits = 0
        var candidateCapHits = 0
        var groupCapHits = 0

        suspend fun current(source: SourceVersion): Boolean {
            currentCoroutineContext().ensureActive()
            if (port.current(run, source)) return true
            unavailable += source
            limits += AnalysisLimit.STALE_SOURCE
            return false
        }

        // Full-file digests only in nonempty size buckets. A returned digest is bound to the requested version.
        val sizes = sources.groupingBy { it.version.size }.eachCount()
        for (item in sources) {
            val source = item.version
            if (item.kind == ContentKind.UNSUPPORTED || source.size == 0L) {
                unavailable += source
                limits += AnalysisLimit.UNAVAILABLE_FEATURE
                continue
            }
            if (!current(source) || sizes.getValue(source.size) < 2) continue
            val digest = port.fullDigest(run, source)
            if (!current(source)) continue
            if (digest == null) continue // No exact feature: visual path remains useful.
            if (digest.source != source || !digest.valid()) {
                limits += AnalysisLimit.UNAVAILABLE_FEATURE
                continue
            }
            val key = source.size to digest.sha256
            var group = exact[key]
            if (group == null || group.members.size == policy.maxGroupSize) {
                if (group != null) {
                    limits += AnalysisLimit.GROUP_SIZE
                    groupCapHits++
                }
                group = MutableGroup(GroupEvidence.FULL_DIGEST, mutableListOf())
                exact[key] = group
                groups += group
            } else {
                exactComparisons++
            }
            group.members += source
        }
        groups.removeAll { it.members.size < 2 }
        groups.forEach { group -> group.members.forEach { membership[it] = group } }

        // No decode needed to rank identical bytes: there is no technical quality winner.
        for (item in sources) {
            val source = item.version
            if (source in unavailable || source in membership || !current(source)) continue
            val record = port.visualFeatures(run, source, policy.features)
            if (!current(source)) continue
            if (record?.source != source || record.version != policy.features || !record.facts.valid()) {
                unavailable += source
                limits += AnalysisLimit.UNAVAILABLE_FEATURE
                continue
            }
            facts[source] = record.facts
        }

        val index = CandidateIndex(policy.maxComparisons)
        val comparisons = mutableMapOf<SourceVersion, Int>()
        val kinds = sources.associate { it.version to it.kind }
        for ((source, feature) in facts) {
            currentCoroutineContext().ensureActive()
            val query = index.candidates(feature)
            if (probe != null) for (other in query.sources) probe.retrieved(source, other)
            visits += query.visits
            if (query.truncated) {
                limits += AnalysisLimit.CANDIDATES
                candidateCapHits++
            }
            val compared = mutableMapOf<SourceVersion, Match>()

            fun compare(other: SourceVersion): Match {
                compared[other]?.let { return it }
                if (comparisons.getOrDefault(source, 0) >= policy.maxComparisons ||
                    comparisons.getOrDefault(other, 0) >= policy.maxComparisons
                ) {
                    limits += AnalysisLimit.CANDIDATES
                    candidateCapHits++
                    return Match.NONE
                }
                comparisons[source] = comparisons.getOrDefault(source, 0) + 1
                comparisons[other] = comparisons.getOrDefault(other, 0) + 1
                visualComparisons++
                val match = match(feature, facts.getValue(other), kinds.getValue(source), kinds.getValue(other))
                compared[other] = match
                return match
            }
            for (other in query.sources) {
                currentCoroutineContext().ensureActive()
                when (compare(other)) {
                    Match.AMBIGUOUS -> {
                        ambiguous += source to other
                    }

                    Match.NONE -> {
                        continue
                    }

                    Match.CONSISTENT -> {
                        val group = membership[other]
                        if (group != null && group.members.size == policy.maxGroupSize) {
                            limits += AnalysisLimit.GROUP_SIZE
                            groupCapHits++
                            continue
                        }
                        // Complete link, including members not retrieved by the index. No chain unions.
                        if (group != null && group.members.any { compare(it) != Match.CONSISTENT }) continue
                        val chosen = group ?: MutableGroup(
                            GroupEvidence.COMPLETE_LINK_VISUAL,
                            mutableListOf(other),
                        ).also { groups += it }
                        chosen.members += source
                        chosen.members.forEach { membership[it] = chosen }
                        break
                    }
                }
            }
            if (index.add(source, feature)) {
                limits += AnalysisLimit.CANDIDATES
                candidateCapHits++
            }
        }

        // Revalidate *all* members after the last read. Consumers must also check run/epoch at publication.
        for (item in sources) if (item.version !in unavailable) current(item.version)
        val resultGroups = groups.filter { group -> group.members.none { it in unavailable } }.map { group ->
            val members = group.members.sortedBy { it.key }.toList()
            GalleryGroup(
                GroupVersion(run, policy, members),
                group.evidence,
                if (group.evidence == GroupEvidence.FULL_DIGEST) {
                    KeeperAdvice.NoClearRecommendation
                } else {
                    KeeperRanking.recommend(members, facts)
                },
            )
        }
        val resolved = resultGroups.flatMap { it.version.members }.toSet()
        val routes = resultGroups.map { group ->
            CandidateRoute(
                run,
                policy,
                group.version.members,
                RouteState.RESOLVED_DETERMINISTIC,
                if (group.evidence ==
                    GroupEvidence.FULL_DIGEST
                ) {
                    RouteReason.EXACT_DIGEST
                } else {
                    RouteReason.CONSISTENT_VISUAL
                },
            )
        }.toMutableList()
        val uncertain = ambiguous.filter { (a, b) ->
            a !in resolved && b !in resolved && a !in unavailable && b !in unavailable
        }
        uncertain.forEach { (a, b) ->
            routes += CandidateRoute(
                run,
                policy,
                listOf(a, b).sortedBy { it.key },
                RouteState.AMBIGUOUS_CANDIDATE,
                RouteReason.CONFLICTING_HASHES,
            )
        }
        val routed = routes.flatMap { it.participants }.toSet()
        for (item in sources) {
            val source = item.version
            if (source in routed) continue
            val missing = source in unavailable
            val withheld = item.kind == ContentKind.TEXT_SENSITIVE || facts[source]?.lowDetail == true
            routes += CandidateRoute(
                run,
                policy,
                listOf(source),
                if (missing) RouteState.UNSUPPORTED_OR_INACCESSIBLE else RouteState.NO_MATCH,
                when {
                    missing -> RouteReason.UNAVAILABLE
                    withheld -> RouteReason.WITHHELD_DETAIL
                    else -> RouteReason.NO_CANDIDATE
                },
            )
        }
        currentCoroutineContext().ensureActive()
        return GalleryResult(
            run,
            resultGroups,
            routes,
            limits.toSet(),
            GalleryDiagnostics(
                sources.size,
                sources.size - unavailable.size,
                unavailable.size,
                exactComparisons,
                visualComparisons,
                visits,
                uncertain.size,
                candidateCapHits,
                groupCapHits,
                comparisons.values.maxOrNull() ?: 0,
            ),
        )
    }

    private fun match(a: VisualFacts, b: VisualFacts, kindA: ContentKind, kindB: ContentKind): Match {
        if (a.lowDetail || b.lowDetail || kindA != ContentKind.PHOTO || kindB != ContentKind.PHOTO) return Match.NONE
        if (abs(a.aspect - b.aspect) / max(a.aspect, b.aspect) > 0.02) return Match.NONE
        val d = java.lang.Long.bitCount(a.dHash xor b.dHash)
        val p = java.lang.Long.bitCount(a.pHash xor b.pHash)
        if (d <= policy.dHashDistance && p <= policy.pHashDistance) return Match.CONSISTENT
        if ((d <= policy.dHashDistance || p <= policy.pHashDistance) && d <= 8 && p <= 12) return Match.AMBIGUOUS
        return Match.NONE
    }

    private enum class Match { NONE, CONSISTENT, AMBIGUOUS }

    private class MutableGroup(val evidence: GroupEvidence, val members: MutableList<SourceVersion>)
}

/**
 * Global hash-band retrieval, independent of dates/sizes. Five dHash and seven pHash bands preserve uncapped
 * recall within radii 4/6 by pigeonhole. Both inspected postings (including duplicates) and bucket storage
 * are capped, not merely the number of returned hits. This deliberately trades recall for bounded work.
 */
private class CandidateIndex(private val cap: Int) {
    private data class Band(val metric: Int, val band: Int, val value: Long)

    data class Query(val sources: List<SourceVersion>, val visits: Int, val truncated: Boolean)

    private val buckets = mutableMapOf<Band, MutableList<SourceVersion>>()

    private fun bands(facts: VisualFacts): List<Band> = buildList {
        for ((metric, hash) in listOf(facts.dHash, facts.pHash).withIndex()) {
            val count = if (metric == 0) 5 else 7
            for (band in 0 until count) {
                val start = band * 64 / count
                val width = (band + 1) * 64 / count - start
                add(Band(metric, band, (hash ushr start) and ((1L shl width) - 1)))
            }
        }
    }

    fun candidates(facts: VisualFacts): Query {
        val found = linkedSetOf<SourceVersion>()
        var visits = 0
        for (band in bands(facts)) {
            for (source in buckets[band].orEmpty()) {
                if (visits == cap) return Query(found.toList(), visits, true)
                visits++
                found += source
            }
        }
        return Query(found.toList(), visits, false)
    }

    fun add(source: SourceVersion, facts: VisualFacts): Boolean {
        var truncated = false
        for (band in bands(facts)) {
            val bucket = buckets.getOrPut(band) { mutableListOf() }
            if (bucket.size < cap + 1) bucket += source else truncated = true
        }
        return truncated
    }
}
