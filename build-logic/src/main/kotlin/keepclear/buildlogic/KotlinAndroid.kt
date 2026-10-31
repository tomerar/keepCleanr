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
