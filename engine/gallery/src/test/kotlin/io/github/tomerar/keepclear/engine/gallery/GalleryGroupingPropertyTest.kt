// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** GAL-3/5/12, E-G05/06/07/10/25; numeric tests do not establish real-image precision/recall. */
class GalleryGroupingPropertyTest {
    @Test
    fun `A similar B similar C does not union A with C`(): Unit = runBlocking {
        val a = source(1)
        val b = source(2)
        val c = source(3)
        val port = ReadFake().apply {
            put(a, facts(0))
            put(b, facts(15)) // 4 bits from A and C
            put(c, facts(255)) // 8 bits from A
        }
        val engine = DeterministicGallery()
        val result = engine.analyze(testRun, listOf(c, b, a).map(::GallerySource), port)
        assertEquals(listOf(a, b), result.groups.single().version.members)
        assertTrue(result.routes.any { it.participants == listOf(c) && it.state == RouteState.NO_MATCH })
        assertEquals(result, engine.analyze(testRun, listOf(a, b, c).map(::GallerySource), port))
    }

    @Test
    fun `every generated group is disjoint and complete link across seeded permutations`(): Unit = runBlocking {
        val random = Random(314159)
        repeat(40) {
            val members = (1..40).map(::source)
            val port = ReadFake()
            val values = members.associateWith {
                val hash = random.nextLong(0, 1024)
                facts(hash, hash)
            }
            values.forEach { (source, facts) -> port.put(source, facts) }
            val engine = DeterministicGallery()
            val first = engine.analyze(testRun, members.shuffled(random).map(::GallerySource), port)
            val again = engine.analyze(testRun, members.reversed().map(::GallerySource), port)
            assertEquals(first, again)
            val grouped = first.groups.flatMap { it.version.members }
            assertEquals(grouped.distinct().size, grouped.size)
            first.groups.forEach { group ->
                assertTrue(group.version.members.size in 2..20)
                group.version.members.forEach { a ->
                    group.version.members.forEach { b ->
                        assertTrue(java.lang.Long.bitCount(values.getValue(a).dHash xor values.getValue(b).dHash) <= 4)
                        assertTrue(java.lang.Long.bitCount(values.getValue(a).pHash xor values.getValue(b).pHash) <= 6)
                    }
                }
            }
        }
    }

    @Test
    fun `5000 colliding hashes bound actual visits comparisons and group sizes`(): Unit = runBlocking {
        val members = (1..5000).map(::source)
        val port = ReadFake().apply { members.forEach { put(it) } }
        val result = DeterministicGallery().analyze(testRun, members.map(::GallerySource), port)
        assertFalse(result.complete)
        assertTrue(AnalysisLimit.CANDIDATES in result.limits)
        assertTrue(result.diagnostics.candidateVisits <= 5000 * 100)
        assertTrue(result.diagnostics.visualComparisons <= 5000 * 100 / 2)
        assertTrue(result.diagnostics.maxVisualComparisonsPerImage <= 100)
        assertTrue(result.diagnostics.candidateCapHits > 0)
        assertTrue(result.diagnostics.groupCapHits > 0)
        assertTrue(result.groups.all { it.version.members.size <= 20 })
        assertEquals(5000, result.diagnostics.analyzed)
    }

    @Test
    fun `full digest groups are bounded without decoding identical bytes`(): Unit = runBlocking {
        val members = (1..45).map { source(it, 100) }
        val port = ReadFake().apply { members.forEach { digests[it] = FullDigest(it, "a".repeat(64)) } }
        val result = DeterministicGallery().analyze(testRun, members.map(::GallerySource), port)
        assertEquals(listOf(20, 20, 5), result.groups.map { it.version.members.size })
        assertTrue(result.groups.all { it.evidence == GroupEvidence.FULL_DIGEST })
        assertTrue(result.groups.all { it.advice == KeeperAdvice.NoClearRecommendation })
        assertEquals(emptyList<SourceVersion>(), port.visualReads)
        assertTrue(AnalysisLimit.GROUP_SIZE in result.limits)
    }

