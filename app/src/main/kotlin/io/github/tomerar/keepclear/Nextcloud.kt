// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tomerar.keepclear.backup.webdav.NextcloudAccount
import io.github.tomerar.keepclear.backup.webdav.NextcloudException
import io.github.tomerar.keepclear.backup.webdav.NextcloudLoginFlow
import io.github.tomerar.keepclear.backup.webdav.NextcloudProblem
import io.github.tomerar.keepclear.backup.webdav.NextcloudServer
import io.github.tomerar.keepclear.backup.webdav.WebDavLocation
import io.github.tomerar.keepclear.backup.webdav.WebDavTargets
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.i18n.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M17b: the one Nextcloud the user connected (Settings › Advanced). Server, login name and user id are plain
 * preferences; the **app password** is stored only sealed by [SecretBox] and never logged. The account password is
 * never asked for (Login Flow v2) and never stored.
 */
class NextcloudStore(private val prefs: SharedPreferences, private val box: SecretBox) {
    private val _hostState = MutableStateFlow(prefs.getString(HOST, null))

    /** The saved Nextcloud's host, updated on save and forget (Settings shows it). */
    val hostState: StateFlow<String?> = _hostState

    fun save(account: NextcloudAccount) = prefs.edit(commit = true) {
        putString(SERVER, account.server.url)
        putString(HOST, account.server.host)
        putString(LOGIN, account.loginName)
        putString(USER_ID, account.userId)
        putString(
            PASSWORD,
            Base64.encodeToString(box.seal(account.appPassword.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
        )
    }.also { _hostState.value = account.server.host }

    /** The saved account, or null if none (or the app password can't be opened any more: sign in again). */
    fun load(): NextcloudAccount? {
        val server = prefs.getString(SERVER, null) ?: return null
        val sealed = prefs.getString(PASSWORD, null) ?: return null
        val password = runCatching { box.open(Base64.decode(sealed, Base64.NO_WRAP)) }.getOrNull()
            ?.toString(Charsets.UTF_8) ?: return null
        return runCatching {
            NextcloudAccount(
                NextcloudServer.parse(server),
                prefs.getString(LOGIN, null) ?: return null,
                prefs.getString(USER_ID, null) ?: return null,
                password,
            )
        }.getOrNull()
    }

    /** The host shown in Settings ("only to …"), without opening the app password. */
    fun host(): String? = prefs.getString(HOST, null)

    fun clear() = prefs.edit(commit = true) { clear() }.also { _hostState.value = null }

    companion object {
        private const val SERVER = "server"
        private const val HOST = "host"
        private const val LOGIN = "login"
        private const val USER_ID = "userId"
        private const val PASSWORD = "appPasswordSealed"

        /** Backups go to the top of the account's files, like on a drive: `KeepClear/<phone>/…` (C29). */
        fun location(account: NextcloudAccount): WebDavLocation = account.location(folder = "")

        /** The reference a job stores to reopen this Nextcloud (no name or password in it, E-MOVE-2). */
        fun ref(account: NextcloudAccount): String = WebDavTargets.ref(location(account))
    }
}

/** What went wrong while connecting the Nextcloud, in plain words on the screen. */
enum class NextcloudIssue {
    BAD_ADDRESS,
    NEEDS_HTTPS,
    UNREACHABLE,
    CERTIFICATE,
    NOT_NEXTCLOUD,
    OTHER_ADDRESS,
    REFUSED,
    EXPIRED,

    /** The account holds a `.keepclear-target` that isn't KeepClear's (SEC-3); nothing is overwritten. */
    DAMAGED_MARKER,
    ;

    companion object {
        fun of(problem: NextcloudProblem) = when (problem) {
            NextcloudProblem.BAD_ADDRESS -> BAD_ADDRESS
            NextcloudProblem.NEEDS_HTTPS -> NEEDS_HTTPS
            NextcloudProblem.UNREACHABLE -> UNREACHABLE
            NextcloudProblem.CERTIFICATE -> CERTIFICATE
            NextcloudProblem.NOT_NEXTCLOUD -> NOT_NEXTCLOUD
            NextcloudProblem.OTHER_ADDRESS -> OTHER_ADDRESS
            NextcloudProblem.REFUSED -> REFUSED
            NextcloudProblem.EXPIRED -> EXPIRED
        }
    }
}

/** Where the set-up is. */
sealed interface NextcloudStep {
    data object Form : NextcloudStep

    /** Talking to the server (starting the sign-in, checking the app password, opening the backup place). */
    data object Working : NextcloudStep

    /** Waiting for the user to sign in on [loginUrl] in the browser. */
    data class Browser(val loginUrl: String) : NextcloudStep

    data class Done(val host: String) : NextcloudStep
}

data class NextcloudSetupState(val step: NextcloudStep = NextcloudStep.Form, val issue: NextcloudIssue? = null)

/**
 * M17b: Settings › Advanced › "Back up to Nextcloud". Signs in with Login Flow v2 (or a typed app password),
 * checks the account, opens the backup place once (folder + marker), and only then saves it.
 */
class NextcloudViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KeepClearApplication
    private val _state = MutableStateFlow(NextcloudSetupState())
    val state: StateFlow<NextcloudSetupState> = _state
    private var job: Job? = null
    private var flow: NextcloudLoginFlow? = null

    /** A fresh form; any sign-in still waiting is stopped. */
    fun begin() {
        cancel()
        _state.value = NextcloudSetupState()
    }

    /** Stops a sign-in that is waiting or running (leaving the screen, "Cancel"). Nothing is saved. */
    fun cancel() {
        job?.cancel()
        job = null
        flow?.close()
        flow = null
        if (_state.value.step !is NextcloudStep.Done) _state.update { it.copy(step = NextcloudStep.Form) }
    }

    /** "Sign in with the browser": Login Flow v2. */
    fun signIn(address: String) = connectWith(address) { server ->
        val login = NextcloudLoginFlow(server)
        flow = login
        try {
            val started = withContext(Dispatchers.IO) { login.start() }
            _state.update { it.copy(step = NextcloudStep.Browser(started.loginUrl)) }
            val result = login.await(started)
            _state.update { it.copy(step = NextcloudStep.Working) }
            withContext(Dispatchers.IO) { NextcloudAccount.verify(server, result.loginName, result.appPassword) }
        } finally {
            login.close()
            if (flow === login) flow = null
        }
    }

    /** "Use an app password instead": the user made one in their Nextcloud and typed it here. */
    fun manual(address: String, loginName: String, appPassword: String) = connectWith(address) { server ->
        withContext(Dispatchers.IO) { NextcloudAccount.verify(server, loginName.trim(), appPassword.trim()) }
    }

    private fun connectWith(address: String, account: suspend (NextcloudServer) -> NextcloudAccount) {
        cancel()
        _state.value = NextcloudSetupState(NextcloudStep.Working)
        job = viewModelScope.launch {
            val issue = try {
                connect(account(NextcloudServer.parse(address)))
            } catch (e: NextcloudException) {
                NextcloudIssue.of(e.problem)
            } catch (e: IllegalArgumentException) {
                NextcloudIssue.BAD_ADDRESS
            }
            if (issue != null) _state.value = NextcloudSetupState(NextcloudStep.Form, issue)
        }
    }

    /** Opens the backup place once (makes the folder and marker); saves the account only if that worked. */
    private suspend fun connect(account: NextcloudAccount): NextcloudIssue? {
        val host = account.server.host
        val opened = withContext(Dispatchers.IO) { app.openNextcloud(account, host) }
        return when (opened) {
            is OpenedTarget.Ok -> {
                withContext(Dispatchers.IO) {
                    opened.target.useTarget { }
                    app.nextcloud.save(account)
                }
                _state.value = NextcloudSetupState(NextcloudStep.Done(host))
                null
            }

            OpenedTarget.Refused -> {
                NextcloudIssue.REFUSED
            }

            OpenedTarget.CertificateRejected -> {
                NextcloudIssue.CERTIFICATE
            }

            OpenedTarget.DamagedMarker -> {
                NextcloudIssue.DAMAGED_MARKER
            }

            OpenedTarget.OnThisPhone, OpenedTarget.Unavailable -> {
                NextcloudIssue.UNREACHABLE
            }
        }
    }

    override fun onCleared() {
        flow?.close()
    }
}

/** The set-up screen's state and actions (Settings › Advanced). */
class NextcloudUi(
    val state: NextcloudSetupState = NextcloudSetupState(),
    /** The Nextcloud already connected, if any. */
    val connectedHost: String? = null,
    val onSignIn: (String) -> Unit = {},
    val onManual: (String, String, String) -> Unit = { _, _, _ -> },
    /** Opens the sign-in page in the browser (checked to be on the typed server by the engine). */
    val onOpenLogin: (String) -> Unit = {},
    val onCancel: () -> Unit = {},
)

@Composable
fun NextcloudScreen(ui: NextcloudUi, onBack: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var login by rememberSaveable { mutableStateOf("") }
    // The app password is never kept in saved instance state.
    var appPassword by remember { mutableStateOf("") }
    val addressProblem = NextcloudServer.problem(address).takeIf { address.isNotBlank() }
    val addressOk = address.isNotBlank() && addressProblem == null
    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("nextcloud"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackRow(stringResource(R.string.nextcloud_title), onBack)
            Text(stringResource(R.string.nextcloud_intro), style = MaterialTheme.typography.bodyMedium)
            ui.connectedHost?.let { host ->
                Text(
                    stringResource(R.string.nextcloud_connected, Bidi.isolate(host)),
                    modifier = Modifier.testTag("nextcloudConnected"),
                )
            }
            ui.state.issue?.let { issue ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(
                        stringResource(issueText(issue)),
                        modifier = Modifier.padding(12.dp).testTag("nextcloudProblem"),
                    )
                }
            }
            when (val step = ui.state.step) {
                NextcloudStep.Form -> {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(stringResource(R.string.nextcloud_address)) },
                        singleLine = true,
                        isError = addressProblem != null,
                        supportingText = addressProblem?.let { p -> { Text(stringResource(addressText(p))) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().testTag("nextcloudAddress"),
                    )
                    Button(
                        onClick = { ui.onSignIn(address) },
                        enabled = addressOk,
                        modifier = Modifier.fillMaxWidth().testTag("nextcloudSignIn"),
                    ) { Text(stringResource(R.string.nextcloud_sign_in)) }
                    TextButton(onClick = { manual = !manual }, modifier = Modifier.testTag("nextcloudManualToggle")) {
                        Text(stringResource(R.string.nextcloud_manual))
                    }
                    if (manual) {
                        Text(stringResource(R.string.nextcloud_manual_help), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = login,
                            onValueChange = { login = it },
                            label = { Text(stringResource(R.string.nextcloud_user)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("nextcloudUser"),
                        )
                        OutlinedTextField(
                            value = appPassword,
                            onValueChange = { appPassword = it },
                            label = { Text(stringResource(R.string.nextcloud_app_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().testTag("nextcloudAppPassword"),
                        )
                        val manualOk = addressOk && NextcloudAccount.isLoginName(login.trim()) &&
                            NextcloudAccount.isAppPassword(appPassword.trim())
                        Button(
                            onClick = { ui.onManual(address, login, appPassword) },
                            enabled = manualOk,
                            modifier = Modifier.fillMaxWidth().testTag("nextcloudManualConnect"),
                        ) { Text(stringResource(R.string.nextcloud_connect)) }
                    }
                }

                NextcloudStep.Working -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.nextcloud_working),
                            modifier = Modifier.testTag("nextcloudWorking"),
                        )
                    }
                    TextButton(onClick = ui.onCancel, modifier = Modifier.testTag("nextcloudCancel")) {
                        Text(stringResource(R.string.move_cancel))
                    }
                }

                is NextcloudStep.Browser -> {
                    // The page opens once by itself; the button opens it again (e.g. after closing the browser).
                    LaunchedEffect(step.loginUrl) { ui.onOpenLogin(step.loginUrl) }
                    Text(stringResource(R.string.nextcloud_browser), modifier = Modifier.testTag("nextcloudWaiting"))
                    OutlinedButton(
                        onClick = { ui.onOpenLogin(step.loginUrl) },
                        modifier = Modifier.fillMaxWidth().testTag("nextcloudOpenAgain"),
                    ) { Text(stringResource(R.string.nextcloud_open_again)) }
                    TextButton(onClick = ui.onCancel, modifier = Modifier.testTag("nextcloudCancel")) {
                        Text(stringResource(R.string.move_cancel))
                    }
                }

                is NextcloudStep.Done -> {
                    Text(
                        stringResource(R.string.nextcloud_done, Bidi.isolate(step.host)),
                        modifier = Modifier.testTag("nextcloudDone"),
                    )
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("nextcloudFinish")) {
                        Text(stringResource(R.string.restore_ok))
                    }
                }
            }
        }
    }
}

private fun addressText(problem: NextcloudProblem) = when (problem) {
    NextcloudProblem.NEEDS_HTTPS -> R.string.nextcloud_needs_https
    else -> R.string.nextcloud_bad_address
}

private fun issueText(issue: NextcloudIssue) = when (issue) {
    NextcloudIssue.BAD_ADDRESS -> R.string.nextcloud_bad_address
    NextcloudIssue.NEEDS_HTTPS -> R.string.nextcloud_needs_https
    NextcloudIssue.UNREACHABLE -> R.string.nextcloud_problem_unreachable
    NextcloudIssue.CERTIFICATE -> R.string.nextcloud_problem_certificate
    NextcloudIssue.NOT_NEXTCLOUD -> R.string.nextcloud_problem_not_nextcloud
    NextcloudIssue.OTHER_ADDRESS -> R.string.nextcloud_problem_other_address
    NextcloudIssue.REFUSED -> R.string.nextcloud_problem_refused
    NextcloudIssue.EXPIRED -> R.string.nextcloud_problem_expired
    NextcloudIssue.DAMAGED_MARKER -> R.string.move_problem_marker
}
