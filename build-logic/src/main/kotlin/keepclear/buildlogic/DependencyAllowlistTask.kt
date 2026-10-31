// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class DependencyAllowlistTask : DefaultTask() {
    @get:Input
    abstract val componentIds: ListProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun run() {
        val ids = componentIds.get()
        val violations = DependencyAllowlist.check(ids)
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(ids.sorted().joinToString("\n", postfix = "\n"))
        if (violations.isNotEmpty()) {
            throw GradleException("Dependency policy violations:\n" + violations.joinToString("\n") { " - $it" })
        }
    }
}
