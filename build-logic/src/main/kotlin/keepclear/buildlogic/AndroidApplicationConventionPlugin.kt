// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/**
 * Application module conventions: SDK levels, `foss`/`play` flavors (ADR 0005),
 * and the per-variant policy checks (merged manifest, dependency allowlist).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        registerDependencyConfinement()

        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            defaultConfig {
                applicationId = APPLICATION_ID
                targetSdk = KeepClearSdk.TARGET
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            flavorDimensions += "distribution"
            productFlavors {
                create("foss") { dimension = "distribution" }
                create("play") { dimension = "distribution" }
            }
            buildFeatures.buildConfig = true
            dependenciesInfo {
                // F-Droid/reproducibility: no Google-encrypted dependency blob (ADR 0005).
                includeInApk = false
                includeInBundle = false
            }
            buildTypes.configureEach {
                // Reproducible builds (M18): no git state in the APK. AGP would write the commit (or
                // "NO_VALID_GIT_FOUND" in a worktree or source tarball) to META-INF/version-control-info.textproto,
                // so the same source would give different APKs depending on how it was checked out.
                vcsInfo.include = false
            }
        }

        val privacyFile = rootProject.layout.projectDirectory.file("docs/PRIVACY.md")
        val policyTasks = mutableListOf<String>()
        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                val name = variant.name.replaceFirstChar { it.uppercase() }
                val manifestTask = tasks.register<ManifestPolicyTask>("check${name}ManifestPolicy") {
                    group = "verification"
                    description = "Checks the merged manifest of ${variant.name} against KeepClear policy."
                    mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    flavor.set(variant.flavorName ?: "")
                    applicationId.set(variant.applicationId)
                    debuggable.set(variant.buildType == "debug")
                    privacyPolicy.set(privacyFile)
                    val mergerName = listOfNotNull(variant.flavorName?.takeIf { it.isNotEmpty() }, variant.buildType)
                        .joinToString("-")
                    mergerReport.set(layout.buildDirectory.file("outputs/logs/manifest-merger-$mergerName-report.txt"))
                    reportFile.set(layout.buildDirectory.file("reports/policy/${variant.name}-manifest.txt"))
                }
                val depsTask = tasks.register<DependencyAllowlistTask>("check${name}DependencyAllowlist") {
                    group = "verification"
                    description = "Checks runtime dependencies of ${variant.name} against the allowlist (PRIV-1)."
                    componentIds.set(
                        variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map { root ->
                            DependencyAllowlist.collectModuleIds(root)
                        },
                    )
                    reportFile.set(layout.buildDirectory.file("reports/policy/${variant.name}-dependencies.txt"))
                }
                policyTasks += manifestTask.name
                policyTasks += depsTask.name
            }
        }
        tasks.register("checkPolicies") {
            group = "verification"
            description = "Runs all KeepClear policy checks for every variant."
            dependsOn(provider { policyTasks.toList() })
        }
        tasks.named("check") { dependsOn("checkPolicies") }
    }
}
