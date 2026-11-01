// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.storage.media.MediaAccess
import io.github.tomerar.keepclear.engine.classify.ClassifiedItem

class MainActivity : ComponentActivity() {
    private val thumbnails by lazy { ThumbnailLoader(contentResolver) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // SEC-7: ignore touches while another app's overlay covers KeepClear.
        window.decorView.filterTouchesWhenObscured = true
        setContent {
            KeepClearTheme {
                val vm: HomeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val mediaLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                        vm.refresh()
                    }
                val app = application as KeepClearApplication
                val testModeOn by app.testMode.on.collectAsStateWithLifecycle()
                val moveVm: MoveViewModel = viewModel()
                val move by moveVm.state.collectAsStateWithLifecycle()
                var notificationsOff by remember { mutableStateOf(!MoveNotifications.allowed(this)) }
                var explainNotifications by remember { mutableStateOf(false) }
                val treeLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        if (uri != null) moveVm.onTreePicked(uri)
                    }
                val notificationLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        // ONB-10: the backup runs either way; without the permission progress shows only in the app.
                        notificationsOff = !granted
                        moveVm.start()
                    }
                val externalCacheLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        vm.refresh()
                    }
                LifecycleResumeEffect(Unit) {
                    vm.refresh()
                    notificationsOff = !MoveNotifications.allowed(this@MainActivity)
                    onPauseOrDispose { }
                }
                if (explainNotifications) {
                    AlertDialog(
                        onDismissRequest = {
                            explainNotifications = false
                            markAskedNotifications()
                            moveVm.start()
                        },
                        title = { Text(stringResource(R.string.move_notif_title)) },
                        text = { Text(stringResource(R.string.move_notif_body)) },
                        confirmButton = {
                            Button(onClick = {
                                explainNotifications = false
                                markAskedNotifications()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    moveVm.start()
                                }
                            }) { Text(stringResource(R.string.move_notif_allow)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                explainNotifications = false
                                markAskedNotifications()
                                moveVm.start()
                            }) { Text(stringResource(R.string.move_notif_skip)) }
                        },
                    )
                }
                CompositionLocalProvider(LocalThumbnailLoader provides thumbnails) {
                    AppScreens(
                        testMode = TestModeUi(
                            on = testModeOn,
                            turnOn = app.testMode::turnOn,
                            turnOff = app.testMode::turnOff,
                        ),
                        state = state,
                        onOpen = vm::open,
                        onBack = vm::back,
                        onRestore = vm::restore,
                        onPurge = vm::purge,
                        move = move,
                        notificationsOff = notificationsOff,
                        moveActions = MoveActions(
                            onPickTarget = { items ->
                                moveVm.pendingItems = items
                                treeLauncher.launch(null)
                            },
                            onStart = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notificationsOff &&
                                    !askedNotifications()
                                ) {
                                    explainNotifications = true
                                } else {
                                    moveVm.start()
                                }
                            },
                            onCancelPick = moveVm::cancelPick,
                            onPause = moveVm::pause,
                            onStop = moveVm::stop,
                            onResume = moveVm::resume,
                            onFree = moveVm::freeOriginals,
                            onCheck = moveVm::checkBackup,
                            onCancelCheck = moveVm::cancelCheck,
                            onShare = { id -> move.jobs.firstOrNull { it.job.id == id }?.let(::shareReceipt) },
                            onOpenGooglePhotos = ::openGooglePhotos,
                        ),
                        safeActions = SafeActions(
                            onClean = vm::clean,
                            onUndo = vm::undo,
                            onTrim = vm::trimCaches,
                            // SAFE-6 (foss with All files access): Android's own one-dialog external cache clear.
                            onClearExternalCaches = {
                                // Test mode (SAFETY-9): Android's cache clear would really delete; don't open it.
                                if (app.testMode.isOn) {
                                    vm.trimCaches()
                                } else {
                                    externalCacheLauncher.launch(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
                                }
                            },
                            onOpenTrash = {
                                vm.loadTrash()
                                vm.open(Screen.Trash)
                            },
                            onRestoreChanged = vm::restoreChanged,
                            onConfirmLastCopy = vm::confirmLastCopies,
                            onDismissLastCopy = vm::dismissLastCopies,
                        ),
                        home = {
                            HomeScreen(
                                state = state,
                                showDebugTools = BuildConfig.DEBUG,
                                actions = HomeActions(
                                    onRequestMedia = { mediaLauncher.launch(MediaAccess.requestPermissions()) },
                                    onRequestAllFiles = {
                                        startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                "package:$packageName".toUri(),
                                            ),
                                        )
                                    },
                                    onScanAgain = vm::scan,
                                    onCancelScan = vm::cancelScan,
                                    onOpenSettings = { vm.open(Screen.Settings) },
                                    onOpenCategory = { vm.open(Screen.CategoryList(it)) },
                                    onOpenTrash = {
                                        vm.loadTrash()
                                        vm.open(Screen.Trash)
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    private val prefs get() = getSharedPreferences("move", MODE_PRIVATE)

    private fun askedNotifications() = prefs.getBoolean("askedNotifications", false)

    private fun markAskedNotifications() = prefs.edit { putBoolean("askedNotifications", true) }

    /** MOVE-16: hand over to Google Photos' own "Free up space"; KeepClear can't see its cloud state. */
    private fun openGooglePhotos() {
        packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")?.let(::startActivity)
    }

    /** MOVE-13: share a receipt as plain text. */
    private fun shareReceipt(view: JobView) {
        val r = view.receipt
        val text = getString(
            R.string.move_receipt_text,
            r.targetLabel,
            r.rootRel,
            r.verified,
            ByteSize.format(r.verifiedBytes, resources.configuration.locales[0]),
            r.total - r.verified,
        )
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                null,
            ),
        )
    }
}
