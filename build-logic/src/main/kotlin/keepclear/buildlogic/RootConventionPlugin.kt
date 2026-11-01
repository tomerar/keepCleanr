// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/** Root-level checks that span modules. */
class RootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        tasks.register<StringParityTask>("checkStringParity") {
            group = "verification"
            description = "Fails if English and Hebrew string resources have different keys (I18N-1)."
            rootDir.set(layout.projectDirectory)
        }
        tasks.register<CopyLintTask>("checkCopy") {
            group = "verification"
            description = "Fails if default-path strings use technical terms (P-2) or Hebrew writes Android in Latin."
            rootDir.set(layout.projectDirectory)
        }
    }
}

abstract class CopyLintTask : DefaultTask() {
    @get:Internal
    abstract val rootDir: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val problems = CopyLint.checkTree(rootDir.get().asFile)
        if (problems.isNotEmpty()) {
            throw GradleException("Copy lint:\n" + problems.joinToString("\n") { " - $it" })
        }
        logger.lifecycle("Copy lint OK.")
    }
}

abstract class StringParityTask : DefaultTask() {
    @get:Internal
    abstract val rootDir: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val violations = StringParity.checkTree(rootDir.get().asFile)
        if (violations.isNotEmpty()) {
            throw GradleException("String parity violations:\n" + violations.joinToString("\n") { " - $it" })
        }
        logger.lifecycle("String parity OK (en ↔ he).")
    }
}
