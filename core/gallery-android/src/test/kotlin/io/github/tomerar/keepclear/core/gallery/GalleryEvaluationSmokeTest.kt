// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.graphics.Bitmap
import android.graphics.Color
import io.github.tomerar.keepclear.core.gallery.evaluation.CorpusIndex
import io.github.tomerar.keepclear.core.gallery.evaluation.EvaluationIds
import io.github.tomerar.keepclear.core.gallery.evaluation.GalleryEvaluation
import io.github.tomerar.keepclear.core.gallery.evaluation.runEvaluation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * SYNTHETIC SMOKE RUN (C216): generated PNG/JPEG fixtures through the real BoundedStillDecoder, canonical features,
 * GalleryGrouping and the harness writers, under Robolectric's native graphics. A pipeline correctness check only:
 * it is not an accuracy, recall, precision, timing or memory result, and it completes no part of G2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryEvaluationSmokeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `generated fixtures flow through the production pipeline into scorer inputs`(): Unit = runBlocking {
        val corpus = temp.newFolder("corpus")
        val checkerPng = encode(Bitmap.CompressFormat.PNG, checker)
        val stripes = encode(Bitmap.CompressFormat.PNG) { x, _ -> if (x % 16 < 8) 240 else 15 }
        val files = linkedMapOf(
            "a/checker.png" to checkerPng,
            "b/checker-copy.png" to checkerPng.copyOf(),
            "checker.jpg" to encode(Bitmap.CompressFormat.JPEG, checker),
            "stripes.png" to stripes,
            "animation.gif" to "GIF89a".toByteArray() + ByteArray(32),
            "cut.png" to checkerPng.copyOf(checkerPng.size / 2),
        )
        files.forEach { (name, bytes) -> File(corpus, name).apply { parentFile!!.mkdirs() }.writeBytes(bytes) }
        File(corpus, CorpusIndex.FILE_NAME).writeText(
            "file,event_id,split,stratum\n" +
                (files.keys + "gone.png").joinToString("\n") { "$it,e1,validation,synthetic" },
        )
        val output = File(temp.root, "out")
        runEvaluation(
            GalleryEvaluation(),
            corpus,
            output,
            "smoke",
            split = "validation",
            passes = 2,
            maxImages = null,
            digests = false,
            device = { emptyMap() },
        )

        val status = File(output, "manifest.csv").readLines().drop(1).associate { row ->
            row.split(',').let { it[0] to "${it[4]}/${it[5]}" }
        }

        fun of(name: String) = status.getValue(EvaluationIds.imageId(name))
        assertEquals("analyzed/", of("a/checker.png"))
        assertEquals("analyzed/", of("b/checker-copy.png"))
        assertEquals("analyzed/", of("checker.jpg"))
        assertEquals("analyzed/", of("stripes.png"))
        assertEquals("unsupported/unsupported_format", of("animation.gif"))
        assertEquals("skipped/damaged", of("cut.png"))
        assertEquals("not_processed/missing", of("gone.png"))

        val groups = File(output, "groups.csv").readLines().drop(1)
            .groupBy({ it.substringBefore(',') }, { it.substringAfter(',') })
        val copies = setOf(EvaluationIds.imageId("a/checker.png"), EvaluationIds.imageId("b/checker-copy.png"))
        // Identical pixels share one group; the different picture is never in it.
        assertTrue(groups.values.any { it.toSet().containsAll(copies) })
        assertTrue(groups.values.none { EvaluationIds.imageId("stripes.png") in it && it.any { id -> id in copies } })
        val candidates = File(output, "candidates.csv").readLines().drop(1).toSet()
        assertTrue(copies.sorted().joinToString(",") in candidates)
        assertTrue("\"passesIdentical\": true" in File(output, "timing.json").readText())
    }

    private val checker: (Int, Int) -> Int = { x, y -> if ((x / 8 + y / 8) % 2 == 0) 220 - x else 30 + y }

    private fun encode(format: Bitmap.CompressFormat, luminance: (Int, Int) -> Int): ByteArray {
        val width = 64
        val height = 48
        val pixels = IntArray(width * height) { i ->
            val v = luminance(i % width, i / width).coerceIn(0, 255)
            Color.rgb(v, v, v)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(format, 100, output))
        bitmap.recycle()
        return output.toByteArray()
    }
}
