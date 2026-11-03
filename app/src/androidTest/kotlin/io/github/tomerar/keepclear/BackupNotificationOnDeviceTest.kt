// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner brief H1 item 5 on a device (CI: the API 30 and API 36 emulators): the backup's progress notification is
 * posted and read back from Android. On Android 16 it is a Live Update (ProgressStyle with copy and check segments,
 * promotion requested, "42%" as the short text); before, the plain progress bar.
 */
@RunWith(AndroidJUnit4::class)
class BackupNotificationOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val id = 4_242

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        MoveNotifications.ensureChannel(context)
    }

    @After
    fun tearDown() {
        nm.cancel(id)
    }

    private fun posted(): Notification {
        val notice = CopyNotice(doneBytes = 42, totalBytes = 100, etaMillis = 41 * 60_000L)
        nm.notify(id, MoveNotifications.progress(context, notice))
        var found: Notification? = null
        val until = System.currentTimeMillis() + 5_000
        while (found == null && System.currentTimeMillis() < until) {
            found = nm.activeNotifications.firstOrNull { it.id == id }?.notification
            if (found == null) Thread.sleep(100)
        }
        assertNotNull("the notification was posted", found)
        return found!!
    }

    @Test
    fun progressNotificationIsALiveUpdateOnAndroid16AndPlainBefore() {
        val n = posted()
        val template = n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        // Pause and Stop on every version.
        assertEquals(2, n.actions?.size ?: 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            assertEquals("android.app.Notification\$ProgressStyle", template)
            assertTrue(n.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
            assertEquals("42%", n.shortCriticalText?.filter { it.isDigit() || it == '%' })
            // The segments: copy, then check.
            val style = Notification.Builder.recoverBuilder(context, n).style as Notification.ProgressStyle
            assertEquals(
                listOf(BackupSegments.COPY, BackupSegments.CHECK),
                style.progressSegments.map { it.length },
            )
            assertEquals(420, style.progress)
        } else {
            assertFalse(template.contains("ProgressStyle"))
            assertEquals(BackupSegments.COPY, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertEquals(420, n.extras.getInt(Notification.EXTRA_PROGRESS))
        }
    }
}
