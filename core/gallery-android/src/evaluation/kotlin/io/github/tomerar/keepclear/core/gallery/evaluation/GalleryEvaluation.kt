// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery.evaluation

import io.github.tomerar.keepclear.core.gallery.BoundedStillDecoder
import io.github.tomerar.keepclear.core.gallery.StillDecode
import io.github.tomerar.keepclear.core.gallery.StillSkip
import io.github.tomerar.keepclear.engine.gallery.CompletedFeature
import io.github.tomerar.keepclear.engine.gallery.ContentKind
import io.github.tomerar.keepclear.engine.gallery.FeatureRecord
import io.github.tomerar.keepclear.engine.gallery.FeatureVersion
import io.github.tomerar.keepclear.engine.gallery.GalleryGrouping
import io.github.tomerar.keepclear.engine.gallery.GalleryPolicy
import io.github.tomerar.keepclear.engine.gallery.GalleryResult
import io.github.tomerar.keepclear.engine.gallery.GalleryRun
import io.github.tomerar.keepclear.engine.gallery.SourceKey
import io.github.tomerar.keepclear.engine.gallery.SourceVersion
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/*
 * Developer-only G2 evaluation harness core (C216, GAL-26). This directory is compiled into the module's JVM tests
 * and its instrumentation APK only, never into the app. It runs the production still pipeline (BoundedStillDecoder →
 * canonical features → GalleryGrouping with the default production policy) over a developer-provided local corpus
 * and writes the inputs of scripts/gallery-benchmark-score.py. Outputs carry opaque image IDs, statuses, groups,
 * candidate pairs and aggregate numbers only: never a path, a file name, pixel data or a feature value.
 * Nothing here uses the network, the feature cache or any file-action API.
 */

/** Opaque, stable image IDs. The same formula is documented as a one-line Python command in the runbook. */
object EvaluationIds {
    const val SCHEME = "keepclear-g2-id-v1"

