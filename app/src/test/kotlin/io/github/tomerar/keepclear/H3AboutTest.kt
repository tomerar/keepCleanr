// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.designsystem.KeepClearSchemes
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Owner brief H3 items 5 and 6: About (version, source, privacy, translations, the licences of every font, icon set
 * and runtime library, from the build files and THIRD_PARTY.md without a licence plugin), "What's new" only for a
 * listed bigger update, the paper splash and window (no green or white flash), and the Paper & Ink app icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H3AboutTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val root = generateSequence(File("").absoluteFile) {
        it.parentFile
    }.first { File(it, "settings.gradle.kts").exists() }

    /** `group:module` of every library a module puts on the APK's runtime classpath (implementation/api). */
    private fun runtimeModules(): Set<String> {
        val toml = File(root, "gradle/libs.versions.toml").readText()
        val aliases = Regex("""(?m)^([\w-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"""").findAll(toml)
            .associate { it.groupValues[1].replace('-', '.') to it.groupValues[2] }
        val used = root.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".git", "build-logic", "testing") }
            .filter { it.name == "build.gradle.kts" }
            .flatMap {
                Regex("""(?m)^\s*(?:implementation|api)\(libs\.([\w.]+)\)""").findAll(it.readText()).map { m ->
                    m.groupValues[1]
                }
            }
            .toSet()
        // The Compose convention plugin adds Compose (androidx) to every UI module.
        return used.map { aliases.getValue(it) }.toSet() + "androidx.compose.material3:material3"
    }

    @Test
    fun `every runtime library, font and icon set has its licence listed`() {
        val modules = runtimeModules()
        assertTrue(modules.toString(), "com.hierynomus:smbj" in modules && "androidx.core:core-splashscreen" in modules)
        // What the libraries above bring with them (the PRIV-1 allowlist's exact modules and groups).
        val transitive =
            setOf("com.hierynomus:asn-one", "org.slf4j:slf4j-api", "net.engio:mbassador", "com.squareup.okio:okio")
        val missing = (modules + transitive).filter { Licences.covering(it) == null }
        assertEquals(emptyList<String>(), missing)
        // THIRD_PARTY.md's rows: the two fonts and the icon set.
        val thirdParty = File(root, "THIRD_PARTY.md").readText()
        listOf(
            "Rubik font" to "font:rubik",
            "Heebo font" to "font:heebo",
            "Material Symbols" to "icons:material-symbols",
        )
            .forEach { (row, id) ->
                assertTrue(row, row in thirdParty)
                assertNotNull(id, Licences.covering(id))
            }
    }

    @Test
    fun `every licence text ships in the APK`() {
        LicenceText.entries.forEach { text ->
            val body = context.assets.open(text.asset).bufferedReader().use { it.readText() }
            assertTrue(text.name, body.length > 500)
        }
    }

    @Test
    fun `About shows the version and build and opens source, privacy, translations and a licence`() {
        val calls = mutableListOf<String>()
        compose.setContent {
            KeepClearTheme {
                AboutScreen(
                    AboutUi(
                        version = "0.0.1",
                        versionCode = 7,
                        onOpenSource = { calls += "source" },
                        onOpenPrivacy = { calls += "privacy" },
                        onTranslate = { calls += "translate" },
                    ),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("aboutVersion").assertExists()
        listOf("aboutSource", "privacy", "aboutTranslate").forEach {
            compose.onNodeWithTag(it).performScrollTo().performClick()
        }
        assertEquals(listOf("source", "privacy", "translate"), calls)
        assertEquals(Licences.all.size, compose.onAllNodesWithTag("licence").fetchSemanticsNodes().size)
    }

    @Test
    fun `What's new shows once after a listed bigger update, never on a first install`() {
        val entries = listOf(WhatsNewEntry(5, 1), WhatsNewEntry(9, 2))
        assertNull(WhatsNew.toShow(lastSeen = 0, current = 9, entries = entries))
        assertNull(WhatsNew.toShow(lastSeen = 5, current = 8, entries = entries))
        assertEquals(9L, WhatsNew.toShow(lastSeen = 5, current = 9, entries = entries)?.versionCode)
        assertEquals(5L, WhatsNew.toShow(lastSeen = 3, current = 7, entries = entries)?.versionCode)
        val prefs = context.getSharedPreferences("whats-new-test", 0).apply { edit().clear().commit() }
        assertNull(WhatsNew.take(prefs, current = 5, entries = entries)) // first install
        assertEquals(9L, WhatsNew.take(prefs, current = 9, entries = entries)?.versionCode)
        assertNull(WhatsNew.take(prefs, current = 9, entries = entries)) // once
        assertEquals(emptyList<WhatsNewEntry>(), WhatsNew.entries)
    }

    // ----------------------------------------------------------------------------- splash, window, icon (item 6)

    private fun color(themeRes: Int, attr: Int, c: Context = context): Int {
        val theme = c.resources.newTheme().apply { applyStyle(themeRes, true) }
        val value = TypedValue()
        assertTrue(theme.resolveAttribute(attr, value, true))
        return if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            value.data
        } else {
            c.getColor(value.resourceId)
        }
    }

    @Test
    fun `the window and the splash are paper, light and dark`() {
        val light = KeepClearSchemes.Light.background.toArgb()
        assertEquals(light, color(R.style.Theme_KeepClear, android.R.attr.windowBackground))
        val splash = androidx.core.splashscreen.R.attr.windowSplashScreenBackground
        assertEquals(light, color(R.style.Theme_KeepClear_Starting, splash))
        val post = TypedValue().also {
            context.resources.newTheme().apply { applyStyle(R.style.Theme_KeepClear_Starting, true) }
                .resolveAttribute(androidx.core.splashscreen.R.attr.postSplashScreenTheme, it, true)
        }
        assertEquals(R.style.Theme_KeepClear, post.resourceId)
        RuntimeEnvironment.setQualifiers("+night")
        val night = context.createConfigurationContext(context.resources.configuration)
        assertEquals(
            KeepClearSchemes.Dark.background.toArgb(),
            color(R.style.Theme_KeepClear, android.R.attr.windowBackground, night),
        )
        assertEquals(KeepClearSchemes.Dark.background.toArgb(), color(R.style.Theme_KeepClear_Starting, splash, night))
    }

    @Test
    fun `MainActivity starts on the splash theme`() {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            0,
        )
        assertEquals(R.style.Theme_KeepClear_Starting, info.theme)
    }

    private fun res(path: String) = File(root, "app/src/main/res/$path").readText()

    @Test
    fun `the launcher icon is option 1 on paper, drawn only in ink and the lane colours, with a monochrome layer`() {
        val launcher = res("mipmap-anydpi/ic_launcher.xml")
        assertTrue("@drawable/ic_launcher_option1_foreground" in launcher)
        assertTrue("<monochrome android:drawable=\"@drawable/ic_launcher_option1_monochrome\"" in launcher)
        assertTrue("#F6F2EA" in res("drawable/ic_launcher_background.xml"))
        val palette = (
            listOf(KeepClearSchemes.Light.primary) +
                io.github.tomerar.keepclear.core.designsystem.KeepClearColors.Light.let {
                    listOf(it.safe.accent, it.review.accent, it.move.accent)
                }
        ).map { "#%08X".format(it.toArgb()) }.toSet()
        for (n in 1..3) {
            val colours = Regex(
                """android:(?:fill|stroke)Color="(#[0-9A-F]+)"""",
            ).findAll(res("drawable/ic_launcher_option${n}_foreground.xml"))
                .map { it.groupValues[1] }.toSet()
            assertEquals("option $n", palette, colours)
            val mono = Regex(
                """Color="(#[0-9A-F]+)"""",
            ).findAll(res("drawable/ic_launcher_option${n}_monochrome.xml")).map {
                it.groupValues[1]
            }.toSet()
            assertEquals(setOf("#FF000000"), mono)
        }
        // The notification icon is the same mark, white only.
        val notification = Regex("""Color="(#[0-9A-F]+)"""").findAll(res("drawable/ic_notification.xml")).map {
            it.groupValues[1]
        }.toSet()
        assertEquals(setOf("#FFFFFFFF"), notification)
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
    }
}
