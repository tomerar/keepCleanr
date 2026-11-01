// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.engine.classify.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M13: Apps screen (ONB-7 explainer, sizes, unused apps with Android's own uninstall). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class M13Test {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val month = 31L * 24 * 3_600_000

    @Test
    fun `the manifest asks for what Android's uninstall window and app sizes need`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        // Without REQUEST_DELETE_PACKAGES, Android (API 28+) ignores ACTION_DELETE from this app.
        assertEquals(
            setOf("android.permission.REQUEST_DELETE_PACKAGES", "android.permission.PACKAGE_USAGE_STATS"),
            requested.filter { "DELETE_PACKAGES" in it || "USAGE_STATS" in it }.toSet(),
        )
    }

    @Test
    fun `in trial mode Uninstall explains and never opens Android's uninstall window (SAFETY-9)`() {
        var uninstalled: String? = null
        val apps = listOf(AppInfo("old.game", "Old game", 2_000_000_000, 0, 0, now - 9 * month, now - 30 * month))
        compose.setContent {
            KeepClearTheme {
                AppsScreen(apps, now, AppsActions(onUninstall = { uninstalled = it }, testModeOn = true), onBack = {})
            }
        }
        compose.onNodeWithTag("uninstall_old.game").performClick()
        assertEquals(null, uninstalled)
        compose.onNodeWithTag("uninstallTrial_old.game").assertExists()
    }

    @Test
    fun `without usage access, the screen explains it first (ONB-7)`() {
        var asked = 0
        compose.setContent {
            KeepClearTheme { AppsScreen(null, now, AppsActions(onAllowUsageAccess = { asked++ }), onBack = {}) }
        }
        compose.onNodeWithTag("allowUsage").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `unused apps can be uninstalled through Android, others open app info`() {
        var uninstalled: String? = null
        var info: String? = null
        val apps = listOf(
            AppInfo("old.game", "Old game", 2_000_000_000, 0, 0, now - 9 * month, now - 30 * month),
            AppInfo("daily.app", "Daily", 500_000_000, 0, 10, now, now - 30 * month),
        )
        compose.setContent {
            KeepClearTheme {
                AppsScreen(
                    apps,
                    now,
                    AppsActions(onUninstall = {
                        uninstalled = it
                    }, onOpenAppInfo = { info = it }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("appsTotal").assertExists()
        compose.onNodeWithTag("unusedTotal").assertExists()
        compose.onNodeWithTag("uninstall_old.game").performClick()
        assertEquals("old.game", uninstalled)
        compose.onNodeWithTag("unused_daily.app").assertDoesNotExist()
        compose.onNodeWithTag("apps").performScrollToNode(hasTestTag("info_daily.app"))
        compose.onNodeWithTag("info_daily.app").performClick()
        assertEquals("daily.app", info)
    }

    @Test
    fun `one app at a time shows cache sizes when Android reports them (SAFE-10)`() {
        compose.setContent {
            KeepClearTheme {
                OneAppAtATimeScreen(
                    listOf(
                        AppEntry("a.b", "Alpha", cacheBytes = 812_000_000),
                    ),
                    emptyMap(),
                    AppActions(),
                    onBack = {
                    },
                )
            }
        }
        compose.onNodeWithTag("cache_a.b").assertExists()
    }
}
