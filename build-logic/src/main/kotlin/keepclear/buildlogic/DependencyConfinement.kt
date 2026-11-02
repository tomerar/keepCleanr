// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * PRIV-1 + ADR 0008/0011: some allowlisted libraries may be used by **one** module only. Network libraries live
 * only in the network modules (W3, owner 2026-11-02: OkHttp only in `backup:net`; SMB only in
 * `backup:target-smb`). Other modules may still get them transitively (the app, through a target module), but
 * never declare them, so they can't call them directly.
 */
object DependencyConfinement {
    /** `group:` or `group:module` prefix → the only projects that may declare it. */
    val rules: Map<String, Set<String>> = mapOf(
        "com.squareup.okhttp3:" to setOf(":backup:net"),
        "com.squareup.okio:" to setOf(":backup:net"),
        "com.hierynomus:" to setOf(":backup:target-smb"),
        "org.bouncycastle:" to setOf(":backup:target-smb"),
    )

    /** Violations for the `group:module` ids [projectPath] declares. */
    fun check(projectPath: String, declared: Collection<String>): List<String> = declared.distinct().sorted()
        .mapNotNull { id ->
            val allowed = rules.entries.firstOrNull { (prefix, _) -> "$id:".startsWith(prefix) }?.value
            if (allowed == null || projectPath in allowed) {
                null
            } else {
                "$projectPath declares $id, which only ${allowed.sorted().joinToString()} may use (PRIV-1, ADR 0011)"
            }
        }
}

abstract class DependencyConfinementTask : DefaultTask() {
    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val declared: ListProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun run() {
        val ids = declared.get()
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(ids.sorted().joinToString("\n", postfix = "\n"))
        val violations = DependencyConfinement.check(projectPath.get(), ids)
        if (violations.isNotEmpty()) {
            throw GradleException("Dependency confinement violations:\n" + violations.joinToString("\n") { " - $it" })
        }
    }
}

/** Registers `checkDependencyConfinement` (part of `check`) for this module's declared dependencies. */
internal fun Project.registerDependencyConfinement() {
    val modulePath = path // the project's, not the task's
    val task = tasks.register<DependencyConfinementTask>("checkDependencyConfinement") {
        group = "verification"
        description = "Fails if this module declares a library confined to another module (PRIV-1, ADR 0011)."
        projectPath.set(modulePath)
        declared.set(
            provider {
                configurations.flatMap { c ->
                    c.dependencies.withType<ExternalModuleDependency>().map { "${it.group}:${it.name}" }
                }.distinct()
            },
        )
        reportFile.set(layout.buildDirectory.file("reports/policy/declared-dependencies.txt"))
    }
    pluginManager.withPlugin("base") { tasks.named("check") { dependsOn(task) } }
}
