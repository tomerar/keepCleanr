// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.gallery.evaluation.CorpusIndex
import io.github.tomerar.keepclear.core.gallery.evaluation.GalleryEvaluation
import io.github.tomerar.keepclear.core.gallery.evaluation.MemorySample
import io.github.tomerar.keepclear.core.gallery.evaluation.runEvaluation
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * DEVELOPER-ONLY G2 evaluation harness (C216, GAL-26; runbook in docs/spec/gallery-benchmarks.md). It never ships:
 * it lives in this module's instrumentation APK. Without `-e galleryCorpus <dir>` (or without that directory) it is
 * skipped, so CI stays green without a corpus. It runs the production still pipeline over the developer's local
 * corpus, without the feature cache and without the network, and writes the scoring tool's CSVs plus an aggregate
 * timing/memory JSON to this test APK's external files dir (`gallery-g2/<label>/`). Outputs hold opaque IDs only.
 * Users never need this or a computer (product principle 1).
 */
@RunWith(AndroidJUnit4::class)
class GalleryEvaluationHarness {
    @Test
    fun evaluateDeveloperCorpus(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val corpusPath = args.getString("galleryCorpus")
        assumeTrue("developer-only harness: pass -e galleryCorpus <dir>", !corpusPath.isNullOrEmpty())
        val corpus = File(checkNotNull(corpusPath))
        assumeTrue("no corpus directory on this device", corpus.isDirectory)
        check(File(corpus, CorpusIndex.FILE_NAME).isFile) { "the corpus has no ${CorpusIndex.FILE_NAME}" }
        val context = InstrumentationRegistry.getInstrumentation().context
        val label = args.getString("galleryRunLabel") ?: "run"
        val output = File(checkNotNull(context.getExternalFilesDir(null)), "gallery-g2/$label")
        val runtime = Runtime.getRuntime()
        val evaluation = GalleryEvaluation(
            memory = { MemorySample(runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize()) },
        )
        runEvaluation(
            evaluation,
            corpus,
            output,
            label,
            passes = args.getString("galleryPasses")?.toInt() ?: 1,
            maxImages = args.getString("galleryMaxImages")?.toInt(),
            digests = args.getString("galleryDigests") == "true",
            device = { device(context) },
            afterPass = { mapOf("pssAtPassEndKb" to Debug.getPss()) },
        )
        Log.i("KeepClearG2", "G2 harness results written to gallery-g2/$label in the test APK's external files dir")
    }

    /** Device facts for the report: hardware/OS identity and state only, nothing personal. */
    private fun device(context: Context): Map<String, Any?> {
        val memory = ActivityManager.MemoryInfo()
        val activity = context.getSystemService(ActivityManager::class.java)
        activity.getMemoryInfo(memory)
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        fun battery(key: String) = battery?.getIntExtra(key, -1)?.takeIf { it >= 0 }
        return linkedMapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "socModel" to if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
            "sdkInt" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "securityPatch" to Build.VERSION.SECURITY_PATCH,
            "fingerprint" to Build.FINGERPRINT,
            "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
            "totalRamBytes" to memory.totalMem,
            "availableRamBytes" to memory.availMem,
            "lowRamDevice" to activity.isLowRamDevice,
            "memoryClassMb" to activity.memoryClass,
            "javaHeapMaxBytes" to Runtime.getRuntime().maxMemory(),
            "thermalStatus" to power.currentThermalStatus,
            "powerSaveMode" to power.isPowerSaveMode,
            "batteryPercent" to battery?.let {
                val level = battery(BatteryManager.EXTRA_LEVEL)
                val scale = battery(BatteryManager.EXTRA_SCALE)
                if (level != null && scale != null && scale > 0) level * 100 / scale else null
            },
            "batteryPlugged" to battery(BatteryManager.EXTRA_PLUGGED),
            "batteryTemperatureDeciC" to battery(BatteryManager.EXTRA_TEMPERATURE),
            "debuggable" to (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0),
        )
    }
}
