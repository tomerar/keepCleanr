// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.devcheck.ReportText

/** Owner brief H3 item 3: how KeepClear's process ended unexpectedly last time. */
enum class ExitKind { CRASH, NATIVE_CRASH, ANR }

/** One unexpected end of KeepClear's process; [trace] is raw (redact it before showing or sharing). */
data class ExitRecord(val timestamp: Long, val kind: ExitKind, val trace: String? = null)

/** Where unexpected exits come from: Android's ApplicationExitInfo (API 30) in the app, a fake in tests. */
fun interface ExitInfoSource {
    fun exits(): List<ExitRecord>
}

/**
 * Android's record of KeepClear's own past exits (API 30, no permission, no dependency). An ANR's trace is the text
 * thread dump Android kept; a Java crash's trace is the one KeepClear wrote itself as it crashed ([CrashTraceStore]);
 * a native crash's tombstone is binary and is not read.
 */
class AndroidExitInfoSource(private val context: Context, private val saved: CrashTraceStore) : ExitInfoSource {
    override fun exits(): List<ExitRecord> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val infos = runCatching { am.getHistoricalProcessExitReasons(null, 0, MAX_EXITS) }.getOrDefault(emptyList())
        val own = saved.read()
        return infos.mapNotNull { info ->
            val kind = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH -> ExitKind.CRASH
                ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitKind.NATIVE_CRASH
                ApplicationExitInfo.REASON_ANR -> ExitKind.ANR
                else -> return@mapNotNull null
            }
            val trace = when (kind) {
                ExitKind.ANR -> runCatching {
                    info.traceInputStream?.use { input -> String(readAtMost(input, MAX_TRACE_BYTES), Charsets.UTF_8) }
                }.getOrNull()

                ExitKind.CRASH -> own?.takeIf { kotlin.math.abs(info.timestamp - it.first) <= MATCH_MILLIS }?.second

                ExitKind.NATIVE_CRASH -> null
            }
            ExitRecord(info.timestamp, kind, trace)
        }
    }

    private fun readAtMost(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < max) {
            val n = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_EXITS = 10
        const val MAX_TRACE_BYTES = 256 * 1024

        /** Our own trace is written a moment before Android records the exit. */
        const val MATCH_MILLIS = 60_000L
    }
}

/**
 * The stack trace of a Java crash, written by KeepClear's own uncaught-exception handler as it crashes (already
 * redacted), so the next launch can offer it. One small file in private storage, overwritten each time.
 */
class CrashTraceStore(private val file: java.io.File) {
    fun save(at: Long, error: Throwable) {
        file.parentFile?.mkdirs()
        file.writeText("$at\n" + CrashText.redact(error.stackTraceToString()))
    }

    /** (time, redacted trace), or null. */
    fun read(): Pair<Long, String>? = runCatching {
        if (!file.exists()) return null
        val text = file.readText()
        val at = text.substringBefore('\n').toLongOrNull() ?: return null
        at to text.substringAfter('\n')
    }.getOrNull()
}

/** Owner brief H3 item 3: which exit to offer, once each ([lastSeen] is the newest one already offered). */
object CrashCheck {
    fun pending(exits: List<ExitRecord>, lastSeen: Long): ExitRecord? =
        exits.filter { it.timestamp > lastSeen }.maxByOrNull { it.timestamp }

    /** The newest exit not offered yet; it is marked offered at once, so it is never offered twice. */
    fun take(source: ExitInfoSource, prefs: android.content.SharedPreferences): ExitRecord? {
        val lastSeen = prefs.getLong(KEY, 0L)
        val exits = runCatching { source.exits() }.getOrDefault(emptyList())
        val next = pending(exits, lastSeen) ?: return null
        prefs.edit(commit = true) { putLong(KEY, next.timestamp) }
        return next
    }

    const val PREFS = "crash"
    private const val KEY = "lastSeenExit"
}

/**
 * What a crash report may say. Stack frames ("at io.github…(File.kt:12)") stay; exception messages are dropped
 * (they can hold a path or a file name: "open failed: /storage/…/IMG_1234.jpg"), and anything path-like,
 * URI-like or named like a user's file is replaced. The device check's last pass then removes every '/'.
 */
object CrashText {
    private val header = Regex("""^(\s*(?:Caused by: |Suppressed: )?)([\w$.]+)(?::.*)?$""")
    private val path = Regex("""(?:[a-z]+://\S+)|(?:/[^\s:()]+)""", RegexOption.IGNORE_CASE)
    private val userFile = Regex(
        """[^\s()/:]+\.(?:jpe?g|png|gif|webp|heic|heif|mp4|mkv|3gp|mov|webm|mp3|m4a|aac|ogg|opus|wav|pdf|docx?|xlsx?|pptx?|txt|zip|rar|7z|apk|vcf|crypt\d*|db)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * [strict] (a Java crash): only stack frames and exception class names are kept, every other line (a message
     * that went on over several lines) is dropped. Not strict (an ANR's thread dump): other lines stay, with paths
     * and file names taken out.
     */
    fun redact(trace: String, strict: Boolean = true): String = trace.lines().mapNotNull { line ->
        val trimmed = line.trimStart()
        val kept = when {
            trimmed.startsWith("at ") || trimmed.startsWith("...") || trimmed.isEmpty() -> line

            else -> header.matchEntire(line)?.takeIf { isThrowable(it.groupValues[2]) }?.let {
                it.groupValues[1] +
                    it.groupValues[2]
            }
                ?: if (strict) null else line
        }
        kept?.replace(path, "<path>")?.replace(userFile, "<file>")
    }.joinToString("\n").let(ReportText::finalPass)

    /** An exception or error class name ("java.io.FileNotFoundException", "kotlin.KotlinNothingValueException"). */
    private fun isThrowable(name: String) = name.contains('.') &&
        (name.endsWith("Exception") || name.endsWith("Error") || name.endsWith("Throwable"))

    /** The text that is shared: a short header (app, device, kind, time in UTC) and the redacted trace. */
    fun report(record: ExitRecord, versionName: String, versionCode: Long, device: String, sdk: Int): String {
        val time = java.time.Instant.ofEpochMilli(record.timestamp).toString()
        val body =
            record.trace?.let { redact(it, strict = record.kind != ExitKind.ANR) }?.ifBlank { null }
                ?: "No stack trace was kept for this exit."
        return ReportText.finalPass(
            "KeepClear ${ReportText.clean(versionName)} ($versionCode) closed unexpectedly\n" +
                "Device: ${ReportText.clean(device)}, SDK $sdk\n" +
                "Kind: ${record.kind.name}\nTime: $time\n" +
                "No file names or paths are included.\n\n" + body + "\n",
        )
    }
}

/** Owner brief H3 item 3: the calm card on Home after an unexpected exit. */
@Composable
fun CrashCard(onShare: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    KcCard(modifier.testTag("crashCard"), containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.crash_body), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KcButton(
                stringResource(R.string.crash_share),
                onShare,
                Modifier.testTag("crashShare"),
                style = KcButtonStyle.Tonal,
                compact = true,
            )
            KcButton(
                stringResource(R.string.explainer_not_now),
                onDismiss,
                Modifier.testTag("crashDismiss"),
                style = KcButtonStyle.Text,
                compact = true,
            )
        }
    }
}
