// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.safety.CanonicalPath
import io.github.tomerar.keepclear.core.safety.ProtectedPaths
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem
import io.github.tomerar.keepclear.engine.classify.Rules
import io.github.tomerar.keepclear.engine.gallery.ContentKind
import io.github.tomerar.keepclear.engine.scan.Source

/** Why a still image is outside the gallery's work entirely (GAL-1, E-G03). These are never read. */
enum class GalleryExclusion { PROTECTED, KEPT, TRASHED, EXCLUDED_LOCATION, JUNK }

/**
 * The live exclusions, rebuilt by the caller from the existing sources: [protectedPaths] from
 * `BackupRoots.protectedPaths` (PROTECTED rules, KeepClear's trash, backup targets), [keptRoots] from `UserChoices`
 * and [excludedRoots] from `BackupRoots.scanExclusions` (backup drives and folders).
 */
class GalleryExclusions(
    val protectedPaths: ProtectedPaths,
    keptRoots: Collection<String> = emptyList(),
    excludedRoots: Collection<String> = emptyList(),
) {
    private val kept = keptRoots.mapNotNull { CanonicalPath.of(it) }
    private val excluded = excludedRoots.mapNotNull { CanonicalPath.of(it) }

    fun isKept(path: String): Boolean = under(path, kept)

    fun isExcluded(path: String): Boolean = under(path, excluded)

    private fun under(path: String, roots: List<String>): Boolean {
        if (roots.isEmpty()) return false
        val canonical = CanonicalPath.of(path) ?: return true
        return roots.any { canonical == it || canonical.startsWith("$it/") }
    }
}

/**
 * The latest completed scan's classified items (`ClassificationSummary`), its ID and whether it was complete.
 * [exclusions] is called again before every read, so a file kept mid-run is never read (E-G09).
 */
class GallerySnapshot(
    val scanId: String,
    val items: List<ClassifiedItem>,
    val scanComplete: Boolean,
    val exclusions: () -> GalleryExclusions,
) {
    companion object {
        fun of(
            scanId: String,
            summary: ClassificationSummary,
            scanComplete: Boolean,
            exclusions: () -> GalleryExclusions,
        ) =
            GallerySnapshot(scanId, summary.groups.flatMap { it.items }, scanComplete, exclusions)
    }
}

/** Metadata-only eligibility. It reuses the scan's classification; it is not another scanner. */
internal object GalleryEligibility {
    sealed interface Decision {
        data object NotStill : Decision

        data class Excluded(val reason: GalleryExclusion) : Decision

        /** Still image with no guarded reader for its location yet (for example an all-files walk item). */
        data object NoReader : Decision

        data class Eligible(val kind: ContentKind) : Decision
    }

    fun decide(item: ClassifiedItem, exclusions: GalleryExclusions): Decision {
        val scan = item.item
        if (scan.mime?.startsWith("image/") != true) return Decision.NotStill
        return when {
            item.ruleId == Rules.USER_KEPT || scan.isFavorite || exclusions.isKept(scan.path) -> {
                Decision.Excluded(GalleryExclusion.KEPT)
            }

            item.category == Category.PROTECTED || exclusions.protectedPaths.isProtected(scan.path) -> {
                Decision.Excluded(GalleryExclusion.PROTECTED)
            }

            scan.isTrashed -> {
                Decision.Excluded(GalleryExclusion.TRASHED)
            }

            exclusions.isExcluded(scan.path) -> {
                Decision.Excluded(GalleryExclusion.EXCLUDED_LOCATION)
            }

            item.category == Category.SAFE -> {
                Decision.Excluded(GalleryExclusion.JUNK)
            }

            scan.source != Source.MEDIA_STORE || scan.ref !is KPath.Media -> {
                Decision.NoReader
            }

            // Screenshots: near-identical text images must not be matched on perceptual hashes alone (E-G05).
            else -> {
                Decision.Eligible(
                    if (item.ruleId ==
                        "screenshots"
                    ) {
                        ContentKind.TEXT_SENSITIVE
                    } else {
                        ContentKind.PHOTO
                    },
                )
            }
        }
    }
}
