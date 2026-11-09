// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GalleryGroupingTest {
    private fun completed(id: Int, d: Long, kind: ContentKind = ContentKind.PHOTO) =
        CompletedFeature(FeatureRecord(source(id), FeatureVersion.V1, facts(d)), kind)

    @Test
    fun `groups only the completed records it is given`(): Unit = runBlocking {
        val result = GalleryGrouping.group(
            testRun,
            listOf(completed(1, 0x0F0F), completed(2, 0x0F0F), completed(3, 0x5555_5555_5555_5555L)),
            scopeComplete = true,
            current = { true },
        )
        assertEquals(listOf(listOf(source(1), source(2))), result.groups.map { it.version.members })
        assertEquals(GroupEvidence.COMPLETE_LINK_VISUAL, result.groups.single().evidence)
        assertEquals(3, result.diagnostics.eligible)
        assertEquals(0, result.diagnostics.exactComparisons) // no digest is read here
        assertTrue(result.complete)
    }

    @Test
    fun `a refused source is withheld and a partial scope is never complete`(): Unit = runBlocking {
        val result = GalleryGrouping.group(
            testRun,
            listOf(completed(1, 0x0F0F), completed(2, 0x0F0F)),
            scopeComplete = false,
            current = { it != source(2) },
        )
        assertTrue(result.groups.isEmpty())
        assertFalse(result.complete)
        assertTrue(AnalysisLimit.PARTIAL_SCOPE in result.limits)
        assertTrue(AnalysisLimit.STALE_SOURCE in result.limits)
    }

    @Test
    fun `text-sensitive records are never matched visually`(): Unit = runBlocking {
        val result = GalleryGrouping.group(
            testRun,
            listOf(completed(1, 0x0F0F, ContentKind.TEXT_SENSITIVE), completed(2, 0x0F0F, ContentKind.TEXT_SENSITIVE)),
            scopeComplete = true,
            current = { true },
        )
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `the permission epoch moves on every scope change and never back`() {
        var last: GalleryAuthorization? = null
        val seen = mutableListOf<Long>()
        for (s in listOf(
            GalleryScope.FULL_IMAGES,
            GalleryScope.FULL_IMAGES,
            GalleryScope.SELECTED,
            GalleryScope.NONE,
        )) {
            last = PermissionEpoch.advance(last, s)
            seen += last.epoch
        }
        assertEquals(listOf(1L, 1L, 2L, 3L), seen)
        assertEquals(listOf(false, false, true, true), GalleryScope.entries.map { it.complete })
    }
}
