// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure policy check for a merged AndroidManifest.xml.
 *
 * Rules (see docs/spec): ADR 0008 (no INTERNET, no accessibility service), ADR 0005 (restricted
 * permissions only in `foss`), SEC-5 (only the launcher activity is exported), PRIV-6 (every
 * requested permission is documented in docs/PRIVACY.md).
 */
object ManifestPolicy {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    val forbiddenEverywhere = setOf("android.permission.INTERNET")
    val forbiddenInPlay = setOf(
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.QUERY_ALL_PACKAGES",
    )

    /** Exported components are allowed only when guarded by one of these system permissions. */
    val allowedGuardPermissions = setOf("android.permission.DUMP")

    /**
     * Debug-only tooling activities injected by `debugImplementation` dependencies (Compose preview and
     * ui-test-manifest). Allowed in debug variants only; release variants are checked strictly.
     */
    val debugOnlyExports = setOf(
        "androidx.compose.ui.tooling.PreviewActivity",
        "androidx.activity.ComponentActivity",
    )

    fun check(
        manifestXml: String,
        flavor: String,
        applicationId: String,
        privacyPolicy: String,
        debuggable: Boolean = false,
    ): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifestXml.byteInputStream())
        val root = doc.documentElement
        val violations = mutableListOf<String>()

        val permissions = (root.children("uses-permission") + root.children("uses-permission-sdk-23"))
            .map { it.androidAttr("name") }
            .toSet()

        permissions.filter { it in forbiddenEverywhere }
            .forEach { violations += "Forbidden permission in every flavor (ADR 0008): $it" }
        if (flavor == "play") {
            permissions.filter { it in forbiddenInPlay }
                .forEach { violations += "Restricted permission in play flavor (ADR 0005): $it" }
        }

        val selfPermission = "$applicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        permissions.filter { it != selfPermission }.forEach { permission ->
            val shortName = permission.substringAfterLast('.')
            if (!privacyPolicy.contains("`$shortName`")) {
                violations += "Permission not documented in docs/PRIVACY.md (PRIV-6): $permission"
            }
        }

        val application = root.children("application").firstOrNull()
        if (application != null) {
            val components = listOf("activity", "activity-alias", "service", "receiver", "provider")
                .flatMap { application.children(it) }
            for (component in components) {
                val name = component.androidAttr("name")
                if (component.tagName == "service" &&
                    component.androidAttr("permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE"
                ) {
                    violations += "AccessibilityService is not allowed (ADR 0008): $name"
                }
                val debugTool = debuggable && name in debugOnlyExports
                if (component.androidAttr("exported") == "true" && !debugTool && !isAllowedExport(component)) {
                    violations += "Exported component not allowed (SEC-5): <${component.tagName}> $name"
                }
            }
        }
        return violations
    }

    private fun isAllowedExport(component: Element): Boolean {
        if (component.androidAttr("permission") in allowedGuardPermissions) return true
        if (component.tagName != "activity" && component.tagName != "activity-alias") return false
        return component.children("intent-filter").any { filter ->
            val actions = filter.children("action").map { it.androidAttr("name") }
            val categories = filter.children("category").map { it.androidAttr("name") }
            "android.intent.action.MAIN" in actions && "android.intent.category.LAUNCHER" in categories &&
                actions.size == 1
        }
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    }

    private fun Element.androidAttr(name: String): String = getAttributeNS(ANDROID_NS, name)

    fun checkFile(
        manifest: File,
        flavor: String,
        applicationId: String,
        privacyPolicy: File,
        debuggable: Boolean,
    ): List<String> = check(manifest.readText(), flavor, applicationId, privacyPolicy.readText(), debuggable)
}
