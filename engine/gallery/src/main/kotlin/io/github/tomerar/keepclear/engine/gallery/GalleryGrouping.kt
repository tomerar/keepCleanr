// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

/** A feature record the caller already completed in this run, with its content kind. */
data class CompletedFeature(val record: FeatureRecord, val kind: ContentKind = ContentKind.PHOTO)

/**
 * C210: the smallest public entry to the advisory grouping engine. It groups only records the caller already
 * completed and reads nothing itself: no full-file digest (so no exact-identity groups yet), no decode, no cache.
 * [current] rechecks the live run and permission epoch; a source it refuses is withheld. The result is advisory
 * ([GalleryResult]): groups, routes, limits and counters, never a category, proof, selection or file action.
 */
object GalleryGrouping {
    suspend fun group(
        run: GalleryRun,
        completed: List<CompletedFeature>,
        scopeComplete: Boolean,
        current: suspend (SourceVersion) -> Boolean,
        policy: GalleryPolicy = GalleryPolicy(),
        probe: CandidateProbe? = null,
    ): GalleryResult {
        val records = completed.associateBy { it.record.source }
        val port = object : GalleryReadPort {
            override suspend fun current(run: GalleryRun, source: SourceVersion): Boolean =
                source in records && current(source)

            override suspend fun fullDigest(run: GalleryRun, source: SourceVersion): FullDigest? = null

            override suspend fun visualFeatures(
                run: GalleryRun,
                source: SourceVersion,
                version: FeatureVersion,
            ): FeatureRecord? = records[source]?.record?.takeIf { it.version == version }
        }
        return DeterministicGallery(policy).analyze(
            run,
            completed.map { GallerySource(it.record.source, it.kind) },
            port,
            scopeComplete,
            probe,
        )
    }
}

/**
 * C215: a developer-only, read-only view of the candidate-retrieval stage, for the G2 evaluation harness's
 * pre-reranking candidate recall (gallery-benchmarks.md). [retrieved] is told each source the hash-band index
 * returned for [query] before any comparison, cap or complete-link check; a pair the comparison cap later refuses is
 * still reported, a pair the index cap never returned is not. It receives only versions the caller supplied and
 * returns nothing, so it cannot change groups, routes, limits or counters. The app never passes one.
 */
fun interface CandidateProbe {
    fun retrieved(query: SourceVersion, candidate: SourceVersion)
}
