// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.devcheck.DeviceCheckCollector
import io.github.tomerar.keepclear.devcheck.DeviceReport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The collector runs end to end on Robolectric's phone, changes nothing and gives a report without paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceCheckCollectorTest {
    /**
     * KeepClear's real dependency graph on Robolectric's context, without its onCreate (which schedules WorkManager,
     * not set up in unit tests; robolectric.properties uses a plain Application for the same reason).
     */
    private val app = KeepClearApplication().apply {
        val attach = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
        attach.isAccessible = true
        attach.invoke(this, ApplicationProvider.getApplicationContext<android.app.Application>())
    }

    @After
    fun closeDatabase() = app.database.close()

    @Test
    fun `a full run reads only and its report has no paths`(): Unit = runBlocking {
        val trashBefore = app.database.trashDao().all()
        val jobsBefore = app.moveController.store.jobs()
        val testModeBefore = app.testMode.isOn
        val facts = DeviceCheckCollector(app).collect(now = 1_793_000_000_000)
        val text = DeviceReport.render(facts)
        assertTrue(text, text.startsWith("KeepClear device check"))
        assertTrue(text, text.trimEnd().endsWith(DeviceReport.FOOTER))
        assertFalse(text, '/' in text)
        assertEquals(BuildConfig.FLAVOR, facts.app.flavor)
        assertEquals(trashBefore, app.database.trashDao().all())
        assertEquals(jobsBefore, app.moveController.store.jobs())
        assertEquals(testModeBefore, app.testMode.isOn)
    }
}
