// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestPolicyTest {
    private val appId = "io.github.tomerar.keepclear"
    private val privacy = """
        | `MANAGE_EXTERNAL_STORAGE` | ... |
        | `QUERY_ALL_PACKAGES` | ... |
        | `INTERNET` | (documented only to isolate the INTERNET rule in tests) |
    """.trimIndent()

    private fun manifest(permissions: List<String> = emptyList(), components: String = launcher) = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$appId">
          ${permissions.joinToString("\n") { "<uses-permission android:name=\"$it\"/>" }}
          <uses-permission android:name="$appId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
          <application>$components</application>
        </manifest>
        """.trimIndent()

    private val launcher = """
        <activity android:name=".MainActivity" android:exported="true">
          <intent-filter>
            <action android:name="android.intent.action.MAIN"/>
            <category android:name="android.intent.category.LAUNCHER"/>
          </intent-filter>
        </activity>
    """

    private fun check(xml: String, flavor: String = "foss", debuggable: Boolean = false) =
        ManifestPolicy.check(xml, flavor, appId, privacy, debuggable)

    @Test
    fun `clean manifest passes`() {
        assertEquals(emptyList<String>(), check(manifest()))
    }

    @Test
    fun `INTERNET fails in every flavor`() {
        for (flavor in listOf("foss", "play")) {
            val violations = check(manifest(listOf("android.permission.INTERNET")), flavor)
            assertTrue(violations.any { "INTERNET" in it && "ADR 0008" in it }, "$flavor: $violations")
        }
    }

    @Test
    fun `restricted permissions allowed in foss but not in play`() {
        val perms = listOf("android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.QUERY_ALL_PACKAGES")
        assertEquals(emptyList<String>(), check(manifest(perms), "foss"))
        assertEquals(2, check(manifest(perms), "play").count { "ADR 0005" in it })
    }

    @Test
    fun `undocumented permission fails PRIV-6`() {
        val violations = check(manifest(listOf("android.permission.CAMERA")))
        assertTrue(violations.single().contains("PRIV-6"))
    }

    @Test
    fun `exported receiver or service fails SEC-5`() {
        val extra = """
            <receiver android:name=".Evil" android:exported="true"/>
            <service android:name=".Svc" android:exported="true"/>
            <provider android:name=".Prov" android:exported="true"/>
        """
        assertEquals(3, check(manifest(components = launcher + extra)).count { "SEC-5" in it })
    }

    @Test
    fun `exported non-launcher activity fails SEC-5`() {
        val extra = """<activity android:name=".Deep" android:exported="true">
            <intent-filter><action android:name="android.intent.action.VIEW"/></intent-filter></activity>"""
        assertTrue(check(manifest(components = launcher + extra)).single().contains(".Deep"))
    }

    @Test
    fun `exported component guarded by DUMP is allowed`() {
        val extra = """<receiver android:name="androidx.profileinstaller.ProfileInstallReceiver"
            android:exported="true" android:permission="android.permission.DUMP"/>"""
        assertEquals(emptyList<String>(), check(manifest(components = launcher + extra)))
    }

    @Test
    fun `accessibility service fails ADR 0008`() {
        val extra = """<service android:name=".Acs" android:exported="false"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"/>"""
        assertTrue(check(manifest(components = launcher + extra)).single().contains("AccessibilityService"))
    }

    @Test
    fun `debug tooling activities are allowed only in debug variants`() {
        val extra = """<activity android:name="androidx.compose.ui.tooling.PreviewActivity" android:exported="true"/>"""
        assertEquals(emptyList<String>(), check(manifest(components = launcher + extra), debuggable = true))
        assertEquals(1, check(manifest(components = launcher + extra), debuggable = false).size)
    }

    @Test
    fun `fake USB provider is allowed only in debug variants`() {
        val extra = """<provider android:name="io.github.tomerar.keepclear.testing.usb.FakeUsbProvider" """ +
            """android:exported="true" android:authorities="x.testusb" />"""
        assertEquals(emptyList<String>(), check(manifest(components = launcher + extra), debuggable = true))
        assertEquals(1, check(manifest(components = launcher + extra), debuggable = false).size)
    }

    @Test
    fun `WorkManager job service guarded by BIND_JOB_SERVICE is allowed`() {
        val extra = """<service android:name="androidx.work.impl.background.systemjob.SystemJobService"
            android:exported="true" android:permission="android.permission.BIND_JOB_SERVICE"/>"""
        assertEquals(emptyList<String>(), check(manifest(components = launcher + extra)))
    }
}
