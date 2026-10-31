// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Architecture rules from CLAUDE.md and design.md §1–2.
 * Pure-JVM modules grow with each milestone; add them to [pureJvmModules].
 */
class ArchitectureTest {
    private val pureJvmModules = listOf("core/model", "core/common")

    private val projectScope = Konsist.scopeFromProject()

    @Test
    fun `pure JVM modules do not import android`() {
        pureJvmModules.forEach { module ->
            Konsist.scopeFromModule(module).files.assertFalse { file ->
                file.imports.any { it.name.startsWith("android.") || it.name.startsWith("androidx.") }
            }
        }
    }

    @Test
    fun `every Kotlin file has an SPDX license header`() {
        projectScope.files.assertTrue { file ->
            file.text.lineSequence().take(3).any { it.contains("SPDX-License-Identifier: GPL-3.0-or-later") }
        }
    }

    @Test
    fun `no network APIs in app code (ADR 0008)`() {
        val forbidden = listOf("java.net.URL", "java.net.HttpURLConnection", "java.net.Socket", "okhttp3.")
        projectScope.files
            .filterNot { it.path.contains("/build-logic/") || it.path.contains("/testing/architecture/") }
            .assertFalse { file -> file.imports.any { imp -> forbidden.any { imp.name.startsWith(it) } } }
    }

    @Test
    fun `test functions return Unit, otherwise JUnit 5 silently skips them`() {
        projectScope.functions()
            .filter { fn -> fn.annotations.any { it.name == "Test" } }
            .assertTrue { fn -> fn.hasBlockBody || fn.returnType?.name == "Unit" }
    }
}
