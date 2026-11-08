// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

internal val testRun = GalleryRun("run-1", "scan-1", 1)

internal fun source(id: Int, size: Long = id.toLong() + 100): SourceVersion =
    SourceVersion(SourceKey("primary", id.toString().padStart(6, '0')), size, 123, "generation-$id", "read-1")

internal fun facts(d: Long = 0, p: Long = d, quality: QualityFacts = QualityFacts(1_000_000, 1.0, 0.1)) =
    VisualFacts(d, p, 1.5, false, quality)

internal class ReadFake : GalleryReadPort {
    val records = mutableMapOf<SourceVersion, FeatureRecord>()
    val digests = mutableMapOf<SourceVersion, FullDigest>()
    val hidden = mutableSetOf<SourceVersion>()
    val visualReads = mutableListOf<SourceVersion>()
    val digestReads = mutableListOf<SourceVersion>()
    var beforeRead: (SourceVersion) -> Unit = {}
    var currentRun = testRun

    fun put(source: SourceVersion, facts: VisualFacts = facts()) {
        records[source] = FeatureRecord(source, FeatureVersion.V1, facts)
    }

    override suspend fun current(run: GalleryRun, source: SourceVersion): Boolean =
        run == currentRun && source !in hidden

    override suspend fun fullDigest(run: GalleryRun, source: SourceVersion): FullDigest? {
        digestReads += source
        beforeRead(source)
        return digests[source]
    }

    override suspend fun visualFeatures(
        run: GalleryRun,
        source: SourceVersion,
        version: FeatureVersion,
    ): FeatureRecord? {
        visualReads += source
        beforeRead(source)
        return records[source]
    }
}
