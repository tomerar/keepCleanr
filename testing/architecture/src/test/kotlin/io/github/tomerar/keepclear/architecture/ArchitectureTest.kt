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
            "engine/gallery",
            "engine/move",
            "engine/restore",
            "backup/target-api",
            "backup/net",
        )

    private val projectScope = Konsist.scopeFromProject()

    @Test
    fun `gallery has only numeric read-only capabilities (GAL-3, ADR 0014)`() {
        val files = Konsist.scopeFromModule("engine/gallery").files.filter { "/src/main/" in it.path }
        check(files.isNotEmpty())
        files.assertFalse { file ->
            Regex("""\bexternal\s+fun\b|\btypealias\b""").containsMatchIn(file.text)
        }
        files.assertTrue { file ->
            file.imports.all {
                it.name.startsWith("kotlin.math.") || it.name.startsWith("kotlinx.coroutines.")
            }
        }
    }

    /**
     * G1b cache (GAL-13, T-G12, C208): gallery code reaches its cache only through the engine's `GalleryFeatureStore`
     * port, never the database module; the Room side holds only `GalleryDao`, whose queries name only `gallery_*`
     * tables, so a cache write or clear cannot reach trash, backup, move, scan or hash data.
     */
    @Test
    fun `gallery cache code cannot reach other tables or DAOs`() {
        listOf("engine/gallery", "core/gallery-android").forEach { module ->
            val files = Konsist.scopeFromModule(module).files
            check(files.isNotEmpty())
            files.assertFalse { file -> "io.github.tomerar.keepclear.core.database" in file.text }
        }
        val cache = Konsist.scopeFromModule("core/database").files.filter { it.path.endsWith("/GalleryCache.kt") }
        check(cache.size == 1)
        cache.assertFalse { file ->
            listOf("ScanDao", "HashCacheDao", "TrashDao", "MoveDao", "KeepClearDatabase", "RoomDatabase", "execSQL")
                .any { Regex("""\b$it\b""").containsMatchIn(file.text) }
        }
        cache.assertTrue { file ->
            val queries = Regex("""@Query\(\s*((?:"[^"]*"\s*\+?\s*)+)""").findAll(file.text)
                .map { m -> Regex(""""([^"]*)"""").findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] } }
                .toList()
            val tables = queries.flatMap { q ->
                Regex("""\b(?:FROM|INTO|UPDATE|JOIN|TABLE)\s+`?(\w+)""", RegexOption.IGNORE_CASE).findAll(q)
                    .map { it.groupValues[1] }.toList()
            }
            queries.size >= 9 && tables.isNotEmpty() && tables.all { it.startsWith("gallery_") }
        }
    }

    /**
     * G1b coordinator (GAL-15, E-G16, C213): gallery analysis is only ever user-started in the foreground. Nothing in
     * the gallery modules may schedule, restart on boot or run as a background job, and the coordinator, its
     * eligibility and read ports stay plain Kotlin (C210).
     */
    @Test
    fun `gallery work never schedules itself and the coordinator is plain Kotlin`() {
        listOf("engine/gallery", "core/gallery-android").forEach { module ->
            val files = Konsist.scopeFromModule(module).files.filter { "/src/main/" in it.path }
            check(files.isNotEmpty())
            files.assertFalse { file ->
                listOf(
                    "androidx.work",
                    "WorkManager",
                    "JobScheduler",
                    "AlarmManager",
                    "BOOT_COMPLETED",
                    "ForegroundService",
                    "startService",
                    "GlobalScope",
                ).any { it in file.text }
            }
        }
        val pure = Konsist.scopeFromModule("core/gallery-android").files.filter { file ->
            listOf("GalleryCoordinator.kt", "GalleryEligibility.kt", "GalleryReadPorts.kt").any {
                file.path.endsWith("/$it")
            }
        }
        check(pure.size == 3)
        pure.assertTrue { file ->
            file.imports.none { it.name.startsWith("android.") || it.name.startsWith("androidx.") }
        }
    }

    /**
     * GAL-11, AC-G8 (C209): the gallery cache lives in the app database (`databases/keepclear.db`), and app data never
     * goes to a cloud backup or a device transfer: `allowBackup=false`, and both rule files exclude the database
     * domain (and every other domain).
     */
    @Test
    fun `gallery cache stays out of cloud backup and device transfer`() {
        var root = java.io.File("").absoluteFile
        while (!java.io.File(root, "settings.gradle.kts").isFile) root = root.parentFile

        fun xml(path: String) = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }.newDocumentBuilder().parse(java.io.File(root, path))
        val android = "http://schemas.android.com/apk/res/android"
        val app = xml("app/src/main/AndroidManifest.xml").getElementsByTagName("application").item(0)
            as org.w3c.dom.Element
        check(app.getAttributeNS(android, "allowBackup") == "false")
        check(app.getAttributeNS(android, "dataExtractionRules") == "@xml/data_extraction_rules")
        check(app.getAttributeNS(android, "fullBackupContent") == "@xml/backup_rules")

        fun excluded(doc: org.w3c.dom.Document, section: String): Set<String> {
            val parent = doc.getElementsByTagName(section).item(0) as org.w3c.dom.Element
            val nodes = parent.getElementsByTagName("exclude")
            check(parent.getElementsByTagName("include").length == 0)
            return (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("domain") }.toSet()
        }
        val all = setOf("root", "file", "database", "sharedpref", "external")
        val extraction = xml("app/src/main/res/xml/data_extraction_rules.xml")
        check(excluded(extraction, "cloud-backup") == all)
        check(excluded(extraction, "device-transfer") == all)
        check(excluded(xml("app/src/main/res/xml/backup_rules.xml"), "full-backup-content") == all)
        val database = java.io.File(
            root,
            "core/database/src/main/kotlin/io/github/tomerar/keepclear/core/database/KeepClearDatabase.kt",
        ).readText()
        check("const val NAME = \"keepclear.db\"" in database && "Room.databaseBuilder(context," in database)
    }

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

    /** ADR 0008 as amended by ADR 0011: backup target modules and the shared `backup/net` are the only exception. */
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
     * ADR 0011 (amends ADR 0008): network code lives only in backup target modules (`backup/target-*`) and the
     * shared HTTP client (`backup/net`). Nothing else opens sockets, HTTP connections or SMB sessions.
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
     * W3 (owner 2026-11-02): OkHttp (and its Okio) is used only inside `backup/net`, behind `HostHttpClient`. The
     * target modules talk HTTP through that small API, so one strictly configured client is all there is.
     * build-logic's `checkDependencyConfinement` keeps other modules from even declaring the library.
     */
    @Test
    fun `OkHttp and Okio only in backup net`() {
        val okhttp = Regex("""^import (okhttp3|okio)\.""", RegexOption.MULTILINE)
        projectScope.files
            .filterNot {
                "/backup/net/" in it.path || "/build-logic/" in it.path || "/testing/architecture/" in it.path
            }
            .assertFalse { file -> okhttp.containsMatchIn(file.text) }
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
     * Device check (test builds, Settings › Advanced): strictly read-only. Its code may list folders, read sizes,
     * run the scanner and read KeepClear's own records, but never delete, move, rename or write anything, never
     * reach the action executor, the trash manager or the scan index, and never ask Android to clear or free space.
     */
    @Test
    fun `device check code is read-only`() {
        val forbidden = listOf(
            "ActionExecutor",
            "executor(",
            "SafetyGuard",
            "trashManager",
            "TrashManager",
            "scanIndex",
            "ScanIndex",
            "actionGateway",
            "mediaRequests",
            "MediaConsentHandler",
            "moveController.start",
            "putJob",
            "put(",
            "remove(",
            "delete(",
            ".rename(",
            "renameTo(",
            "deleteRecursively(",
            "Files.move(",
            "Files.delete",
            "Files.write",
            "Files.copy(",
            "copyTo(",
            ".create(",
            ".mkdir(",
            "mkdirs(",
            "createNewFile(",
            "writeText(",
            "writeBytes(",
            "appendText(",
            "OutputStream",
            "FileWriter",
            "openOutputStream",
            "openFileDescriptor",
            "Document(",
            "Request(",
            ".insert(",
            ".update(",
            "allocateBytes(",
            "ACTION_CLEAR_APP_CACHE",
            "ACTION_DELETE",
            "edit {",
            "edit(",
            "turnOff(",
            "turnOn(",
            "refreshGrantedFolders(",
            "forgetNetworkDrive(",
        )
        val files = projectScope.files.filter {
            "/app/src/main/kotlin/io/github/tomerar/keepclear/devcheck/" in it.path
        }
        // Guards against a move or rename silently emptying this rule.
        check(files.size >= 4) { "device check files not found: ${files.map { it.name }}" }
        files.assertFalse { file ->
            file.text.lineSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .any { line -> forbidden.any { it in line } }
        }
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
