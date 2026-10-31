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
    private val pureJvmModules =
        listOf(
            "core/model",
            "core/common",
            "core/storage-api",
            "core/storage-file",
            "core/hash",
            "core/safety",
            "engine/scan",
            "engine/classify",
            "engine/trash",
        )

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

    /**
     * CLAUDE.md safety rule 3: destructive file APIs only in the choke point's collaborators.
     * Allowed: storage gateway implementations (`core/storage-*`), the trash engine (`engine/trash`),
     * and test code. `core/safety` itself delegates to [ActionHandler] and never deletes directly.
     */
    @Test
    fun `destructive APIs only in allowed modules`() {
        val destructive = listOf(
            ".delete(",
            ".rename(",
            ".renameTo(",
            "Files.move(",
            "Files.delete",
            "deleteRecursively(",
            "deleteDocument(",
            "moveDocument(",
            "createTrashRequest(",
            "createDeleteRequest(",
        )
        val allowed = listOf("/core/storage-", "/engine/trash/")
        projectScope.files
            .filter { "/src/main/" in it.path }
            .filterNot { file -> allowed.any { it in file.path } || "/build-logic/" in file.path }
            .assertFalse { file ->
                val code = file.text.lineSequence().filterNot {
                    it.trimStart().startsWith("//") ||
                        it.trimStart().startsWith("*")
                }
                code.any { line -> destructive.any { it in line } }
            }
    }
}
