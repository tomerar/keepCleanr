// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.designsystem.component.CONFIRM_SHEET_BODY_TAG
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmFact
import io.github.tomerar.keepclear.core.designsystem.component.ConfirmSheet
import io.github.tomerar.keepclear.core.designsystem.component.DaysLeftChip
import io.github.tomerar.keepclear.core.designsystem.component.HERO_LABEL_TAG
import io.github.tomerar.keepclear.core.designsystem.component.HERO_NUMBER_TAG
import io.github.tomerar.keepclear.core.designsystem.component.HeroNumber
import io.github.tomerar.keepclear.core.designsystem.component.InfoChip
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.LaneCard
import io.github.tomerar.keepclear.core.designsystem.component.ResultBanner
import io.github.tomerar.keepclear.core.designsystem.component.StorageStrip
import io.github.tomerar.keepclear.core.designsystem.component.StorageUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xxhdpi")
class ComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun numberText(): String =
        compose.onNodeWithTag(HERO_NUMBER_TAG, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }

    @Test
    fun `lane card is one button whose label names the lane, the count and the size`() {
        var clicked = 0
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                LaneCard(KcLane.SAFE, "Clean now", count = "2,324 files", size = "7.9 GB", onClick = { clicked++ })
            }
        }
        compose.onNodeWithText("Clean now", substring = true)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher("merged label") { node ->
                    val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
                    "Clean now" in text && "2,324 files" in text && "7.9 GB" in text
                },
            )
            .performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `hero number is a heading and TalkBack hears the final value while it counts`() {
        var bytes by mutableLongStateOf(16_800_000_000)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeepClearTheme(reducedMotion = false) {
                HeroNumber(bytes = bytes, label = "free", format = { "${it / 100_000_000 / 10.0}GB" })
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(isHeading()).assertIsDisplayed()
        bytes = 24_700_000_000
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        // In the middle of counting: the screen shows an intermediate number, TalkBack the final one.
        assertNotEquals("24.7GB", numberText())
        // PR #46 review: the heading TalkBack focuses says the final value and what it is, once.
        compose.onNode(isHeading())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("24.7GB free")))
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals("24.7GB", numberText())
    }

    @Test
    fun `hero number jumps without animation under reduced motion`() {
        var bytes by mutableLongStateOf(16_800_000_000)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                HeroNumber(bytes = bytes, label = "free", format = { "${it / 100_000_000 / 10.0}GB" })
            }
        }
        compose.mainClock.advanceTimeByFrame()
        bytes = 24_700_000_000
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("24.7GB", numberText())
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `hero number at 200 percent font stays whole on one line`() {
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                HeroNumber(
                    bytes = 123_400_000_000,
                    label = "פנויים",
                    sentence = "אפשר לפנות בבטחה 7.9GB",
                )
            }
        }
        compose.waitForIdle()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(HERO_NUMBER_TAG, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.single()
        assertFalse("number overflows its width", layout.didOverflowWidth)
        assertFalse("number is clipped", layout.hasVisualOverflow)
        assertEquals(1, layout.lineCount)
        compose.onNodeWithTag(HERO_LABEL_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `reduced motion follows Android's Remove animations setting`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var seen: Boolean? = null
        compose.setContent { KeepClearTheme { seen = KeepClearTheme.reducedMotion } }
        compose.waitForIdle()
        assertEquals(true, seen)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertFalse(KcMotion.systemReducedMotion(context))
    }

    private fun stripPixels(direction: LayoutDirection): Pair<Int, Int> {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                KeepClearTheme(darkTheme = false, reducedMotion = true) {
                    Box(Modifier.width(300.dp).testTag("strip")) {
                        StorageStrip(StorageUsage(total = 100, free = 50), description = "half used")
                    }
                }
            }
        }
        val map = compose.onNodeWithTag("strip").captureToImage().toPixelMap()
        val y = map.height / 2
        return map[map.width / 10, y].toArgb() to map[map.width * 9 / 10, y].toArgb()
    }

    @Test
    fun `storage strip fills from the left in English`() {
        val (left, right) = stripPixels(LayoutDirection.Ltr)
        assertEquals(KeepClearColors.Light.stripUsed.toArgb(), left)
        assertEquals(KeepClearColors.Light.stripFree.toArgb(), right)
    }

    @Test
    fun `storage strip fills from the right in Hebrew (start side)`() {
        val (left, right) = stripPixels(LayoutDirection.Rtl)
        assertEquals(KeepClearColors.Light.stripFree.toArgb(), left)
        assertEquals(KeepClearColors.Light.stripUsed.toArgb(), right)
    }

    @Test
    fun `storage strip speaks its description`() {
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                StorageStrip(
                    StorageUsage(total = 100, free = 50, safe = 10),
                    description = "100 GB in total: 50 GB free",
                )
            }
        }
        compose.onNodeWithContentDescription("100 GB in total: 50 GB free").assertIsDisplayed()
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `confirm sheet scrolls at 200 percent font and keeps its buttons reachable`() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                ConfirmSheet(
                    title = "Move 2,324 files to the trash?",
                    dismissText = "Cancel",
                    onDismiss = { dismissed++ },
                    confirmButton = { KcButton("Move to the trash", { confirmed++ }) },
                    facts = listOf(
                        ConfirmFact("How many", "2,324 files"),
                        ConfirmFact("How much", "7.9 GB"),
                        ConfirmFact("Where it goes", "To the trash, 30 days"),
                    ),
                    note = "You can get them back from the trash for 30 days.",
                ) {
                    repeat(40) { Text("Screenshot_2023-03-$it.png") }
                }
            }
        }
        compose.onNode(isHeading()).assertIsDisplayed()
        compose.onNode(hasTestTag(CONFIRM_SHEET_BODY_TAG) and hasScrollAction()).assertExists()
        compose.onNodeWithText("Screenshot_2023-03-39.png").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Move to the trash").assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `result banner is announced politely and offers undo`() {
        var undone = 0
        var closed = 0
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                ResultBanner(
                    message = "Freed 7.9GB",
                    detail = "Now 24.7GB free (was 16.8GB)",
                    undoText = "Undo",
                    onUndo = { undone++ },
                    dismissLabel = "Close",
                    onDismiss = { closed++ },
                )
            }
        }
        compose.onNodeWithText("Freed 7.9GB", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, undone)
        assertEquals(1, closed)
    }

    @Test
    fun `info chip is a button and days-left chip reads its text`() {
        var opened = 0
        compose.setContent {
            KeepClearTheme(reducedMotion = true) {
                androidx.compose.foundation.layout.Column {
                    InfoChip("Why is this here?", onClick = { opened++ })
                    DaysLeftChip("23 days left", daysLeft = 23)
                }
            }
        }
        compose.onNodeWithText("Why is this here?")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        compose.onNodeWithText("23 days left").assertIsDisplayed()
        assertEquals(1, opened)
    }

    @Test
    fun `directional icons mirror in right-to-left, the others don't`() {
        val mirrored = mutableMapOf<String, Boolean>()
        compose.setContent {
            mirrored["back"] = KcIcons.Back.autoMirror
            mirrored["chevron"] = KcIcons.Chevron.autoMirror
            mirrored["undo"] = KcIcons.Undo.autoMirror
            mirrored["safe"] = KcIcons.Safe.autoMirror
            mirrored["trash"] = KcIcons.Trash.autoMirror
        }
        compose.waitForIdle()
        assertEquals(
            mapOf("back" to true, "chevron" to true, "undo" to true, "safe" to false, "trash" to false),
            mirrored,
        )
    }

    @Test
    fun `theme uses Paper and Ink, not Material's defaults`() {
        var background = Color.Unspecified
        var darkSafe = Color.Unspecified
        compose.setContent {
            KeepClearTheme(darkTheme = false) {
                background =
                    androidx.compose.material3.MaterialTheme.colorScheme.background
            }
            KeepClearTheme(darkTheme = true) { darkSafe = KeepClearTheme.colors.safe.accent }
        }
        compose.waitForIdle()
        assertEquals(Color(0xFFF6F2EA), background)
        assertEquals(Color(0xFF9DCDAE), darkSafe)
    }
}
