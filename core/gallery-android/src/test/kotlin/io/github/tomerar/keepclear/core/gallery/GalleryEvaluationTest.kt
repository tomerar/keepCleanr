// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import io.github.tomerar.keepclear.core.gallery.evaluation.CorpusIndex
import io.github.tomerar.keepclear.core.gallery.evaluation.EvaluationIds
import io.github.tomerar.keepclear.core.gallery.evaluation.EvaluationOutputs
import io.github.tomerar.keepclear.core.gallery.evaluation.GalleryEvaluation
import io.github.tomerar.keepclear.core.gallery.evaluation.MemorySample
import io.github.tomerar.keepclear.core.gallery.evaluation.runEvaluation
import io.github.tomerar.keepclear.engine.gallery.ContentKind
import io.github.tomerar.keepclear.engine.gallery.QualityFacts
import io.github.tomerar.keepclear.engine.gallery.VisualFacts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * C216 (GAL-26): the developer-only G2 harness's ID scheme, corpus index, status mapping and CSV/JSON writers, with a
 * fake decoder. Pipeline correctness only; nothing here measures photographic accuracy or device performance.
 */
class GalleryEvaluationTest {
    @get:Rule val temp = TemporaryFolder()

    /** Content-driven fake: "d:<hash>" decodes to features, other words select a production skip or an I/O error. */
    private val fake: suspend (InputStream) -> StillDecode = { stream ->
        val text = stream.use { it.readBytes().toString(Charsets.US_ASCII) }
        when {
            text.startsWith("d:") -> {
                val hash = text.removePrefix("d:").toLong()
                StillDecode.Decoded(VisualFacts(hash, hash, 1.5, false, QualityFacts(1_000_000, 1.0, 0.1)))
            }

            text == "format" -> {
                StillDecode.Skipped(StillSkip.UNSUPPORTED_FORMAT)
            }

            text == "variant" -> {
                StillDecode.Skipped(StillSkip.UNSUPPORTED_VARIANT)
            }

            text == "pixels" -> {
                StillDecode.Skipped(StillSkip.TOO_LARGE_IMAGE)
            }

            text == "damaged" -> {
                StillDecode.Skipped(StillSkip.DAMAGED)
            }

            else -> {
                throw IOException("unreadable")
            }
        }
    }

    @Test
    fun `image IDs follow the documented formula and are opaque tokens`() {
        // Golden values from the runbook's Python one-liner:
        // "i" + hashlib.sha256(("keepclear-g2-id-v1\n" + name).encode()).hexdigest()[:16]
        assertEquals("ifbd66cccb7b4faed", EvaluationIds.imageId("a.png"))
        assertEquals("i64c3d1bff15e3732", EvaluationIds.imageId("burst/IMG_0001.jpg"))
        assertTrue(Regex("i[0-9a-f]{16}").matches(EvaluationIds.imageId("x/y.png")))
    }

    @Test
    fun `the corpus index refuses paths, tokens and splits the scoring tool or the harness cannot trust`() {
        val header = "file,event_id,split,stratum"
        val good = CorpusIndex.parse(
            "\uFEFF$header,kind\r\nb.png,e1,holdout,camera,photo\na.png,e1,holdout,shots,text_sensitive\n",
        )
        assertEquals(listOf("a.png", "b.png").map(EvaluationIds::imageId).sorted(), good.map { it.imageId })
        assertEquals(ContentKind.TEXT_SENSITIVE, good.single { it.file == "a.png" }.kind)
        assertEquals(ContentKind.PHOTO, CorpusIndex.parse("$header\nb.png,e1,holdout,camera\n").single().kind)
        listOf(
            "$header\n../a.png,e1,holdout,c",
            "$header\n/sdcard/a.png,e1,holdout,c",
            "$header\nx\\a.png,e1,holdout,c",
            "$header\nx//a.png,e1,holdout,c",
            "$header\n./a.png,e1,holdout,c",
            "$header\n\"a.png\",e1,holdout,c",
            "$header\ncorpus-index.csv,e1,holdout,c",
            "$header\na.png,e1,holdout,c\na.png,e2,holdout,c",
            "$header\na.png,e 1,holdout,c",
            "$header\na.png,e1,test,c",
            "$header\na.png,e1,holdout,c\nb.png,e1,development,c",
            "$header\na.png,e1,holdout",
            "$header,kind\na.png,e1,holdout,c,screenshot",
            "file,event,split,stratum\na.png,e1,holdout,c",
            header,
            "",
        ).forEach { text -> assertThrows(text, IllegalArgumentException::class.java) { CorpusIndex.parse(text) } }
    }

