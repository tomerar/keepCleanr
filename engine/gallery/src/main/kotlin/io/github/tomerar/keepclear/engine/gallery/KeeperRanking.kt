// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

/** Conservative Pareto advice: every rival must be meaningfully worse and none meaningfully better. */
internal object KeeperRanking {
    fun recommend(members: List<SourceVersion>, facts: Map<SourceVersion, VisualFacts>): KeeperAdvice {
        if (members.size < 2 || members.any { facts[it]?.valid() != true }) return KeeperAdvice.NoClearRecommendation
        val winners = members.mapNotNull { candidate ->
            val a = facts.getValue(candidate).quality
            val reasons = linkedSetOf<KeeperReason>()
            val dominates = members.filterNot { it == candidate }.all { rival ->
                val b = facts.getValue(rival).quality
                val advantage = advantages(a, b)
                reasons += advantage
                advantage.isNotEmpty() && advantages(b, a).isEmpty()
            }
            if (dominates) KeeperAdvice.Suggested(candidate, reasons.take(2)) else null
        }
        return winners.singleOrNull() ?: KeeperAdvice.NoClearRecommendation
    }

    private fun advantages(a: QualityFacts, b: QualityFacts): List<KeeperReason> = buildList {
        if (a.usablePixels.toDouble() >= b.usablePixels.toDouble() * 1.25) add(KeeperReason.HIGHER_USABLE_RESOLUTION)
        if (a.sharpness >= b.sharpness * 1.25 && a.sharpness - b.sharpness >= 0.05) add(KeeperReason.SHARPER)
        if (b.clippedFraction - a.clippedFraction >= 0.05) add(KeeperReason.LESS_CLIPPING)
    }
}
