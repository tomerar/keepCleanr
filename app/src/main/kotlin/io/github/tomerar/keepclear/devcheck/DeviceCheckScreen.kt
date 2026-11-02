// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.devcheck

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tomerar.keepclear.BackRow
import io.github.tomerar.keepclear.BuildConfig
import io.github.tomerar.keepclear.KeepClearApplication
import io.github.tomerar.keepclear.core.i18n.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The device check exists only in test builds; release builds never show or open it. */
object DeviceCheckGate {
    fun visible(debug: Boolean = BuildConfig.DEBUG): Boolean = debug
}

data class DeviceCheckState(val running: Boolean = false, val report: String? = null)

/** What the device-check screen shows and can do (wired in MainActivity). */
class DeviceCheckUi(
    val enabled: Boolean = false,
    val state: DeviceCheckState = DeviceCheckState(),
    val onRun: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onShare: (String) -> Unit = {},
)

/** Runs the read-only checks off the main thread and keeps the last report. */
class DeviceCheckViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(DeviceCheckState())
    val state: StateFlow<DeviceCheckState> = _state
    private var job: Job? = null

    fun run() {
        if (_state.value.running || !DeviceCheckGate.visible()) return
        val app = getApplication<KeepClearApplication>()
        _state.update { it.copy(running = true) }
        job = viewModelScope.launch {
            val text = runCatching {
                val facts = withContext(Dispatchers.IO) { DeviceCheckCollector(app).collect() }
                DeviceReport.render(facts)
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                val kind = ReportText.token(e.javaClass.simpleName)
                ReportText.finalPass("KeepClear device check\nThe check stopped: $kind\n\n${DeviceReport.FOOTER}\n")
            }
            _state.update { DeviceCheckState(running = false, report = text) }
        }
    }

    /** First opening: run once; later openings show the last report until "Run again". */
    fun runIfEmpty() {
        if (_state.value.report == null) run()
    }
}

/** Copy and Share for the report (plain text; no file is written). */
object DeviceCheckShare {
    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("KeepClear device check", text))
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, "KeepClear device check")
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }
}

/**
 * Settings › Advanced › Device check (test builds): runs every read-only device check it can and shows a report
 * with counts and sizes only, to copy or share. The report is in English; the screen says so.
 */
@Composable
fun DeviceCheckScreen(ui: DeviceCheckUi, onBack: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("deviceCheck"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackRow(stringResource(R.string.debug_device_check_title), onBack)
            Text(stringResource(R.string.debug_device_check_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.debug_device_check_english), style = MaterialTheme.typography.bodySmall)
            val report = ui.state.report
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = ui.onRun,
                    enabled = !ui.state.running,
                    modifier = Modifier.testTag("deviceCheckRun"),
                ) {
                    Text(stringResource(R.string.debug_device_check_run))
                }
                OutlinedButton(
                    onClick = { report?.let(ui.onCopy) },
                    enabled = report != null && !ui.state.running,
                    modifier = Modifier.testTag("deviceCheckCopy"),
                ) {
                    Text(stringResource(R.string.debug_device_check_copy))
                }
                OutlinedButton(
                    onClick = { report?.let(ui.onShare) },
                    enabled = report != null && !ui.state.running,
                    modifier = Modifier.testTag("deviceCheckShare"),
                ) {
                    Text(stringResource(R.string.debug_device_check_share))
                }
            }
            if (ui.state.running) {
                CircularProgressIndicator(Modifier.testTag("deviceCheckProgress"))
                Text(
                    stringResource(R.string.debug_device_check_running),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (report != null) {
                // The report is English: left to right even when the app is in Hebrew.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Card(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(
                                report,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(12.dp).testTag("deviceCheckReport"),
                            )
                        }
                    }
                }
            }
        }
    }
}
