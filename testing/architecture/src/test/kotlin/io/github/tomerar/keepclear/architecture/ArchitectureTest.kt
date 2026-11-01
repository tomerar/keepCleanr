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
            "engine/enrich",
            "engine/move",
            "engine/restore",
            "backup/target-api",
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

    /** ADR 0008 as amended by ADR 0011: backup target modules (and later `backup/net`) are the only exception. */
    @Test
    fun `no network APIs in app code (ADR 0008)`() {
        val forbidden = listOf("java.net.URL", "java.net.HttpURLConnection", "java.net.Socket", "okhttp3.")
        projectScope.files
            .filterNot { it.path.contains("/build-logic/") || it.path.contains("/testing/architecture/") }
            .filterNot { "/backup/target-" in it.path || "/backup/net/" in it.path }
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
     * backup target implementations (`backup/target-*`, which only touch the KeepClear folder on the drive), and
     * test code. `core/safety` itself delegates to [ActionHandler] and never deletes directly. `engine/move` is
     * checked by the next rule: it may only delete or rename its own files on the backup target.
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
        // testing/usb-provider: a debug-only fake USB drive working inside app-private storage.
        val allowed =
            listOf(
                "/core/storage-",
                "/engine/trash/",
                "/backup/target-",
                "/engine/move/",
                "/engine/restore/",
                "/testing/usb-provider/",
            )
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

    /**
     * ADR 0011 (amends ADR 0008): network code lives only in backup target modules (`backup/target-*`, later a
     * shared `backup/net`). Nothing else opens sockets, HTTP connections or SMB sessions.
     */
    @Test
    fun `network APIs only in backup target modules`() {
        val network = Regex(
            """import (java\.net\.(Socket|ServerSocket|URL|HttpURLConnection|DatagramSocket)|javax\.net\.|""" +
                """okhttp3\.|com\.hierynomus\.|android\.net\.http|java\.net\.http\.)""",
        )
        projectScope.files
            .filter { "/src/main/" in it.path }
            .filterNot { "/backup/target-" in it.path || "/backup/net/" in it.path || "/build-logic/" in it.path }
            .assertFalse { file -> network.containsMatchIn(file.text) }
    }

    /**
     * SAFETY-5: the MOVE engine never deletes or renames an original. Its only destructive calls are
     * `target.delete(` / `target.rename(` on its own temp files, damaged copies and manifests on the drive.
     * Originals are freed later through the executor (M10).
     */
    @Test
    fun `engine move only deletes or renames on the backup target`() {
        val destructive =
            Regex("""\.(delete|rename|renameTo)\(|Files\.(move|delete)|deleteRecursively\(|Document\(|Request\(""")
        val allowedCall = Regex("""\btarget\.(delete|rename)\(""")
        projectScope.files
            .filter { "/engine/move/src/main/" in it.path }
            .assertFalse { file ->
                file.text.lineSequence()
                    .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                    .any { line -> destructive.containsMatchIn(allowedCall.replace(line, "")) }
            }
    }

    /**
     * MOVE-17: restoring from a backup only adds files to the phone. Its only destructive calls are deleting its
     * own temporary file after a failed copy and renaming that temporary file to its final name.
     */
    @Test
    fun `engine restore only touches its own temp file`() {
        val destructive =
            Regex("""\.(delete|rename|renameTo)\(|Files\.(move|delete)|deleteRecursively\(|Document\(|Request\(""")
        val allowedCall = Regex("""\bphone\.(delete\(tempPath\)|rename\(temp, )""")
        projectScope.files
            .filter { "/engine/restore/src/main/" in it.path }
            .assertFalse { file ->
                file.text.lineSequence()
                    .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                    .any { line -> destructive.containsMatchIn(allowedCall.replace(line, "")) }
            }
    }

    /**
     * minSdk 30: `InputStream.readNBytes(int)` is API 33+, `skipNBytes` API 34+, `transferTo` API 33+. Android
     * Lint doesn't see pure-JVM modules, so production code uses `core.storage.Streams` instead.
     */
    @Test
    fun `no JDK stream methods missing on Android 11 and 12`() {
        val missing = listOf(".readNBytes(", ".skipNBytes(", ".transferTo(")
        projectScope.files
            .filter { "/src/main/" in it.path && !it.path.endsWith("/Streams.kt") }
            .assertFalse { file -> missing.any { it in file.text } }
    }

    /**
     * Review PR B: logcat is readable by anyone with `adb` and by bug-report tools; file names can be private
     * ("divorce papers.pdf"). No log call in production code may include a path, a name or a URI.
     */
    @Test
    fun `no file paths in log calls`() {
        val logCall = Regex("""Log\.[vdiwe]\((?:[^()]|\([^()]*\))*\)""")
        val pathish = listOf("displayPath", "Path", "path", ".absolute", "Uri", "uri", "name")
        projectScope.files
            .filter { "/src/main/" in it.path }
            .assertFalse { file ->
                logCall.findAll(file.text).any { call -> pathish.any { it in call.value.substringAfter(',') } }
            }
    }

    /**
     * Review PR B: plans are confirmed where the user taps. App code may confirm a plan only with a
     * `Confirmation` (never `confirm(now, session)`), and only UI code (not a ViewModel) may create one.
     */
    @Test
    fun `plans are confirmed by the user's tap, not stamped by a ViewModel`() {
        val appMain = projectScope.files.filter { "/app/src/main/" in it.path }
        appMain.assertFalse { file ->
            Regex("""\.confirm\(\s*System\.currentTimeMillis\(\)""").containsMatchIn(file.text)
        }
        appMain.filter { it.name.endsWith("ViewModel") || it.path.endsWith("ViewModel.kt") }
            .assertFalse { file -> "Confirmation.of(" in file.text || "userConfirmed(" in file.text }
    }
}
