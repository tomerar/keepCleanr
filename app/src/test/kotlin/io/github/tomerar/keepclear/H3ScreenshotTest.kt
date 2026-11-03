// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import io.github.tomerar.keepclear.core.i18n.R as S

/**
 * PR H3 (UI-3): Help (list and one topic), the report sheet, the crash card on Home, the activity history, About and
 * the three app icon options, in {en, he} × {light, dark}. Record with `./gradlew :app:recordRoborazziFossDebug`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class H3ScreenshotTest(private val locale: String, private val night: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    private val shots get() = Shots(compose, locale, night)

    @Test
    fun help() {
        val s = shots
        s.show { HelpScreen(HelpTopics.all(foss = true), onOpenTopic = {}, onBack = {}) }
        s.capture("help")
    }

    @Test
    fun helpTopic() {
        val s = shots
        s.show { HelpTopicScreen(HelpTopics.byId(HelpTopics.GET_BACK, foss = true)!!, onLink = {}, onBack = {}) }
        s.capture("help_topic")
    }

    @Test
    fun report() {
        val s = shots
        s.show {
            val facts = ReportFacts.of(
                home = OwnerPhone.home.copy(trash = OwnerPhone.trash),
                backups = 1,
                device = ReportFacts.Device("Google", "Pixel 8", "16", 36),
                app = io.github.tomerar.keepclear.devcheck.AppFacts("0.0.1", 1, "foss", "debug"),
                language = locale,
                testMode = true,
            )
            ReportSheet(ReportUi(body = ProblemReport.body(stringResource(S.string.report_prompt), facts)))
        }
        s.capture("report")
    }

    @Test
    fun crashCard() {
        val s = shots
        val state = OwnerPhone.home.copy(trash = OwnerPhone.trash)
        s.show {
            AppScreens(
                state = state,
                onOpen = {},
                onBack = {},
                home = {
                    HomeScreen(state, showDebugTools = false, topNotice = { CrashCard(onShare = {}, onDismiss = {}) })
                },
            )
        }
        s.capture("crash_card")
    }

    @Test
    fun history() {
        val s = shots
        s.show { HistoryScreen(ownerHistory, onBack = {}, zone = ZoneId.of("UTC")) }
        s.capture("history")
    }

    @Test
    fun historyEmpty() {
        val s = shots
        s.show { HistoryScreen(HistoryState(), onBack = {}, zone = ZoneId.of("UTC")) }
        s.capture("history_empty")
    }

    @Test
    fun about() {
        val s = shots
        s.show { AboutScreen(AboutUi(version = "0.0.1", versionCode = 1), onBack = {}) }
        s.capture("about")
    }

    @Test
    fun iconOptions() {
        val s = shots
        s.show { IconOptions() }
        s.capture("icon_options")
    }

    /** Owner brief H3 item 6: the three options at 108dp: full square, the launcher's circle, themed (monochrome). */
    @Composable
    private fun IconOptions() {
        val paper = Color(0xFFF6F2EA)
        val options = listOf(
            io.github.tomerar.keepclear.R.drawable.ic_launcher_option1_foreground to
                io.github.tomerar.keepclear.R.drawable.ic_launcher_option1_monochrome,
            io.github.tomerar.keepclear.R.drawable.ic_launcher_option2_foreground to
                io.github.tomerar.keepclear.R.drawable.ic_launcher_option2_monochrome,
            io.github.tomerar.keepclear.R.drawable.ic_launcher_option3_foreground to
                io.github.tomerar.keepclear.R.drawable.ic_launcher_option3_monochrome,
        )
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for (row in 0..2) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEachIndexed { i, (fg, mono) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val shape = when (row) {
                                0 -> RoundedCornerShape(0.dp)
                                else -> CircleShape
                            }
                            Box(
                                Modifier.size(108.dp).clip(shape).background(
                                    if (row == 2) MaterialTheme.colorScheme.primaryContainer else paper,
                                ),
                            ) {
                                if (row == 2) {
                                    Image(
                                        painterResource(mono),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                                        modifier = Modifier.size(108.dp),
                                    )
                                } else {
                                    Image(
                                        painterResource(fg),
                                        contentDescription = null,
                                        modifier = Modifier.size(108.dp),
                                    )
                                }
                            }
                            if (row == 0) Text("${i + 1}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val DAY = 86_400_000L
        private const val NOW = OwnerPhone.NOW

        /** The owner-shaped phone after a month: „פיניתם עד היום 18.4GB”. */
        val ownerHistory = HistoryState(
            events = listOf(
                HistoryEvent(NOW - 1 * DAY, HistoryKind.RESTORED_FROM_TRASH, 1, 4_200_000),
                HistoryEvent(NOW - 3 * DAY, HistoryKind.FREED_AFTER_BACKUP, 2_104, 9_000_000_000, 9_000_000_000),
                HistoryEvent(NOW - 4 * DAY, HistoryKind.BACKED_UP, 6_830, 31_000_000_000),
                HistoryEvent(NOW - 9 * DAY, HistoryKind.TRASH_EMPTIED, 120, 258_000_000, 258_000_000),
                HistoryEvent(NOW - 12 * DAY, HistoryKind.CACHES_TRIMMED, 0, 1_200_000_000, 1_200_000_000),
                HistoryEvent(NOW - 14 * DAY, HistoryKind.CLEANED, 2_324, 7_900_000_000, 7_900_000_000),
            ),
            totalFreed = 18_358_000_000,
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}")
        fun params(): List<Array<Any>> = listOf("en", "iw").flatMap { l -> listOf(false, true).map { arrayOf(l, it) } }
    }
}
