// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.engine.classify.Total
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the "Manage space" screen shows: the trash's size, or null while it is still being read. */
data class ManageSpaceUi(val trash: Total?, val onOpenTrash: () -> Unit = {}, val onClose: () -> Unit = {})

/**
 * Owner brief H4 item 1: Android's Settings › Apps › KeepClear › Storage shows "Manage space" (this screen) instead
 * of "Clear storage". "Clear storage" would wipe KeepClear's data, and with it the list of what is in the trash: the
 * files would stay in `.KeepClearTrash` with no way to restore them. Here nothing is wiped: the screen says what the
 * trash holds and opens it, where "Empty now" asks its usual second confirmation (TRASH-4). Not exported: only
 * Android's Settings (a system app) opens it (SEC-5).
 */
class ManageSpaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // SEC-7: ignore touches while another app's overlay covers KeepClear.
        window.decorView.filterTouchesWhenObscured = true
        val app = application as KeepClearApplication
        var trash by mutableStateOf<Total?>(null)
        lifecycleScope.launch {
            trash = runCatching {
                withContext(Dispatchers.IO) {
                    val entries = app.trashManager.entries()
                    Total(entries.size, entries.sumOf { it.size })
                }
            }.getOrElse { Total(0, 0) }
        }
        setContent {
            KeepClearTheme {
                ManageSpaceScreen(
                    ManageSpaceUi(
                        trash = trash,
                        onOpenTrash = {
                            startActivity(ManageSpace.openTrashIntent(this))
                            finish()
                        },
                        onClose = ::finish,
                    ),
                )
            }
        }
    }
}

object ManageSpace {
    /** KeepClear's Trash, through the launcher shortcut's route: navigation only, it never empties anything. */
    fun openTrashIntent(context: android.content.Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ShortcutRoute.ACTION)
        .putExtra(ShortcutRoute.EXTRA, ShortcutRoute.TRASH.extra)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

@Composable
fun ManageSpaceScreen(ui: ManageSpaceUi) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal),
                )
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("manageSpace"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.manage_space_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semanticsHeading().testTag("screenTitle"),
            )
            KcCard {
                Text(
                    when (val t = ui.trash) {
                        null -> stringResource(R.string.manage_space_reading)

                        else -> if (t.files == 0) {
                            stringResource(R.string.manage_space_trash_empty)
                        } else {
                            stringResource(R.string.manage_space_trash, totalText(t))
                        }
                    },
                    style = KeepClearTheme.type.number,
                    modifier = Modifier.testTag("manageSpaceTrash"),
                )
                Text(stringResource(R.string.manage_space_trash_note), style = MaterialTheme.typography.bodyMedium)
            }
            helpParagraphs(stringResource(R.string.manage_space_body)).forEach { paragraph ->
                Text(paragraph, style = MaterialTheme.typography.bodyLarge)
            }
            KcButton(
                stringResource(R.string.manage_space_open_trash),
                onClick = ui.onOpenTrash,
                modifier = Modifier.fillMaxWidth().testTag("manageSpaceOpenTrash"),
            )
            KcButton(
                stringResource(R.string.manage_space_close),
                onClick = ui.onClose,
                modifier = Modifier.fillMaxWidth().testTag("manageSpaceClose"),
                style = KcButtonStyle.Text,
            )
        }
    }
}

/** A section or screen title for TalkBack's heading navigation. */
internal fun Modifier.semanticsHeading(): Modifier =
    this.then(Modifier.semantics { heading() })
