// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.engine.classify.Total
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PR H5 (UI-3): a review group with its sort and filter panel and the selection bar, Settings' rules list, test
 * mode's export, and the network drive form with its hints, in {en, he} × {light, dark}.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class H5ScreenshotTest(private val locale: String, private val night: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    private val shots get() = Shots(compose, locale, night)

    @Test
    fun groupTools() {
        val s = shots
        s.show { GroupScreen(OwnerPhone.summary.group("screenshots", Category.REVIEW)!!, {}, {}) }
        compose.onNodeWithTag("groupTools").performClick()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("year_2025"))
        compose.onNodeWithTag("year_2025").performClick()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("groupLargest"))
        compose.onNodeWithTag("groupLargest").performClick()
        compose.onNodeWithTag("largest_25").performClick()
        compose.onNodeWithTag("grid").performScrollToNode(hasTestTag("groupTools"))
        s.capture("group_tools")
    }

    @Test
    fun rules() {
        val s = shots
        val root = "/storage/emulated/0"
        val kept = "$root/Pictures/Screenshots"
        val junk = "$root/Download/Memes"
        // The owner's phone scanned with the rules in place, so each row shows what it matched.
        val classifier = io.github.tomerar.keepclear.engine.classify.Classifier(
            keptByUser = listOf(kept),
            junkFolders = listOf(junk),
        )
        val summary = io.github.tomerar.keepclear.engine.classify.ClassificationSummary.of(
            OwnerPhone.items.map { classifier.classify(it.item, now = OwnerPhone.NOW) },
        )
        s.show {
            SettingsScreen(
                false,
                {},
                {},
                {},
                SettingsActions(
                    choices = Choices(kept = setOf(kept, "$root/Documents/Tax"), junk = setOf(junk)),
                    summary = summary,
                ),
            )
        }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("keptByYou"))
        s.capture("rules")
    }

    @Test
    fun testExport() {
        val s = shots
        val rows = OwnerPhone.summary.groups(Category.SAFE).flatMap { it.items }.map {
            WouldDelete(it.item.path, it.item.size, forGood = false)
        }
        val total = Total(rows.size, rows.sumOf { it.bytes })
        s.show {
            CompositionLocalProvider(LocalTestExporter provides TestExporter(testModeOn = { true }) { }) {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    CleanResultBanner(
                        CleanResult(total, 0, 0, emptyList(), simulated = true, wouldDelete = rows),
                        { _, _ -> },
                        {},
                    )
                }
            }
        }
        compose.onNodeWithTag("testExport").performClick()
        s.capture("test_export")
    }

    @Test
    fun networkForm() {
        val s = shots
        // The same form, drawn on the screen: Material's AlertDialog never lets Compose idle under Robolectric's
        // native graphics (no AlertDialog had a screenshot before either).
        s.show {
            NetworkDriveDialog(saved = null, onConnect = {
            }, onDismiss = {}, testAccountNotice = false, asDialog = false)
        }
        s.capture("network_form")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}")
        fun params(): List<Array<Any>> = listOf("en", "iw").flatMap { l -> listOf(false, true).map { arrayOf(l, it) } }
    }
}
