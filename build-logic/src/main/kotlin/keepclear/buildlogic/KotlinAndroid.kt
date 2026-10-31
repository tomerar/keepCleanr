// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

internal fun Project.configureKotlinAndroid(android: CommonExtension) {
    android.apply {
        compileSdk = KeepClearSdk.COMPILE
        defaultConfig.minSdk = KeepClearSdk.MIN
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
        lint.apply {
            abortOnError = true
            checkDependencies = true
            warningsAsErrors = true
            xmlReport = true
            // Third-party lint jars (e.g. androidx.lifecycle) compiled against older lint APIs; not our code.
            informational += "ObsoleteLintCustomCheck"
        }
        testOptions.unitTests.isIncludeAndroidResources = true
        testOptions.unitTests.all { test ->
            test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            // Robolectric on SDK 36 needs JDK internals (FileDescriptor via SharedSecrets) on JDK 17+.
            test.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
            test.environment("LC_ALL", "C.UTF-8")
            // Optional mirror for Robolectric's android-all downloads (set in ~/.gradle/gradle.properties).
            providers.gradleProperty("keepclear.robolectricRepoUrl").orNull?.let {
                test.systemProperty("robolectric.dependency.repo.url", it)
            }
        }
    }
    configureKotlinCompiler()
}

internal fun Project.configureKotlinCompiler() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions.allWarningsAsErrors.set(true)
    }
}
