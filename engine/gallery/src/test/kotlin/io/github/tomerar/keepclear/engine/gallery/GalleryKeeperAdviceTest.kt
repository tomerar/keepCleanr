// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** GAL-10, AC-G15 (numeric policy only), E-G32. */
class GalleryKeeperAdviceTest {
    private val a = source(1)
    private val b = source(2)

    @Test
    fun `observed resolution advantage explains a recommendation without selection state`() {
        val values = mapOf(
            a to facts(quality = QualityFacts(2_000_000, 1.0, 0.1)),
            b to facts(quality = QualityFacts(1_000_000, 1.0, 0.1)),
        )
        val advice = KeeperRanking.recommend(listOf(a, b), values) as KeeperAdvice.Suggested
        assertEquals(a, advice.source)
        assertEquals(listOf(KeeperReason.HIGHER_USABLE_RESOLUTION), advice.reasons)
        assertEquals(values, values.toMap())
    }

    @Test
    fun `sharper or less clipped facts support bounded factual reasons`() {
        val values = mapOf(
            a to facts(quality = QualityFacts(1_000_000, 2.0, 0.02)),
            b to facts(quality = QualityFacts(1_000_000, 1.0, 0.2)),
        )
        val advice = KeeperRanking.recommend(listOf(a, b), values) as KeeperAdvice.Suggested
        assertEquals(a, advice.source)
        assertEquals(listOf(KeeperReason.SHARPER, KeeperReason.LESS_CLIPPING), advice.reasons)
        assertTrue(advice.reasons.size <= 2)
    }

    @Test
    fun `tie tiny differences conflict missing or invalid facts abstain`() {
        val cases = listOf(
            mapOf(a to facts(), b to facts()),
            mapOf(a to facts(quality = QualityFacts(1_000_001, 1.001, 0.099)), b to facts()),
            mapOf(a to facts(quality = QualityFacts(2_000_000, 0.5, 0.1)), b to facts()),
            mapOf(a to facts(quality = QualityFacts(0, 1.0, 0.1)), b to facts()),
            mapOf(a to facts()),
        )
        cases.forEach { assertEquals(KeeperAdvice.NoClearRecommendation, KeeperRanking.recommend(listOf(a, b), it)) }
    }
}
