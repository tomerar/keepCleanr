// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** C215: the G2 harness's candidate-stage hook reports retrieval only and never changes the advisory result. */
class CandidateProbeTest {
    private fun completed(id: Int, d: Long, p: Long = d) =
        CompletedFeature(FeatureRecord(source(id), FeatureVersion.V1, facts(d, p)))

    @Test
    fun `reports every pair the index retrieved, including pairs that were not grouped`(): Unit = runBlocking {
        val seen = mutableListOf<Pair<SourceVersion, SourceVersion>>()
        val result = GalleryGrouping.group(
            testRun,
            // 1 and 2 match; 3 is 8 bits from 1 (retrieved, never grouped); 4 shares no hash band with anyone.
            listOf(completed(1, 0), completed(2, 15), completed(3, 255), completed(4, 0x5555_5555_5555_5555L)),
            scopeComplete = true,
            current = { true },
            probe = { query, candidate -> seen += query to candidate },
        )
        assertEquals(listOf(listOf(source(1), source(2))), result.groups.map { it.version.members })
        assertEquals(
            setOf(source(2) to source(1), source(3) to source(1), source(3) to source(2)),
            seen.toSet(),
        )
        assertEquals(seen.size, seen.toSet().size)
        assertTrue(seen.none { (a, b) -> a == source(4) || b == source(4) })
    }

    @Test
    fun `a probe never changes groups, routes, limits or counters, also at the caps`(): Unit = runBlocking {
        val random = Random(20261109)
        var capped = 0
        var uncapped = 0
        repeat(30) { round ->
            // Every third round: few distinct hashes over many sources drive the comparison, index and group-size
            // caps. Otherwise: spread hashes with small near-copy families, which stay under every cap.
            val crowded = round % 3 == 0
            val records = (1..if (crowded) 160 else 40).map { id ->
                val hash = if (crowded) random.nextLong(0, 4) else (id / 3).toLong() * -0x61c8_8646_80b5_83ebL
                completed(id, hash xor random.nextLong(0, 4), hash xor random.nextLong(0, 4))
            }
            val plain = GalleryGrouping.group(testRun, records, scopeComplete = true, current = { true })
            var calls = 0
            val probed = GalleryGrouping.group(
                testRun,
                records.shuffled(random),
                scopeComplete = true,
                current = { true },
                probe = { _, _ -> calls++ },
            )
            assertEquals(plain, probed)
            assertTrue(calls <= plain.diagnostics.candidateVisits)
            if (plain.diagnostics.candidateCapHits > 0 && plain.diagnostics.groupCapHits > 0) capped++
            if (plain.limits.isEmpty() && plain.groups.isNotEmpty()) uncapped++
        }
        assertTrue(uncapped > 0) // the comparison is made where no cap binds
        assertTrue(capped > 0) // and where both caps bind
    }
}
