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
        )
    }
}