    @Test
    fun `same size and partial-looking digest cannot establish equality`(): Unit = runBlocking {
        val a = source(1, 200)
        val b = source(2, 200)
        val single = source(3, 300)
        val port = ReadFake().apply {
            put(a, facts(0))
            put(b, facts(-1))
            put(single, facts(0x5555555555555555))
            digests[a] = FullDigest(a, "abcd")
            digests[b] = FullDigest(b, "abcd")
        }
        val result = DeterministicGallery().analyze(testRun, listOf(a, b, single).map(::GallerySource), port)
        assertTrue(result.groups.isEmpty())
        assertEquals(listOf(a, b), port.digestReads)
        assertTrue(AnalysisLimit.UNAVAILABLE_FEATURE in result.limits)
    }

    @Test
    fun `identical visual hashes with different full digests remain similarity advice`(): Unit = runBlocking {
        val a = source(1, 200)
        val b = source(2, 200)
        val port = ReadFake().apply {
            put(a)
            put(b)
            digests[a] = FullDigest(a, "a".repeat(64))
            digests[b] = FullDigest(b, "b".repeat(64))
        }
        val before = port.digests.toMap()
        val result = DeterministicGallery().analyze(testRun, listOf(a, b).map(::GallerySource), port)
        assertEquals(GroupEvidence.COMPLETE_LINK_VISUAL, result.groups.single().evidence)
        assertEquals(RouteReason.CONSISTENT_VISUAL, result.routes.single().reason)
        assertEquals(KeeperAdvice.NoClearRecommendation, result.groups.single().advice)
        assertEquals(before, port.digests)
    }

    @Test
    fun `global visual retrieval can match delayed copies of different byte sizes`(): Unit = runBlocking {
        val a = source(1)
        val b = source(2).copy(mtimeMillis = Long.MAX_VALUE)
        val port = ReadFake().apply {
            put(a)
            put(b, facts(1, 3))
        }
        val result = DeterministicGallery().analyze(testRun, listOf(a, b).map(::GallerySource), port)
        assertEquals(listOf(a, b), result.groups.single().version.members)
        assertEquals(GroupEvidence.COMPLETE_LINK_VISUAL, result.groups.single().evidence)
    }

    @Test
    fun `flat text and mismatched aspect hashes are withheld without automatic escalation`(): Unit = runBlocking {
        for (kind in listOf("flat", "text", "aspect")) {
            val a = source(1)
            val b = source(2)
            val port = ReadFake().apply {
                put(a, facts().copy(lowDetail = kind == "flat"))
                put(b, facts().copy(aspect = if (kind == "aspect") 0.5 else 1.5))
            }
            val inventory = listOf(
                GallerySource(
                    a,
                    if (kind ==
                        "text"
                    ) {
                        ContentKind.TEXT_SENSITIVE
                    } else {
                        ContentKind.PHOTO
                    },
                ),
                GallerySource(b),
            )
            val result = DeterministicGallery().analyze(testRun, inventory, port)
            assertTrue(result.groups.isEmpty(), kind)
            assertTrue(result.routes.all { it.state == RouteState.NO_MATCH }, kind)
        }
    }

    @Test
    fun `conflicting plausible hashes route only that pair and quality ties do not escalate`(): Unit = runBlocking {
        val a = source(1)
        val b = source(2)
        val unrelated = source(3)
        val port = ReadFake().apply {
            put(a)
            put(b, facts(1, 255))
            put(unrelated, facts(-1))
        }
        val result = DeterministicGallery().analyze(testRun, listOf(a, b, unrelated).map(::GallerySource), port)
        assertEquals(listOf(a, b), result.routes.single { it.state == RouteState.AMBIGUOUS_CANDIDATE }.participants)
        assertEquals(RouteState.NO_MATCH, result.routes.single { it.participants == listOf(unrelated) }.state)
        port.put(b)
        val tied = DeterministicGallery().analyze(testRun, listOf(a, b).map(::GallerySource), port)
        assertEquals(KeeperAdvice.NoClearRecommendation, tied.groups.single().advice)
        assertEquals(RouteState.RESOLVED_DETERMINISTIC, tied.routes.single().state)
    }

