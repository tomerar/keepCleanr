// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** SET-3: opt-in, at most weekly, only when space is low; it only tells. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpaceReminderTest {
    @get:Rule
    val compose = createComposeRule()

    private val gb = 1000L * 1000 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun `only when on, low, and not shown in the last week`() {
        val total = 128 * gb
        // Low = under 10% of 128 GB (12.8 GB).
        assertTrue(ReminderPolicy.shouldRemind(true, 8 * gb, total, 0, now))
        assertFalse(ReminderPolicy.shouldRemind(false, 8 * gb, total, 0, now))
        assertFalse(ReminderPolicy.shouldRemind(true, 20 * gb, total, 0, now))
        assertFalse(ReminderPolicy.shouldRemind(true, 8 * gb, total, now - ReminderPolicy.WEEK_MILLIS + 1, now))
        assertTrue(ReminderPolicy.shouldRemind(true, 8 * gb, total, now - ReminderPolicy.WEEK_MILLIS, now))
        // A small phone: the 2 GB floor applies.
        assertTrue(ReminderPolicy.isLow(1_500_000_000, 16 * gb))
    }

    @Test
    fun `the text gives exact numbers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val withJunk = SpaceReminder.text(context, 8 * gb, 3 * gb, Locale.US)
        assertTrue(withJunk, withJunk.contains("8") && withJunk.contains("3") && withJunk.contains("junk"))
        val without = SpaceReminder.text(context, 8 * gb, 0, Locale.US)
        assertFalse(without.contains("junk"))
    }

    @Test
    fun `English and Hebrew texts use no fear or boost words`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val banned = listOf("boost", "urgent", "danger", "critical", "warning", "דחוף", "סכנה", "אזהרה", "קריטי")
        for (tag in listOf("en", "iw")) {
            val config = android.content.res.Configuration(base.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            val context = base.createConfigurationContext(config)
            val texts = listOf(
                SpaceReminder.text(context, 8 * gb, 3 * gb, Locale.forLanguageTag(tag)),
                SpaceReminder.text(context, 8 * gb, 0, Locale.forLanguageTag(tag)),
                context.getString(io.github.tomerar.keepclear.core.i18n.R.string.reminder_title),
            )
            for (t in texts) {
                assertTrue("$tag: $t", banned.none { t.lowercase().contains(it) })
            }
        }
        val he = android.content.res.Configuration(base.resources.configuration)
        he.setLocale(Locale.forLanguageTag("iw"))
        val heText = SpaceReminder.text(
            base.createConfigurationContext(he),
            8 * gb,
            3 * gb,
            Locale.forLanguageTag("iw"),
        )
        assertTrue(heText, heText.contains("8") && heText.contains("3"))
    }

    @Test
    fun `Settings switch turns reminders on (off by default)`() {
        var turnedOn: Boolean? = null
        compose.setContent {
            KeepClearTheme {
                SettingsScreen(
                    testModeOn = true,
                    onTestModeOn = {},
                    onTestModeOff = {},
                    onBack = {},
                    actions = SettingsActions(onReminder = { turnedOn = it }),
                )
            }
        }
        compose.onNodeWithTag("reminderSwitch").performScrollTo().performClick()
        assertEquals(true, turnedOn)
        assertFalse(
            ReminderPrefs(
                ApplicationProvider.getApplicationContext<Context>()
                    .getSharedPreferences("reminder-test", Context.MODE_PRIVATE),
            ).enabled,
        )
    }
}
