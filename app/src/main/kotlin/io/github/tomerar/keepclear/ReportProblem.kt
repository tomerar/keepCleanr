// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category
import io.github.tomerar.keepclear.devcheck.DeviceReport
import io.github.tomerar.keepclear.devcheck.ReportText
import io.github.tomerar.keepclear.engine.classify.Total

/**
 * Owner brief H3 item 2: what "Report a problem" says about the phone. Counts, sizes, versions and switches only:
 * there is no field that could hold a path or a file name (the summary's items, the trash's entries and the backup's
 * places are never read here), and every system string goes through [ReportText.clean].
 */
data class ReportFacts(
    val manufacturer: String,
    val model: String,
    val android: String,
    val sdk: Int,
    val versionName: String,
    val versionCode: Long,
    val flavor: String,
    val buildType: String,
    val language: String,
    val testMode: Boolean,
    /** null before the first scan. */
    val clean: Total? = null,
    val choose: Total? = null,
    val backUp: Total? = null,
    val trash: Total = Total(),
    val backups: Int = 0,
) {
    companion object {
        /** From the app's state: totals and counts only, by construction. */
        fun of(
            home: HomeUiState,
            backups: Int,
            device: Device,
            app: io.github.tomerar.keepclear.devcheck.AppFacts,
            language: String,
            testMode: Boolean,
        ): ReportFacts {
            val summary = home.summary
            return ReportFacts(
                manufacturer = device.manufacturer,
                model = device.model,
                android = device.release,
                sdk = device.sdk,
                versionName = app.versionName,
                versionCode = app.versionCode,
                flavor = app.flavor,
                buildType = app.buildType,
                language = language,
                testMode = testMode,
                clean = summary?.total(Category.SAFE),
                choose = summary?.total(Category.REVIEW),
                backUp = summary?.total(Category.MOVE),
                trash = Total(home.trash.entries.size, home.trash.bytes),
                backups = backups,
            )
        }
    }

    data class Device(val manufacturer: String, val model: String, val release: String, val sdk: Int)
}

object ProblemReport {
    /**
     * The email's body: the user's [prompt] with room to write, then the facts in English for the developer (the
     * sheet says so). The facts block gets the device check's last pass: no '/' and no control characters.
     */
    fun body(prompt: String, f: ReportFacts): String {
        fun total(t: Total?) =
            t?.let { DeviceReport.count(io.github.tomerar.keepclear.devcheck.Count(it.files.toLong(), it.bytes)) }
                ?: "not scanned yet"
        val facts = buildString {
            append(
                "KeepClear ",
            ).append(ReportText.clean(f.versionName)).append(" (").append(f.versionCode).append("), ")
                .append(ReportText.token(f.flavor)).append(' ').append(ReportText.token(f.buildType)).append('\n')
            append("Device: ").append(ReportText.clean(f.manufacturer)).append(' ').append(ReportText.clean(f.model))
                .append(", Android ").append(ReportText.clean(f.android)).append(" (SDK ").append(f.sdk).append(")\n")
            append("Language: ").append(ReportText.token(f.language)).append('\n')
            append("Test mode: ").append(if (f.testMode) "on" else "off").append('\n')
            append("Clean now: ").append(total(f.clean)).append('\n')
            append("Choose what to delete: ").append(total(f.choose)).append('\n')
            append("Back up: ").append(total(f.backUp)).append('\n')
            append("Trash: ").append(total(f.trash)).append('\n')
            append("Backups made: ").append(f.backups).append('\n')
            append(DeviceReport.FOOTER)
        }
        return "$prompt\n\n\n\n---\n" + ReportText.finalPass(facts) + "\n"
    }

    /**
     * The email app with the user's (possibly edited) text. Without an attachment: ACTION_SENDTO `mailto:`, so only
     * email apps answer. With one: ACTION_SEND with the mailto selector (ACTION_SENDTO can't carry a file), the file
     * shared read-only through KeepClear's FileProvider. [address] empty: the user fills in "To" (C166).
     */
    fun intent(address: String, subject: String, body: String, attachment: Uri?): Intent {
        val mailto = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
        val intent = if (attachment == null) {
            mailto
        } else {
            Intent(Intent.ACTION_SEND).setType("text/plain").apply {
                putExtra(Intent.EXTRA_STREAM, attachment)
                clipData = ClipData.newRawUri(DIAGNOSTICS_NAME, attachment)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                selector = mailto
            }
        }
        if (address.isNotBlank()) intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(address.trim()))
        return intent.putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
    }

    const val DIAGNOSTICS_NAME = "keepclear-diagnostics.txt"
    const val REPORTS_DIR = "reports"

    /** The FileProvider's authority for this build (test builds end in `.test`). */
    fun authority(context: Context) = "${context.packageName}.reports"

    /**
     * Writes the device check's report (counts and sizes, no paths: [DeviceReport]) into KeepClear's cache, the only
     * folder its FileProvider shares, and returns its content URI. Read-only on the phone's files; also in release
     * builds (owner brief H3 item 2). Blocking: call off the main thread.
     */
    suspend fun writeDiagnostics(app: KeepClearApplication): Uri {
        val facts = io.github.tomerar.keepclear.devcheck.DeviceCheckCollector(app).collect()
        val dir = java.io.File(app.cacheDir, REPORTS_DIR).apply { mkdirs() }
        val file = java.io.File(dir, DIAGNOSTICS_NAME)
        // Overwritten each time (never deleted: deleting is the choke point's job, CLAUDE.md safety rule 3).
        file.writeText(DeviceReport.render(facts))
        return androidx.core.content.FileProvider.getUriForFile(app, authority(app), file)
    }
}

/** What the report sheet shows and does (wired in MainActivity). */
class ReportUi(
    val body: String,
    val preparing: Boolean = false,
    val noEmailApp: Boolean = false,
    val copied: Boolean = false,
    val onSend: (body: String, attach: Boolean) -> Unit = { _, _ -> },
    val onCopy: (body: String) -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * Owner brief H3 item 2: "Report a problem" as a sheet: the text the email will hold, editable here and again in the
 * email app, the optional diagnostics file, and one button. Nothing is sent by KeepClear itself.
 */
@Composable
fun ReportSheet(ui: ReportUi) {
    var text by rememberSaveable { mutableStateOf(ui.body) }
    var attach by rememberSaveable { mutableStateOf(false) }
    InfoSheet(
        title = stringResource(R.string.report_title),
        dismissText = stringResource(R.string.swipe_close),
        onDismiss = ui.onDismiss,
        modifier = Modifier.testTag("reportSheet"),
        primaryButton = {
            KcButton(
                stringResource(R.string.report_send),
                onClick = { ui.onSend(text, attach) },
                enabled = !ui.preparing,
                modifier = Modifier.fillMaxWidth().testTag("reportSend"),
            )
        },
    ) {
        Text(stringResource(R.string.report_intro), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.report_message_label)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag("reportBody"),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(checked = attach, onCheckedChange = { attach = it }, modifier = Modifier.testTag("reportAttach"))
            Text(stringResource(R.string.report_attach), style = MaterialTheme.typography.bodyMedium)
        }
        if (ui.preparing) {
            Text(
                stringResource(R.string.report_preparing),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (ui.noEmailApp) {
            Text(
                stringResource(R.string.report_no_email),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("reportNoEmail").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (ui.copied) {
            Text(
                stringResource(R.string.report_copied),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        KcButton(
            stringResource(R.string.report_copy),
            onClick = { ui.onCopy(text) },
            style = KcButtonStyle.Text,
            modifier = Modifier.testTag("reportCopy"),
        )
    }
}
