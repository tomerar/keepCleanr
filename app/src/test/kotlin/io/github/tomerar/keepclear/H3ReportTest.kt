// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.devcheck.AppFacts
import io.github.tomerar.keepclear.engine.classify.ClassificationSummary
import io.github.tomerar.keepclear.engine.classify.Classifier
import io.github.tomerar.keepclear.engine.scan.ScanItem
import io.github.tomerar.keepclear.engine.trash.TrashEntry
import io.github.tomerar.keepclear.engine.trash.TrashState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner brief H3 items 2 and 3: "Report a problem" (an email the user sees and edits, never a path, an optional
 * diagnostics file through KeepClear's FileProvider, nothing sent by the app) and the crash card after an unexpected
 * exit (ApplicationExitInfo, once per exit, a redacted trace through the share sheet).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class H3ReportTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Private things a phone holds; none may reach the report.
    private val secrets = listOf("/storage", "DCIM", "IMG_beach_secret", "divorce", "papers", "WhatsApp", "Media")

    private fun item(path: String, size: Long) =
        Classifier().classify(ScanItem(KPath.Local(path), path, size, 1_700_000_000_000), now = 1_800_000_000_000)

    /** A state full of paths: scanned items, trash entries, a drive label in the model name. */
    private fun stateWithPaths(): HomeUiState {
        val items = listOf(
            item("/storage/emulated/0/DCIM/Camera/IMG_beach_secret.jpg", 4_000_000),
            item("/storage/emulated/0/WhatsApp/Media/WhatsApp Documents/divorce papers.pdf", 2_000_000),
            item("/storage/emulated/0/DCIM/.thumbnails/123.jpg", 30_000),
        )
        val trash = TrashEntry(
            id = "t1",
            originalPath = "/storage/emulated/0/Documents/divorce papers.pdf",
            trashedPath = "/storage/emulated/0/.KeepClearTrash/t1/divorce papers.pdf",
            volumeRoot = "/storage/emulated/0",
            size = 2_000,
            mtimeMillis = 1,
            reason = "rule",
            deletedAt = 1,
            expiresAt = 2,
            deletedAtElapsed = 1,
            bootId = "b",
            state = TrashState.ACTIVE,
        )
        return HomeUiState(summary = ClassificationSummary.of(items), trash = TrashUi(entries = listOf(trash)))
    }

    private fun facts(state: HomeUiState) = ReportFacts.of(
        home = state,
        backups = 2,
        device = ReportFacts.Device("Google", "Pixel 8/Pro\u202E", "16", 36),
        app = AppFacts("0.0.1", 1, "foss", "debug"),
        language = "iw",
        testMode = true,
    )

    @Test
    fun `the email body has counts and versions, never a path or a file name`() {
        val state = stateWithPaths()
        val body = ProblemReport.body("מה קרה?", facts(state))
        val factsPart = body.substringAfter("---")
        assertFalse(factsPart, '/' in factsPart)
        secrets.forEach { assertFalse("$it in $body", it in body) }
        assertFalse("\u202E" in body)
        assertTrue(body.startsWith("מה קרה?"))
        assertTrue(body, "Test mode: on" in body)
        assertTrue(body, "Trash: 1 files" in body)
        assertTrue(body, "Backups made: 2" in body)
        assertTrue(body, "foss debug" in body)
        // The totals are the summary's: one SAFE thumbnail, the rest to choose or back up.
        assertTrue(body, "Clean now: ${state.summary!!.total(Category.SAFE).files} files" in body)
    }

    @Test
    fun `the email opens in an email app, with the file only when asked for`() {
        val plain = ProblemReport.intent("", "KeepClear: בעיה", "text", attachment = null)
        assertEquals(Intent.ACTION_SENDTO, plain.action)
        assertEquals("mailto:", plain.data.toString())
        // No address yet (C166): the user fills in "To".
        assertNull(plain.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("text", plain.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(plain.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))

        val uri = Uri.parse("content://io.github.tomerar.keepclear.test.reports/reports/keepclear-diagnostics.txt")
        val withFile = ProblemReport.intent(" reports@example.org ", "s", "text", uri)
        assertEquals(Intent.ACTION_SEND, withFile.action)
        assertEquals("mailto:", withFile.selector!!.data.toString())
        assertEquals(uri, withFile.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(withFile.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(listOf("reports@example.org"), withFile.getStringArrayExtra(Intent.EXTRA_EMAIL)!!.toList())
    }

    @Test
    fun `the project address is empty until the owner gives one, never a private address`() {
        assertEquals("", context.getString(io.github.tomerar.keepclear.core.i18n.R.string.report_email))
    }

    @Test
    fun `the sheet sends what the user edited, with the file only when ticked`() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            KeepClearTheme {
                ReportSheet(
                    ReportUi(body = "What happened?\n\n---\nfacts", onSend = {
                        b,
                        a,
                        ->
                        sent +=
                            b to a
                    }),
                )
            }
        }
        compose.onNodeWithTag("reportBody").performTextReplacement("It stopped at 40%.\n\n---\nfacts")
        compose.onNodeWithTag("reportSend").performClick()
        compose.onNodeWithTag("reportAttach").performScrollTo().performClick()
        compose.onNodeWithTag("reportSend").performClick()
        assertEquals(
            listOf("It stopped at 40%.\n\n---\nfacts" to false, "It stopped at 40%.\n\n---\nfacts" to true),
            sent,
        )
    }

    private lateinit var t: TestApp

    @After
    fun tearDown() {
        if (::t.isInitialized) t.close()
    }

    @Test
    fun `the diagnostics file is the device check without paths, in KeepClear's cache`(): Unit = runBlocking {
        t = TestApp(tmp.newFolder("phone"))
        t.file("DCIM/Camera/IMG_beach_secret.jpg")
        val uri = ProblemReport.writeDiagnostics(t.app)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.reports", uri.authority)
        val file = java.io.File(java.io.File(t.app.cacheDir, ProblemReport.REPORTS_DIR), ProblemReport.DIAGNOSTICS_NAME)
        val text = file.readText()
        assertTrue(text, text.startsWith("KeepClear device check"))
        assertFalse(text, '/' in text)
        assertFalse(text, "IMG_beach_secret" in text)
    }

    // --------------------------------------------------------------------------------------- crash (item 3)

    private class Source(var exits: List<ExitRecord>) : ExitInfoSource {
        override fun exits() = exits
    }

    @Test
    fun `an unexpected exit is offered once, and a newer one later`() {
        val prefs = context.getSharedPreferences("crash-test", 0).apply { edit().clear().commit() }
        val source = Source(listOf(ExitRecord(1_000, ExitKind.CRASH), ExitRecord(2_000, ExitKind.ANR)))
        assertEquals(2_000L, CrashCheck.take(source, prefs)?.timestamp)
        // Once per exit: not again on the next launch.
        assertNull(CrashCheck.take(source, prefs))
        source.exits = source.exits + ExitRecord(3_000, ExitKind.NATIVE_CRASH)
        assertEquals(ExitKind.NATIVE_CRASH, CrashCheck.take(source, prefs)?.kind)
        assertNull(CrashCheck.take(source, prefs))
        // A source that fails is no exit (never a crash at start).
        assertNull(CrashCheck.take({ throw SecurityException("no") }, prefs))
    }

    private val javaTrace = """
        java.io.FileNotFoundException: /storage/emulated/0/DCIM/Camera/IMG_beach_secret.jpg: open failed: ENOENT
        the folder divorce papers was gone
        	at libcore.io.IoBridge.open(IoBridge.java:574)
        	at io.github.tomerar.keepclear.engine.move.MoveEngine.copy(MoveEngine.kt:212)
        Caused by: android.system.ErrnoException: open failed: ENOENT (No such file or directory) content://media/external/images/1
        	... 12 more
    """.trimIndent()

    @Test
    fun `a crash trace keeps its frames and class names, never a message, a path or a file name`() {
        val text = CrashText.redact(javaTrace)
        assertTrue(text, "java.io.FileNotFoundException" in text)
        assertTrue(text, "at io.github.tomerar.keepclear.engine.move.MoveEngine.copy(MoveEngine.kt:212)" in text)
        assertTrue(text, "Caused by: android.system.ErrnoException" in text)
        assertFalse(text, '/' in text)
        listOf("IMG_beach", "DCIM", "divorce", "ENOENT", "content:", "media").forEach {
            assertFalse(
                "$it in $text",
                it in text,
            )
        }
    }

    @Test
    fun `an ANR dump keeps its thread lines, without paths or user files`() {
        val anr = """
            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72b
              at io.github.tomerar.keepclear.HomeViewModel.scan(HomeViewModel.kt:300)
              native: #00 pc 0004 /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)
              - waiting to lock <0x0a> held by thread 12 reading WhatsApp Voice 2026.opus
        """.trimIndent()
        val text = CrashText.redact(anr, strict = false)
        assertTrue(text, "\"main\" prio=5 tid=1 Blocked" in text)
        assertTrue(text, "HomeViewModel.scan(HomeViewModel.kt:300)" in text)
        assertFalse(text, '/' in text || "apex" in text || "opus" in text)
    }

    @Test
    fun `the shared report has a header and the redacted trace`() {
        val report = CrashText.report(
            ExitRecord(1_793_000_000_000, ExitKind.CRASH, javaTrace),
            "0.0.1",
            1,
            "Google Pixel 8",
            36,
        )
        assertTrue(report, report.startsWith("KeepClear 0.0.1 (1) closed unexpectedly"))
        assertTrue(report, "Kind: CRASH" in report && "MoveEngine.kt:212" in report)
        assertFalse(report, '/' in report || "IMG_beach" in report)
        val none = CrashText.report(ExitRecord(1, ExitKind.NATIVE_CRASH), "0.0.1", 1, "x", 30)
        assertTrue(none, "No stack trace was kept" in none)
    }

    @Test
    fun `KeepClear's own trace file is written redacted and read back`() {
        val store = CrashTraceStore(java.io.File(tmp.newFolder("crash"), "last-trace.txt"))
        assertNull(store.read())
        store.save(42, java.io.FileNotFoundException("/storage/emulated/0/DCIM/Camera/IMG_beach_secret.jpg"))
        val (at, text) = store.read()!!
        assertEquals(42L, at)
        assertTrue(text, "java.io.FileNotFoundException" in text)
        assertFalse(text, "IMG_beach" in text || '/' in text)
    }

    @Test
    fun `the crash card shares or goes away`() {
        var shared = 0
        var dismissed = 0
        compose.setContent { KeepClearTheme { CrashCard(onShare = { shared++ }, onDismiss = { dismissed++ }) } }
        compose.onNodeWithTag("crashShare").performClick()
        compose.onNodeWithTag("crashDismiss").performClick()
        assertEquals(1, shared)
        assertEquals(1, dismissed)
    }
}
