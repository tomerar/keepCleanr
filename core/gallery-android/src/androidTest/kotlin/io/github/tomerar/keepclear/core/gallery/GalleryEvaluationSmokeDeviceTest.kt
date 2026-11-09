// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.gallery.evaluation.CorpusIndex
import io.github.tomerar.keepclear.core.gallery.evaluation.EvaluationIds
import io.github.tomerar.keepclear.core.gallery.evaluation.GalleryEvaluation
import io.github.tomerar.keepclear.core.gallery.evaluation.runEvaluation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * SYNTHETIC SMOKE RUN on Android's own ImageDecoder (C216): generated fixtures through the harness and the
 * production pipeline. Pipeline correctness only, never an accuracy, timing or memory result; it completes no part
 * of G2. Runs without any developer corpus and cleans up its private files.
 */
@RunWith(AndroidJUnit4::class)
class GalleryEvaluationSmokeDeviceTest {
    private val root =
        File(InstrumentationRegistry.getInstrumentation().context.cacheDir, "g2-smoke-${UUID.randomUUID()}")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun syntheticFixturesReachScorerInputs(): Unit = runBlocking {
        val corpus = File(root, "corpus").apply { mkdirs() }
        val checkerPng = encode(Bitmap.CompressFormat.PNG, checker)
        val files = linkedMapOf(
            "a/checker.png" to checkerPng,
            "b/checker-copy.png" to checkerPng.copyOf(),
            "checker.jpg" to encode(Bitmap.CompressFormat.JPEG, checker),
            "stripes.png" to encode(Bitmap.CompressFormat.PNG) { x, _ -> if (x % 16 < 8) 240 else 15 },
            "animation.gif" to "GIF89a".toByteArray() + ByteArray(32),
        )
        files.forEach { (name, bytes) -> File(corpus, name).apply { parentFile!!.mkdirs() }.writeBytes(bytes) }
        File(corpus, CorpusIndex.FILE_NAME).writeText(
            "file,event_id,split,stratum\n" +
                (files.keys + "gone.png").joinToString("\n") { "$it,e1,validation,synthetic" },
        )
        val output = File(root, "out")
        runEvaluation(
            GalleryEvaluation(),
            corpus,
            output,
            "smoke",
            passes = 1,
            maxImages = null,
            digests = true,
            device = { emptyMap() },
        )

        val status = File(output, "manifest.csv").readLines().drop(1).associate { row ->
            row.split(',').let { it[0] to "${it[4]}/${it[5]}" }
        }
        listOf("a/checker.png", "b/checker-copy.png", "checker.jpg", "stripes.png").forEach {
            assertEquals(it, "analyzed/", status.getValue(EvaluationIds.imageId(it)))
        }
        assertEquals("unsupported/unsupported_format", status.getValue(EvaluationIds.imageId("animation.gif")))
        assertEquals("not_processed/missing", status.getValue(EvaluationIds.imageId("gone.png")))
        val copies = listOf("a/checker.png", "b/checker-copy.png").map(EvaluationIds::imageId).sorted()
        val groups = File(output, "groups.csv").readLines().drop(1)
            .groupBy({ it.substringBefore(',') }, { it.substringAfter(',') })
        assertTrue(groups.values.any { it.containsAll(copies) })
        assertTrue(copies.joinToString(",") in File(output, "candidates.csv").readLines())
        val written = output.listFiles()!!.joinToString("\n") { it.readText() }
        assertTrue(listOf("checker", "stripes", "animation", corpus.path).none { it in written })
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
