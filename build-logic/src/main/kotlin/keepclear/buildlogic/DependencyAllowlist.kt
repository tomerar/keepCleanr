// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/**
 * PRIV-1: runtime dependencies must come from allowlisted groups, and never from
 * analytics, ads, crash-reporting or Google Play Services groups.
 */
object DependencyAllowlist {
    val deniedGroupPrefixes = listOf(
        "com.google.firebase",
        "com.google.android.gms",
        "com.google.android.play",
        "com.google.ads",
        "com.crashlytics",
        "io.sentry",
        "com.bugsnag",
        "com.facebook",
        "com.flurry",
        "com.appsflyer",
        "com.amplitude",
        "com.mixpanel",
        "io.branch",
        "com.adjust",
        "com.applovin",
        "com.unity3d",
        "com.onesignal",
        "io.embrace",
        "com.newrelic",
        "com.datadoghq",
    )

    val allowedGroupPrefixes = listOf(
        "androidx.",
        "org.jetbrains.kotlin",
        "org.jetbrains.kotlinx",
        "org.jetbrains.compose",
        "org.jetbrains:annotations",
        "com.google.guava:listenablefuture",
        "com.google.guava:guava",
        "com.google.guava:failureaccess",
        "org.jspecify",
        "com.google.errorprone:error_prone_annotations",
        "com.google.code.findbugs:jsr305",
        "org.checkerframework",
        "com.google.j2objc:j2objc-annotations",
        // M15 (ADR 0011, research 06 §3.1): SMB backups, used only by backup:target-smb. Exact modules, not groups.
        "com.hierynomus:smbj",
        "com.hierynomus:asn-one",
        "org.bouncycastle:bcprov-jdk18on",
        "org.slf4j:slf4j-api",
        "net.engio:mbassador",
    )

    /**
     * Exact `group:module` ids (no prefix match). W3 (owner 2026-11-02): OkHttp and its Okio for the network
     * backup targets, Apache-2.0; only `backup:net` may declare them ([DependencyConfinement]).
     */
    val allowedModules = setOf(
        "com.squareup.okhttp3:okhttp",
        "com.squareup.okio:okio",
        "com.squareup.okio:okio-jvm",
    )

    /** Returns violations for `group:module:version` ids. */
    fun check(moduleIds: Collection<String>): List<String> = moduleIds.sorted().mapNotNull { id ->
        val groupAndModule = id.substringBeforeLast(':')
        val group = groupAndModule.substringBefore(':')
        when {
            deniedGroupPrefixes.any { group == it || group.startsWith("$it.") } -> {
                "Denied dependency (PRIV-1): $id"
            }

            groupAndModule !in allowedModules && allowedGroupPrefixes.none { groupAndModule.startsWith(it) } -> {
                "Dependency not on the allowlist (PRIV-1; discuss before adding): $id"
            }

            else -> {
                null
            }
        }
    }

    fun collectModuleIds(root: ResolvedComponentResult): List<String> {
        val seen = LinkedHashSet<String>()
        val visited = HashSet<ResolvedComponentResult>()
        val queue = ArrayDeque<ResolvedComponentResult>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!visited.add(component)) continue
            (component.id as? ModuleComponentIdentifier)?.let { seen += "${it.group}:${it.module}:${it.version}" }
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
        return seen.toList()
    }
}
