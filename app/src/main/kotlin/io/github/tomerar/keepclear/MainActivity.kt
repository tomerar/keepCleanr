// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Intent
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.storage.media.MediaAccess

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
                val externalCacheLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        vm.refresh()
                    }
                LifecycleResumeEffect(Unit) {
                    vm.refresh()
                    onPauseOrDispose { }
                }
                CompositionLocalProvider(LocalThumbnailLoader provides thumbnails) {
                    AppScreens(
                        state = state,
                        onOpen = vm::open,
                        onBack = vm::back,
                        onRestore = vm::restore,
                        onPurge = vm::purge,
                        safeActions = SafeActions(
                            onClean = vm::clean,
                            onUndo = vm::undo,
                            onTrim = vm::trimCaches,
                            // SAFE-6 (foss with All files access): Android's own one-dialog external cache clear.
                            onClearExternalCaches = {
                                externalCacheLauncher.launch(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
                            },
                            onOpenTrash = {
                                vm.loadTrash()
                                vm.open(Screen.Trash)
                            },
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
}
