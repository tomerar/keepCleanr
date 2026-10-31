// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ManifestPolicyTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyPolicy: RegularFileProperty

    @get:Input
    abstract val flavor: Property<String>

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val debuggable: Property<Boolean>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun run() {
        val violations = ManifestPolicy.checkFile(
            mergedManifest.get().asFile,
            flavor.get(),
            applicationId.get(),
            privacyPolicy.get().asFile,
            debuggable.get(),
        )
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(if (violations.isEmpty()) "OK\n" else violations.joinToString("\n", postfix = "\n"))
        if (violations.isNotEmpty()) {
            throw GradleException("Manifest policy violations:\n" + violations.joinToString("\n") { " - $it" })
        }
    }
}