    /** "i" + the first 16 hex digits of SHA-256("keepclear-g2-id-v1\n" + relative path with '/' separators). */
    fun imageId(relative: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$SCHEME\n$relative".toByteArray(Charsets.UTF_8))
        return "i" + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** One indexed corpus file. [file] is relative to the corpus directory and never leaves this process. */
class CorpusEntry(
    val file: String,
    val imageId: String,
    val eventId: String,
    val split: String,
    val stratum: String,
    val kind: ContentKind,
)

/**
 * The developer's `corpus-index.csv`: header `file,event_id,split,stratum[,kind]`, one row per image in the
 * reference scope. Plain comma-separated values without quoting; tokens follow the scoring tool's rules.
 */
object CorpusIndex {
    const val FILE_NAME = "corpus-index.csv"
    const val MAX_ROWS = 1_000_000
    private val token = Regex("[A-Za-z0-9_-]{1,128}")
    private val splits = setOf("development", "validation", "holdout")
    private val kinds = mapOf("photo" to ContentKind.PHOTO, "text_sensitive" to ContentKind.TEXT_SENSITIVE)
    private val required = listOf("file", "event_id", "split", "stratum")

    fun parse(text: String): List<CorpusEntry> {
        val lines = text.removePrefix("\uFEFF").split('\n').map { it.removeSuffix("\r") }.dropLastWhile { it.isEmpty() }
        require(lines.isNotEmpty()) { "corpus index is empty" }
        val header = lines.first().split(',')
        require(header.take(4) == required && header.drop(4).all { it == "kind" } && header.size <= 5) {
            "corpus index header must be file,event_id,split,stratum[,kind]"
        }
        require(lines.size - 1 in 1..MAX_ROWS) { "corpus index must have 1..$MAX_ROWS rows" }
        val files = HashSet<String>()
        val ids = HashSet<String>()
        val eventSplit = HashMap<String, String>()
        return lines.drop(1).mapIndexed { index, line ->
            val row = index + 2
            val cells = line.split(',')
            require(cells.size == header.size) { "corpus index row $row: wrong number of columns" }
            val file = cells[0]
            require(validRelative(file)) { "corpus index row $row: file must be a plain relative path" }
            require(files.add(file)) { "corpus index row $row: duplicate file" }
            val (event, split, stratum) = Triple(cells[1], cells[2], cells[3])
            require(listOf(event, split, stratum).all { token.matches(it) }) { "corpus index row $row: bad token" }
            require(split in splits) { "corpus index row $row: unknown split" }
            require(eventSplit.getOrPut(event) { split } == split) { "corpus index row $row: event crosses splits" }
            val kind = if (header.size == 5) {
                requireNotNull(kinds[cells[4]]) { "corpus index row $row: kind must be photo or text_sensitive" }
            } else {
                ContentKind.PHOTO
            }
            val id = EvaluationIds.imageId(file)
            require(ids.add(id)) { "corpus index row $row: image ID collision; rename the file" }
            CorpusEntry(file, id, event, split, stratum, kind)
        }.sortedBy { it.imageId }
    }

    private fun validRelative(file: String): Boolean {
        if (file.isEmpty() || file.length > 1024 || file.startsWith('/') || '\\' in file || '"' in file) return false
        if (file.any { it < ' ' || it == '\u007f' } || file == FILE_NAME) return false
        return file.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}

/** Manifest status and skip reason, in the scoring tool's vocabulary. Production names in comments. */
enum class EvaluationOutcome(val status: String, val reason: String) {
    ANALYZED("analyzed", ""),
    UNSUPPORTED_FORMAT("unsupported", "unsupported_format"), // StillSkip.UNSUPPORTED_FORMAT
    UNSUPPORTED_VARIANT("unsupported", "unsupported_variant"), // StillSkip.UNSUPPORTED_VARIANT
    FILE_TOO_LARGE("skipped", "file_too_large"), // StillSkip.TOO_LARGE_FILE, refused before opening as in production
    IMAGE_TOO_LARGE("skipped", "image_too_large"), // StillSkip.TOO_LARGE_IMAGE
    DAMAGED("skipped", "damaged"), // StillSkip.DAMAGED
    EMPTY("skipped", "empty"), // GallerySkip.EMPTY
    UNREADABLE("skipped", "unreadable"), // an I/O failure while reading
    MISSING("not_processed", "missing"), // indexed but not a regular file in the corpus
    OUTSIDE_CORPUS("not_processed", "outside_corpus"), // resolves (for example via a link) outside the corpus
    LIMIT("not_processed", "limit"), // beyond the developer's maxImages argument
    OTHER_SPLIT("not_processed", "other_split"), // another split than this run; listed for a complete inventory
    ;

    companion object {
        internal fun of(skip: StillSkip): EvaluationOutcome = when (skip) {
            StillSkip.UNSUPPORTED_FORMAT -> UNSUPPORTED_FORMAT
            StillSkip.UNSUPPORTED_VARIANT -> UNSUPPORTED_VARIANT
            StillSkip.TOO_LARGE_FILE -> FILE_TOO_LARGE
            StillSkip.TOO_LARGE_IMAGE -> IMAGE_TOO_LARGE
            StillSkip.DAMAGED -> DAMAGED
        }
    }
}

/** One memory reading. On a device: Runtime used Java heap and Debug.getNativeHeapAllocatedSize. */
data class MemorySample(val javaHeapBytes: Long, val nativeHeapBytes: Long)

/** The result of one pass. Every pass starts without any feature cache: each analyzed image is decoded anew. */
class EvaluationPass(
    val number: Int,
    val outcomes: List<Pair<CorpusEntry, EvaluationOutcome>>,
    val grouping: GalleryResult,
    /** Unordered pairs of image IDs the candidate index retrieved before comparison (CandidateProbe). */
    val candidates: Set<Pair<String, String>>,
    val stageNanos: Map<String, Long>,
    /** Per opened image: read + decode + orientation + resize + features, in nanoseconds. */
    val imageNanos: List<Long>,
    val peakJavaHeapBytes: Long,
    val peakNativeHeapBytes: Long,
    val memorySamples: Int,
)

/**
 * Runs one pass over [entries] (already sorted by image ID). [decode] is `BoundedStillDecoder::decode` on a device;
 * tests may pass a fake. [policy] stays the production default: the harness never calibrates (C217).
 */
class GalleryEvaluation internal constructor(
    private val decode: suspend (InputStream) -> StillDecode = BoundedStillDecoder::decode,
    private val nanoTime: () -> Long = System::nanoTime,
    private val memory: () -> MemorySample = { MemorySample(0, 0) },
    val policy: GalleryPolicy = GalleryPolicy(),
) {
    suspend fun pass(number: Int, corpus: File, entries: List<CorpusEntry>, maxImages: Int? = null): EvaluationPass {
        require(maxImages == null || maxImages >= 0)
        val root = corpus.canonicalFile.toPath()
        var peakJava = 0L
        var peakNative = 0L
        var samples = 0

        fun sample() {
            val m = memory()
            peakJava = maxOf(peakJava, m.javaHeapBytes)
            peakNative = maxOf(peakNative, m.nativeHeapBytes)
            samples++
        }
        sample()
        val outcomes = ArrayList<Pair<CorpusEntry, EvaluationOutcome>>(entries.size)
        val completed = ArrayList<CompletedFeature>()
        val imageNanos = ArrayList<Long>()
        val observation = "g2-pass-$number"
        val decodeStart = nanoTime()
        for ((index, entry) in entries.withIndex()) {
            val file = File(corpus, entry.file)
            val outcome = when {
                maxImages != null && index >= maxImages -> {
                    EvaluationOutcome.LIMIT
                }

                !file.canonicalFile.toPath().startsWith(root) -> {
                    EvaluationOutcome.OUTSIDE_CORPUS
                }

                !file.isFile -> {
                    EvaluationOutcome.MISSING
                }

                file.length() == 0L -> {
                    EvaluationOutcome.EMPTY
                }

                file.length() > BoundedStillDecoder.MAX_BYTES -> {
                    EvaluationOutcome.FILE_TOO_LARGE
                }

                else -> {
                    val size = file.length()
                    val start = nanoTime()
                    val decoded = try {
                        decode(FileInputStream(file))
                    } catch (_: IOException) {
                        null
                    }
                    imageNanos += nanoTime() - start
                    when (decoded) {
                        null -> {
                            EvaluationOutcome.UNREADABLE
                        }

                        is StillDecode.Skipped -> {
                            EvaluationOutcome.of(decoded.reason)
                        }

                        is StillDecode.Decoded -> {
                            val source = SourceVersion(SourceKey(VOLUME, entry.imageId), size, 0, null, observation)
                            completed += CompletedFeature(
                                FeatureRecord(source, FeatureVersion.V1, decoded.facts),
                                entry.kind,
                            )
                            EvaluationOutcome.ANALYZED
                        }
                    }
                }
            }
            outcomes += entry to outcome
            if (outcome != EvaluationOutcome.LIMIT) sample()
        }
        val decodeNanos = nanoTime() - decodeStart
        val candidates = HashSet<Pair<String, String>>()
        val groupStart = nanoTime()
        val grouping = GalleryGrouping.group(
            GalleryRun("g2-harness-$number", "g2-corpus", 1),
            completed,
            scopeComplete = outcomes.none { it.second.status == "not_processed" },
            current = { true },
            policy = policy,
            probe = { query, candidate ->
                val a = query.key.ref
                val b = candidate.key.ref
                candidates += if (a < b) a to b else b to a
            },
        )
        val groupNanos = nanoTime() - groupStart
        sample()
        return EvaluationPass(
            number,
            outcomes,
            grouping,
            candidates,
            linkedMapOf("readDecodeFeatures" to decodeNanos, "grouping" to groupNanos),
            imageNanos,
            peakJava,
            peakNative,
            samples,
        )
    }

    companion object {
        const val VOLUME = "g2-corpus"
    }
}

/** Writers for the scoring tool's CSVs and the aggregate report. Only IDs, tokens and numbers are written. */
object EvaluationOutputs {
    /**
     * Every analysed image of the pass, then [otherSplits] as `not_processed/other_split`: the scoring tool validates
     * every label against the full inventory and uses digests across splits to detect identical content (review of
     * PR #75). Grouping stays split-local.
     */
    fun manifest(
        pass: EvaluationPass,
        digests: Map<String, String>? = null,
        otherSplits: List<CorpusEntry> = emptyList(),
    ): String = buildString {
        append("image_id,event_id,split,stratum,status,skip_reason")
        if (digests != null) append(",sha256")
        append('\n')
        for ((entry, outcome) in pass.outcomes) {
            append(entry.imageId).append(',').append(entry.eventId).append(',').append(entry.split).append(',')
            append(entry.stratum).append(',').append(outcome.status).append(',').append(outcome.reason)
            if (digests != null) append(',').append(digests[entry.imageId].orEmpty())
            append('\n')
        }
        for (entry in otherSplits) {
            val outcome = EvaluationOutcome.OTHER_SPLIT
            append(entry.imageId).append(',').append(entry.eventId).append(',').append(entry.split).append(',')
            append(entry.stratum).append(',').append(outcome.status).append(',').append(outcome.reason)
            if (digests != null) append(',').append(digests[entry.imageId].orEmpty())
            append('\n')
        }
    }

    fun groups(pass: EvaluationPass): String = buildString {
        append("group_id,image_id\n")
        pass.grouping.groups.forEachIndexed { index, group ->
            val id = "g%05d".format(index + 1)
            group.version.members.forEach { append(id).append(',').append(it.key.ref).append('\n') }
        }
    }

    fun candidates(pass: EvaluationPass): String = buildString {
        append("image_a,image_b\n")
        pass.candidates.sortedWith(compareBy({ it.first }, { it.second })).forEach { (a, b) ->
            append(a).append(',').append(b).append('\n')
        }
    }

    /** Nearest-rank percentile in milliseconds; null when there is no observation (never zero). */
    fun percentileMillis(nanos: List<Long>, p: Double): Double? {
        if (nanos.isEmpty()) return null
        val sorted = nanos.sorted()
        val rank = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1] / 1_000_000.0
    }

    fun passReport(pass: EvaluationPass, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val outcomes = pass.outcomes.map { it.second }
        val result = pass.grouping
        val d = result.diagnostics
        return linkedMapOf(
            "pass" to pass.number,
            "featureCache" to "none (every analyzed image is decoded in this pass)",
            "stagesMillis" to pass.stageNanos.mapValues { it.value / 1_000_000.0 },
            "imageLatencyMillis" to linkedMapOf(
                "count" to pass.imageNanos.size,
                "p50" to percentileMillis(pass.imageNanos, 0.50),
                "p95" to percentileMillis(pass.imageNanos, 0.95),
                "p99" to percentileMillis(pass.imageNanos, 0.99),
                "max" to percentileMillis(pass.imageNanos, 1.0),
            ),
            "counts" to linkedMapOf(
                "indexed" to outcomes.size,
                "analyzed" to outcomes.count { it.status == "analyzed" },
                "unsupported" to outcomes.count { it.status == "unsupported" },
                "skipped" to outcomes.count { it.status == "skipped" },
                "not_processed" to outcomes.count { it.status == "not_processed" },
            ),
            "reasons" to EvaluationOutcome.entries.filter { it != EvaluationOutcome.ANALYZED }
                .associate { o -> o.reason to outcomes.count { it == o } },
            // Each analyzed image is exactly one software bitmap, recycled by BoundedStillDecoder before the next
            // image (one worker). This is a count by construction, not a measured live-bitmap number.
            "decodedBitmaps" to outcomes.count { it == EvaluationOutcome.ANALYZED },
            "groups" to result.groups.size,
            "groupedImages" to result.groups.sumOf { it.version.members.size },
            "candidatePairs" to pass.candidates.size,
            "limits" to result.limits.map { it.name }.sorted(),
            "diagnostics" to linkedMapOf(
                "eligible" to d.eligible,
                "analyzed" to d.analyzed,
                "visualComparisons" to d.visualComparisons,
                "candidateVisits" to d.candidateVisits,
                "ambiguousPairs" to d.ambiguousPairs,
                "candidateCapHits" to d.candidateCapHits,
                "groupCapHits" to d.groupCapHits,
                "maxVisualComparisonsPerImage" to d.maxVisualComparisonsPerImage,
            ),
            "memory" to linkedMapOf(
                "peakJavaHeapBytes" to pass.peakJavaHeapBytes,
                "peakNativeHeapBytes" to pass.peakNativeHeapBytes,
                "samples" to pass.memorySamples,
                "sampling" to "after every image and after grouping; a sampled peak, not a profiler trace",
            ),
        ) + extra
    }

    fun policyReport(policy: GalleryPolicy): Map<String, Any?> = linkedMapOf(
        "version" to policy.version,
        "preprocessing" to policy.features.preprocessing,
        "algorithm" to policy.features.algorithm,
        "dHashDistance" to policy.dHashDistance,
        "pHashDistance" to policy.pHashDistance,
        "maxComparisons" to policy.maxComparisons,
        "maxGroupSize" to policy.maxGroupSize,
        "calibrated" to false,
    )

    /** Minimal JSON for maps, lists, strings, numbers, booleans and null. Non-finite numbers become null. */
    fun json(value: Any?, indent: String = ""): String = when (value) {
        null -> "null"

        is Boolean -> value.toString()

        is Double -> if (value.isFinite()) value.toString() else "null"

        is Float -> json(value.toDouble(), indent)

        is Number -> value.toString()

        is String -> quote(value)

        is Map<*, *> -> if (value.isEmpty()) {
            "{}"
        } else {
            val inner = "$indent  "
            value.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
                inner + quote(k.toString()) + ": " + json(v, inner)
            }
        }

        is Iterable<*> -> value.joinToString(", ", "[", "]") { json(it, indent) }

        else -> error("unsupported JSON value")
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Full-file SHA-256 for the manifest's optional leakage column; computed outside the timed stages. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/**
 * Runs [passes] passes over the images of one [split] only and writes the four outputs to [output], which must be new
 * or empty (never overwritten). Splits are evaluated independently, so candidate caps and groups never depend on another
 * split and the scoring tool's `--split` accepts the output (review of PR #75). Returns the report map. [device] holds
 * caller-collected device facts (no personal data). [digest] may fail per file; that file's optional digest stays empty.
 */
suspend fun runEvaluation(
    evaluation: GalleryEvaluation,
    corpus: File,
    output: File,
    label: String,
    split: String,
    passes: Int,
    maxImages: Int?,
    digests: Boolean,
    device: () -> Map<String, Any?>,
    afterPass: (EvaluationPass) -> Map<String, Any?> = { emptyMap() },
    digest: (File) -> String = EvaluationOutputs::sha256,
): Map<String, Any?> {
    require(Regex("[A-Za-z0-9_-]{1,64}").matches(label)) { "run label must be a simple token" }
    require(passes in 1..10) { "passes must be 1..10" }
    require(!output.exists() || output.list().isNullOrEmpty()) { "output directory is not empty; use a new label" }
    val start = System.nanoTime()
    val indexFile = File(corpus, CorpusIndex.FILE_NAME)
    val all = CorpusIndex.parse(indexFile.readText(Charsets.UTF_8))
    val entries = all.filter { it.split == split }
    require(entries.isNotEmpty()) { "the corpus index has no image in split $split" }
    val indexed = all.map { it.file }.toHashSet()
    val unindexed = corpus.walkTopDown().maxDepth(32).filter { it.isFile }.count {
        val relative = it.relativeTo(corpus).invariantSeparatorsPath
        relative != CorpusIndex.FILE_NAME && relative !in indexed
    }
    val indexMillis = (System.nanoTime() - start) / 1_000_000.0
    val deviceBefore = device()
    val reports = ArrayList<Map<String, Any?>>()
    var first: Triple<String, String, String>? = null
    var firstPass: EvaluationPass? = null
    var identical = true
    for (number in 1..passes) {
        val pass = evaluation.pass(number, corpus, entries, maxImages)
        val texts =
            Triple(EvaluationOutputs.manifest(pass), EvaluationOutputs.groups(pass), EvaluationOutputs.candidates(pass))
        if (first == null) {
            first = texts
            firstPass = pass
        } else if (texts != first) {
            identical = false
        }
        val temperature = if (number ==
            1
        ) {
            "process cold (first pass in this instrumentation process)"
        } else {
            "process warm"
        }
        reports += EvaluationOutputs.passReport(pass, mapOf("process" to temperature) + afterPass(pass))
    }
    val root = corpus.canonicalFile.toPath()
    val sha = if (digests) {
        all.map { it to File(corpus, it.file) }
            .filter { (_, file) -> file.isFile && file.canonicalFile.toPath().startsWith(root) }
            .mapNotNull { (entry, file) ->
                // An unreadable file keeps its manifest status (skipped/unreadable) and an empty digest.
                try {
                    entry.imageId to digest(file)
                } catch (_: java.io.IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }.toMap()
    } else {
        null
    }
    val pass = checkNotNull(firstPass)
    output.mkdirs()
    val otherSplits = all.filter { it.split != split }.sortedBy { it.imageId }
    File(output, "manifest.csv").writeText(EvaluationOutputs.manifest(pass, sha, otherSplits), Charsets.UTF_8)
    File(output, "groups.csv").writeText(checkNotNull(first).second, Charsets.UTF_8)
    File(output, "candidates.csv").writeText(first.third, Charsets.UTF_8)
    val report = linkedMapOf(
        "harness" to "keepclear-g2-harness-v1",
        "purpose" to "developer-only G2 measurement; not a user feature and not an accuracy result by itself",
        "label" to label,
        "split" to split,
        "idScheme" to EvaluationIds.SCHEME,
        "policy" to EvaluationOutputs.policyReport(evaluation.policy),
        "indexMillis" to indexMillis,
        "indexedImages" to entries.size,
        "indexedImagesAllSplits" to all.size,
        "unindexedFiles" to unindexed,
        "maxImages" to maxImages,
        "digestsWritten" to digests,
        "passesIdentical" to identical,
        "deviceAtStart" to deviceBefore,
        "passes" to reports,
        "deviceAtEnd" to device(),
        // Whole harness wall time, including every pass, the optional digests and writing the CSVs.
        "totalMillis" to (System.nanoTime() - start) / 1_000_000.0,
    )
    File(output, "timing.json").writeText(EvaluationOutputs.json(report) + "\n", Charsets.UTF_8)
    return report
}