    @Test
    fun `invalid numbers and stale source or algorithm records never enter a group`(): Unit = runBlocking {
        val a = source(1)
        val b = source(2)
        val bad = listOf(
            FeatureRecord(b.copy(revision = "replaced-same-size-time"), FeatureVersion.V1, facts()),
            FeatureRecord(b, FeatureVersion("unknown", "unknown"), facts()),
            FeatureRecord(b, FeatureVersion.V1, facts().copy(aspect = Double.NaN)),
            FeatureRecord(b, FeatureVersion.V1, facts(quality = QualityFacts(1, Double.POSITIVE_INFINITY, 0.0))),
            FeatureRecord(b, FeatureVersion.V1, facts(quality = QualityFacts(1, 1.0, -0.1))),
        )
        for (record in bad) {
            val port = ReadFake().apply {
                put(a)
                records[b] = record
            }
            val result = DeterministicGallery().analyze(testRun, listOf(a, b).map(::GallerySource), port)
            assertTrue(result.groups.isEmpty())
            assertEquals(
                RouteState.UNSUPPORTED_OR_INACCESSIBLE,
                result.routes.single {
                    it.participants == listOf(b)
                }.state,
            )
        }
    }

    @Test
    fun `permission or run change during a read rejects all late publication`(): Unit = runBlocking {
        val a = source(1)
        val b = source(2)
        for (revoke in listOf(true, false)) {
            val port = ReadFake().apply {
                put(a)
                put(b)
                beforeRead = {
                    if (it == b) {
                        if (revoke) hidden += a else currentRun = testRun.copy(id = "new")
                    }
                }
            }
            val result = DeterministicGallery().analyze(testRun, listOf(a, b).map(::GallerySource), port)
            assertTrue(result.groups.isEmpty())
            assertTrue(AnalysisLimit.STALE_SOURCE in result.limits)
        }
    }

    @Test
    fun `unsupported and inaccessible sources are not read and partial scope is honest`(): Unit = runBlocking {
        val a = source(1)
        val b = source(2)
        val port = ReadFake().apply { hidden += b }
        val result = DeterministicGallery().analyze(
            testRun,
            listOf(GallerySource(a, ContentKind.UNSUPPORTED), GallerySource(b)),
            port,
            scopeComplete = false,
        )
        assertEquals(2, result.diagnostics.skipped)
        assertTrue(port.visualReads.isEmpty() && port.digestReads.isEmpty())
        assertFalse(result.complete)
        assertTrue(AnalysisLimit.PARTIAL_SCOPE in result.limits)
    }

    @Test
    fun `cancellation is propagated without another read or terminal success`() {
        val members = (1..100).map(::source)
        val port = ReadFake().apply { members.forEach { put(it) } }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                coroutineScope {
                    port.beforeRead = { cancel() }
                    DeterministicGallery().analyze(testRun, members.map(::GallerySource), port)
                }
            }
        }
        assertEquals(1, port.visualReads.size)
    }

    @Test
    fun `source keys remain lossless volume scoped and reject duplicate keys`() {
        val a = source(1)
        assertFalse(a.key == a.key.copy(volume = "sd-card"))
        assertFalse(SourceKey("primary", "é") == SourceKey("primary", "e\u0301"))
        assertFalse(a.copy(revision = null, observationId = "read-2") == a.copy(revision = null))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                DeterministicGallery().analyze(
                    testRun,
                    listOf(GallerySource(a), GallerySource(a.copy(revision = "new"))),
                    ReadFake(),
                )
            }
        }
    }
}
