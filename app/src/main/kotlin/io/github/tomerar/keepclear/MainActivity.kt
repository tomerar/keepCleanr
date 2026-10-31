// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.storage.media.MediaAccess

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeepClearTheme {
                val vm: HomeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    vm.refresh()
                }
                LifecycleResumeEffect(Unit) {
                    vm.refresh()
                    onPauseOrDispose { }
                }
                HomeScreen(
                    state = state,
                    showDebugTools = BuildConfig.DEBUG,
                    actions = HomeActions(
                        onRequestMedia = { mediaLauncher.launch(MediaAccess.requestPermissions()) },
                        onRequestAllFiles = {
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                            )
                        },
                        onScanAgain = vm::scan,
                        onCancelScan = vm::cancelScan,
                    ),
                )
            }
        }
    }
}
