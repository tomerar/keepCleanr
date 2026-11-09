// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.tomerar.keepclear.core.model.Category
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * G4a (AC-G10): the gallery comparison as the user sees it, in {en, he} × {light, dark}: the review lane's entry, a
 * run in progress, a partial (stopped, Selected photos) result, the groups list, a comparison with a reasoned
 * keeper, one with no clear recommendation, and no photo access.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class G4aScreenshotTest(private val locale: String, private val night: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    private val shots get() = Shots(compose, locale, night)

    private fun run(ui: GalleryUi, name: String) = shots.run {
        show { GalleryScreen(ui, GalleryActions(), onOpenGroup = {}, onBack = {}) }
        capture(name)
    }

    private fun group(group: GalleryGroupUi, name: String) = shots.run {
        show { GalleryGroupScreen(group, GalleryActions(), onBack = {}) }
        capture(name)
    }

    @Test
    fun entry(): Unit = shots.run {
        show {
            CategoryListScreen(OwnerPhone.summary, Category.REVIEW, onOpenGroup = {}, onBack = {}, onFindSimilar = {})
        }
        capture("gallery_entry")
    }

    @Test fun running(): Unit = run(GallerySamples.running, "gallery_running")

    @Test fun partial(): Unit = run(GallerySamples.partial, "gallery_partial")

    @Test fun groups(): Unit = run(GallerySamples.done, "gallery_groups")

    @Test fun noAccess(): Unit = run(GallerySamples.noAccess, "gallery_no_access")

    @Test fun compare(): Unit = group(GallerySamples.withKeeper, "gallery_compare")

    @Test fun compareNoRecommendation(): Unit = group(GallerySamples.tied, "gallery_compare_none")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}")
        fun params(): List<Array<Any>> = listOf("en", "iw").flatMap { l -> listOf(false, true).map { arrayOf(l, it) } }
    }
}
