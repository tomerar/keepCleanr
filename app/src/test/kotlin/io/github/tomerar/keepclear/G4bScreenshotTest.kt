// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * G4b (AC-G10): a comparison with one photo chosen for the trash, and the existing editable confirmation it opens, in
 * {en, he} × {light, dark}.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class G4bScreenshotTest(private val locale: String, private val night: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    private val shots get() = Shots(compose, locale, night)

    /** The tied group with its first photo chosen; the others stay offered while one is left. */
    private val chosen = GallerySamples.tied.let { g ->
        val paths = g.members.map { it.item.item.path }
        g.copy(version = "v1", chosen = setOf(paths[0]), choosable = paths.drop(1).toSet())
    }

    private fun screen() = shots.show {
        GalleryReviewGroupScreen(
            chosen,
            GalleryActions(onReviewChosen = {}),
            disposal = { null },
            onUnchoose = { _, _ -> },
            onChoicesDone = {},
            review = GalleryReviewActions(),
            result = null,
            onBack = {},
        )
    }

    @Test
    fun selection(): Unit = shots.run {
        screen()
        capture("gallery_compare_chosen")
    }

    @Test
    fun confirmation(): Unit = shots.run {
        screen()
        compose.onNodeWithTag(GALLERY_GROUP_TAG).performScrollToNode(hasTestTag("galleryDeleteChosen"))
        compose.onNodeWithTag("galleryDeleteChosen").performClick()
        // Design QA item 5: the picture shows the confirm button ready, after its 500 ms fill.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("confirmDelete").fetchSemanticsNodes()
                .any { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config }
        }
        capture("gallery_confirm")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}")
        fun params(): List<Array<Any>> = listOf("en", "iw").flatMap { l -> listOf(false, true).map { arrayOf(l, it) } }
    }
}
