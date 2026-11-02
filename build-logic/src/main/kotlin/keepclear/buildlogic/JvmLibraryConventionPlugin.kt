// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** Pure-JVM modules (engines, model, safety): no Android types allowed (Konsist-enforced). */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("java-test-fixtures")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        configureKotlinCompiler()
        registerDependencyConfinement()
        dependencies {
            add("testImplementation", platform(libs.lib("junit5-bom")))
            add("testImplementation", libs.lib("junit5-jupiter"))
            add("testImplementation", libs.lib("kotest-property"))
            add("testImplementation", libs.lib("kotest-assertions"))
            add("testRuntimeOnly", libs.lib("junit5-launcher"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // File names are UTF-8 on Android; make JVM tests match even on POSIX-locale machines.
            environment("LC_ALL", "C.UTF-8")
            systemProperty("file.encoding", "UTF-8")
            systemProperty("sun.jnu.encoding", "UTF-8")
        }
    }
}