    @Test
    fun `every indexed image gets an honest status, groups and candidates, and outputs carry no names`(): Unit =
        runBlocking {
            val corpus = temp.newFolder("corpus")

            fun put(name: String, text: String) = File(corpus, name).apply { parentFile!!.mkdirs() }.writeText(text)
            put("family/one.jpg", "d:0")
            put("family/two.jpg", "d:15") // 4 bits from one: grouped
            put("family/three.jpg", "d:65280") // 8 and 12 bits away: retrieved as a candidate, never grouped
            put("gif.jpg", "format")
            put("cmyk.jpg", "variant")
            put("huge-dims.png", "pixels")
            put("broken.png", "damaged")
            put("ioerror.png", "boom")
            put("empty.png", "")
            RandomAccessFile(File(corpus, "big.png"), "rw").use { it.setLength(BoundedStillDecoder.MAX_BYTES + 1L) }
            put("not-in-index.png", "d:0")
            val names = listOf(
                "family/one.jpg",
                "family/two.jpg",
                "family/three.jpg",
                "gif.jpg",
                "cmyk.jpg",
                "huge-dims.png",
                "broken.png",
                "ioerror.png",
                "empty.png",
                "big.png",
                "missing.png",
            )
            put(CorpusIndex.FILE_NAME, HEADER + names.joinToString("\n") { "$it,e1,holdout,s" })
            var opened = 0
            val evaluation = GalleryEvaluation(
                decode = {
                    opened++
                    fake(it)
                },
                memory = { MemorySample(10L + opened, 20L + opened) },
            )
            val output = File(temp.root, "out")
            val report = runEvaluation(
                evaluation,
                corpus,
                output,
                "unit",
                split = "holdout",
                passes = 2,
                maxImages = null,
                digests = true,
                device = { mapOf("model" to "fake") },
            )

            val manifest = File(output, "manifest.csv").readLines()
            assertEquals("image_id,event_id,split,stratum,status,skip_reason,sha256", manifest.first())
            val status = manifest.drop(1).associate { row -> row.split(',').let { it[0] to "${it[4]}/${it[5]}" } }

            fun of(name: String) = status.getValue(EvaluationIds.imageId(name))
            assertEquals("analyzed/", of("family/one.jpg"))
            assertEquals("analyzed/", of("family/three.jpg"))
            assertEquals("unsupported/unsupported_format", of("gif.jpg"))
            assertEquals("unsupported/unsupported_variant", of("cmyk.jpg"))
            assertEquals("skipped/image_too_large", of("huge-dims.png"))
            assertEquals("skipped/damaged", of("broken.png"))
            assertEquals("skipped/unreadable", of("ioerror.png"))
            assertEquals("skipped/empty", of("empty.png"))
            assertEquals("skipped/file_too_large", of("big.png"))
            assertEquals("not_processed/missing", of("missing.png"))
            assertEquals(names.size, status.size)
            assertEquals(8 * 2, opened) // empty, oversized and missing files are never opened, as in production

            // The digest column is filled for existing files only.
            fun digestOf(name: String) = manifest.single { it.startsWith(EvaluationIds.imageId(name)) }.split(',')[6]
            val digest = digestOf("family/one.jpg")
            assertTrue(Regex("[0-9a-f]{64}").matches(digest))
            assertEquals("", digestOf("missing.png"))

            val one = EvaluationIds.imageId("family/one.jpg")
            val two = EvaluationIds.imageId("family/two.jpg")
            val three = EvaluationIds.imageId("family/three.jpg")
            assertEquals(
                listOf("group_id,image_id", "g00001,${minOf(one, two)}", "g00001,${maxOf(one, two)}"),
                File(output, "groups.csv").readLines(),
            )
            val pairs = File(output, "candidates.csv").readLines()
            assertEquals("image_a,image_b", pairs.first())
            assertEquals(
                setOf(one to two, one to three, two to three).map { (a, b) -> "${minOf(a, b)},${maxOf(a, b)}" }.toSet(),
                pairs.drop(1).toSet(),
            )

            val json = File(output, "timing.json").readText()
            assertTrue("\"passesIdentical\": true" in json)
            assertTrue("\"calibrated\": false" in json)
            assertTrue("\"unindexedFiles\": 1" in json)
            assertTrue("\"peakJavaHeapBytes\": 18" in json) // the highest sample of pass 1 (eight opened)
            assertTrue("\"peakNativeHeapBytes\": 36" in json) // and of pass 2
            @Suppress("UNCHECKED_CAST")
            val passes = report["passes"] as List<Map<String, Any?>>
            assertEquals(2, passes.size)
            assertTrue((passes[0]["process"] as String).startsWith("process cold"))
            assertEquals("process warm", passes[1]["process"])
            @Suppress("UNCHECKED_CAST")
            val counts = passes[0]["counts"] as Map<String, Int>
            assertEquals(
                mapOf("indexed" to 11, "analyzed" to 3, "unsupported" to 2, "skipped" to 5, "not_processed" to 1),
                counts,
            )
            assertEquals(listOf("PARTIAL_SCOPE"), passes[0]["limits"]) // a missing file is never a complete scope

            // No output names a file, a folder or the corpus location.
            val written = output.listFiles()!!.joinToString("\n") { it.readText() }
            listOf("family", "one.jpg", "gif", "missing.png", "not-in-index", corpus.path).forEach {
                assertFalse(it, it in written)
            }
            // A second run never overwrites earlier results.
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    runEvaluation(evaluation, corpus, output, "unit", "holdout", 1, null, false, { emptyMap() })
                }
            }
        }

    @Test
    fun `each split is evaluated on its own and an unreadable digest never aborts the run`(): Unit = runBlocking {
        // Review of PR #75: splits used to be grouped together, which the scoring tool rejects and which let one split
        // change another's caps and groups; and a digest read failure aborted the whole run before any output.
        val corpus = temp.newFolder("splits")
        File(corpus, "dev-a.png").writeText("d:0")
        File(corpus, "dev-b.png").writeText("d:15")
        File(corpus, "hold-a.png").writeText("d:0") // identical features to dev-a: would group across splits
        File(corpus, "hold-b.png").writeText("d:1000003")
        File(corpus, CorpusIndex.FILE_NAME).writeText(
            HEADER + "dev-a.png,e1,development,s\ndev-b.png,e1,development,s\nhold-a.png,e2,holdout,s\n" +
                "hold-b.png,e3,holdout,s",
        )
        val output = File(temp.root, "hold")
        runEvaluation(
            GalleryEvaluation(decode = fake),
            corpus,
            output,
            "unit",
            split = "holdout",
            passes = 1,
            maxImages = null,
            digests = true,
            device = { emptyMap() },
            digest = { file ->
                if (file.name ==
                    "hold-b.png"
                ) {
                    throw java.io.IOException("unreadable")
                } else {
                    "f".repeat(64)
                }
            },
        )
        val manifest = File(output, "manifest.csv").readLines().drop(1)
        // The inventory stays complete: other splits are listed as not processed, never analysed or grouped.
        val rows = manifest.associate { row -> row.split(',').let { it[0] to "${it[2]}:${it[4]}/${it[5]}" } }
        assertEquals("holdout:analyzed/", rows[EvaluationIds.imageId("hold-a.png")])
        assertEquals("development:not_processed/other_split", rows[EvaluationIds.imageId("dev-a.png")])
        assertEquals("development:not_processed/other_split", rows[EvaluationIds.imageId("dev-b.png")])
        assertEquals(4, rows.size)
        assertEquals(listOf("group_id,image_id"), File(output, "groups.csv").readLines()) // no cross-split group

        fun digestOf(name: String) = manifest.single { it.startsWith(EvaluationIds.imageId(name)) }.split(',')[6]
        assertEquals("f".repeat(64), digestOf("hold-a.png"))
        assertEquals("", digestOf("hold-b.png"))
        assertEquals("f".repeat(64), digestOf("dev-a.png")) // digests cover other splits for leakage checks
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                runEvaluation(
                    GalleryEvaluation(decode = fake),
                    corpus,
                    File(temp.root, "none"),
                    "unit",
                    "validation",
                    1,
                    null,
                    false,
                    { emptyMap() },
                )
            }
        }
    }

    @Test
    fun `maxImages leaves the rest not processed and the scope partial`(): Unit = runBlocking {
        val corpus = temp.newFolder("limited")
        val names = (1..5).map { "p$it.png" }
        names.forEachIndexed { i, name -> File(corpus, name).writeText("d:${i * 1_000_003L}") }
        val index = HEADER + names.withIndex().joinToString("\n") { (i, name) -> "$name,e$i,development,s" }
        val pass = GalleryEvaluation(decode = fake).pass(1, corpus, CorpusIndex.parse(index), maxImages = 2)
        assertEquals(
            listOf("analyzed", "analyzed", "not_processed", "not_processed", "not_processed"),
            pass.outcomes.map { it.second.status },
        )
        assertTrue(pass.outcomes.drop(2).all { it.second.reason == "limit" })
        assertFalse(pass.grouping.complete)
        assertEquals(2, pass.imageNanos.size)
    }

    @Test
    fun `percentiles are nearest rank and undefined without data, JSON escapes and refuses unknown values`() {
        val nanos = (1..100).map { it * 1_000_000L }
        assertEquals(50.0, EvaluationOutputs.percentileMillis(nanos, 0.50)!!, 0.0)
        assertEquals(95.0, EvaluationOutputs.percentileMillis(nanos, 0.95)!!, 0.0)
        assertEquals(100.0, EvaluationOutputs.percentileMillis(nanos, 1.0)!!, 0.0)
        assertEquals(7.0, EvaluationOutputs.percentileMillis(listOf(7_000_000L), 0.99)!!, 0.0)
        assertNull(EvaluationOutputs.percentileMillis(emptyList(), 0.5))
        assertEquals(
            "{\n  \"a\": \"x\\\"y\\\\z\\u000a\",\n  \"b\": [1, null, true],\n  \"c\": {}\n}",
            EvaluationOutputs.json(
                linkedMapOf("a" to "x\"y\\z\n", "b" to listOf(1, Double.NaN, true), "c" to emptyMap<String, Int>()),
            ),
        )
        assertThrows(IllegalStateException::class.java) { EvaluationOutputs.json(Any()) }
    }

    private companion object {
        const val HEADER = "file,event_id,split,stratum\n"
    }
}
