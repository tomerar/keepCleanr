// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.safety.ActionPlan
import io.github.tomerar.keepclear.core.safety.ExecutionReport
import io.github.tomerar.keepclear.core.safety.OpKind
import io.github.tomerar.keepclear.core.safety.OpResult
import io.github.tomerar.keepclear.core.safety.PlannedOp
import io.github.tomerar.keepclear.core.storage.KPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SAFETY-9 on a real, freshly installed fossDebug app (CI installs it fresh): test mode is on, and the app's
 * own executor leaves a confirmed delete as a simulation, with the file untouched.
 */
@RunWith(AndroidJUnit4::class)
class TestModeOnDeviceTest {
    @Test
    fun freshDebugInstallIsInTestModeAndDeletesNothing(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KeepClearApplication
        assertTrue("a fresh fossDebug install starts in test mode", app.testMode.isOn)

        val file = File(app.filesDir, "test-mode/IMG_1.jpg").canonicalFile.apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(2048) { 7 })
        }
        val stat = app.fileGateway.stat(KPath.Local(file.path))!!
        val op = PlannedOp(stat.path, file.path, OpKind.TRASH, false, stat.size, stat.mtimeMillis, stat.identity)
        val plan = ActionPlan("p", listOf(op), "test").confirm(System.currentTimeMillis(), app.sessionId)
        val report = app.executor().run(plan)
        assertTrue("$report", report is ExecutionReport.Ran && report.results.single() is OpResult.Simulated)
        assertArrayEquals(ByteArray(2048) { 7 }, file.readBytes())
        file.parentFile!!.deleteRecursively()
    }
}
