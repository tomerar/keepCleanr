// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

/** Opaque, lossless adapter keys. Never normalize names or use a media ID without its volume. */
data class SourceKey(val volume: String, val ref: String) : Comparable<SourceKey> {
    init {
        require(volume.isNotEmpty() && ref.isNotEmpty())
        require(volume.length <= 512 && ref.length <= 4096)
    }

    override fun compareTo(other: SourceKey): Int = compareValuesBy(this, other, { it.volume }, { it.ref })
}

/**
 * A revision is a provider generation + store version, or a freshly verified content revision, NOT an inode
 * alone. Where the adapter cannot prove a revision, observationId must be fresh for each authorized read run.
 * Size + coarse mtime (even with the same ref) never permits cross-run reuse by itself (E-G07).
 */
data class SourceVersion(
    val key: SourceKey,
    val size: Long,
    val mtimeMillis: Long,
    val revision: String?,
    val observationId: String,
) {
    init {
        require(size >= 0 && observationId.isNotEmpty())
        require(revision == null || revision.isNotEmpty())
    }
}

data class GalleryRun(val id: String, val scanId: String, val permissionEpoch: Long) {
    init {
        require(id.isNotEmpty() && scanId.isNotEmpty() && permissionEpoch >= 0)
    }
}

data class FeatureVersion(val preprocessing: String, val algorithm: String) {
    companion object {
        val V1 = FeatureVersion("canonical-still-v1", "dhash64-phash64-quality-v1")
    }
}

/** Thresholds are an internal experiment, not a calibrated/shipping Balanced profile (G2). */
data class GalleryPolicy(
    val version: String = "g1a-experimental-v1",
    val features: FeatureVersion = FeatureVersion.V1,
    val dHashDistance: Int = 4,
    val pHashDistance: Int = 6,
    val maxComparisons: Int = 100,
    val maxGroupSize: Int = 20,
) {
    init {
        require(version.isNotEmpty())
        require(dHashDistance in 0..4 && pHashDistance in 0..6)
        require(maxComparisons in 1..100 && maxGroupSize in 2..20)
    }
}

enum class ContentKind { PHOTO, TEXT_SENSITIVE, UNSUPPORTED }

/** Only the existing scanner/classifier adapter may supply eligible entries; this is not another scanner. */
data class GallerySource(val version: SourceVersion, val kind: ContentKind = ContentKind.PHOTO)

data class QualityFacts(val usablePixels: Long, val sharpness: Double, val clippedFraction: Double) {
    fun valid(): Boolean = usablePixels > 0 && sharpness.isFinite() && sharpness >= 0 &&
        clippedFraction.isFinite() && clippedFraction in 0.0..1.0
}

/** Hashes measure resemblance, never byte equality. Low-detail/text-sensitive matches are withheld. */
data class VisualFacts(
    val dHash: Long,
    val pHash: Long,
    val aspect: Double,
    val lowDetail: Boolean,
    val quality: QualityFacts,
) {
    fun valid(): Boolean = aspect.isFinite() && aspect > 0 && quality.valid()
}

data class FeatureRecord(val source: SourceVersion, val version: FeatureVersion, val facts: VisualFacts)

/** Full-file digest only. There is deliberately no partial-hash/equality conversion API. */
data class FullDigest(val source: SourceVersion, val sha256: String) {
    fun valid(): Boolean = sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }
}

/**
 * Read-only port; no paths, mutable storage gateway, safety types, model or cache writes in this engine.
 * Implementations recheck authorization/source around reads, return only completed compatible records, and
 * bound/cancel their reads. current() includes latest run, scope/exclusions/kept state and permission epoch.
 */
interface GalleryReadPort {
    suspend fun current(run: GalleryRun, source: SourceVersion): Boolean

    suspend fun fullDigest(run: GalleryRun, source: SourceVersion): FullDigest?

    suspend fun visualFeatures(
        run: GalleryRun,
        source: SourceVersion,
        version: FeatureVersion,
    ): FeatureRecord?
}

enum class RouteState { RESOLVED_DETERMINISTIC, AMBIGUOUS_CANDIDATE, NO_MATCH, UNSUPPORTED_OR_INACCESSIBLE }

enum class RouteReason {
    EXACT_DIGEST,
    CONSISTENT_VISUAL,
    CONFLICTING_HASHES,
    NO_CANDIDATE,
    WITHHELD_DETAIL,
    UNAVAILABLE,
}

/** Candidate ambiguity is separate from a quality tie: a tied keeper alone never requests learned work. */
data class CandidateRoute(
    val run: GalleryRun,
    val policy: GalleryPolicy,
    val participants: List<SourceVersion>,
    val state: RouteState,
    val reason: RouteReason,
)

enum class GroupEvidence { FULL_DIGEST, COMPLETE_LINK_VISUAL }

enum class KeeperReason { HIGHER_USABLE_RESOLUTION, SHARPER, LESS_CLIPPING }

sealed interface KeeperAdvice {
    data class Suggested(val source: SourceVersion, val reasons: List<KeeperReason>) : KeeperAdvice

    data object NoClearRecommendation : KeeperAdvice
}

/** Version is structural: no delimiter collisions or digest-derived action authority. */
data class GroupVersion(val run: GalleryRun, val policy: GalleryPolicy, val members: List<SourceVersion>)

data class GalleryGroup(val version: GroupVersion, val evidence: GroupEvidence, val advice: KeeperAdvice)

enum class AnalysisLimit { CANDIDATES, GROUP_SIZE, STALE_SOURCE, UNAVAILABLE_FEATURE, PARTIAL_SCOPE }

data class GalleryDiagnostics(
    val eligible: Int,
    val analyzed: Int,
    val skipped: Int,
    val exactComparisons: Int,
    val visualComparisons: Int,
    val candidateVisits: Int,
    val ambiguousPairs: Int,
    val candidateCapHits: Int,
    val groupCapHits: Int,
    val maxVisualComparisonsPerImage: Int,
)

/** Advisory only: no category, proof, selection, confirmation or file-action fields. */
data class GalleryResult(
    val run: GalleryRun,
    val groups: List<GalleryGroup>,
    val routes: List<CandidateRoute>,
    val limits: Set<AnalysisLimit>,
    val diagnostics: GalleryDiagnostics,
) {
    val complete: Boolean get() = limits.isEmpty()
}
