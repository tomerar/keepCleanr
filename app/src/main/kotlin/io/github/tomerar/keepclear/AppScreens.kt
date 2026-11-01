// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Test mode (SAFETY-9) for [AppScreens]: the banner on every screen and the Settings switch. */
class TestModeUi(val on: Boolean = false, val turnOn: () -> Unit = {}, val turnOff: () -> Unit = {})

/** Every screen, with the always-visible test-mode banner on top while test mode is on. */
@Composable
fun AppScreens(
    state: HomeUiState,
    onOpen: (Screen) -> Unit,
    onBack: () -> Unit,
    home: @Composable () -> Unit,
    onRestore: (List<io.github.tomerar.keepclear.engine.trash.TrashEntry>) -> Unit = {},
    onPurge: (List<io.github.tomerar.keepclear.engine.trash.TrashEntry>) -> Unit = {},
    safeActions: SafeActions = SafeActions(),
    move: MoveUiState = MoveUiState(),
    moveActions: MoveActions = MoveActions(),
    notificationsOff: Boolean = false,
    testMode: TestModeUi = TestModeUi(),
) {
    Column {
        if (testMode.on) TestModeBanner()
        // The banner already pads for the status bar; the screens below must not pad for it again.
        Box(if (testMode.on) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier) {
            ScreenContent(
                state,
                onOpen,
                onBack,
                home,
                onRestore,
                onPurge,
                safeActions,
                move,
                moveActions,
                notificationsOff,
                testMode,
            )
        }
    }
    state.lastCopy?.let { ops ->
        LastCopyDialog(ops, onConfirm = safeActions.onConfirmLastCopy, onDismiss = safeActions.onDismissLastCopy)
    }
}

/** Shows the top of the navigation stack; system back pops it. */
@Composable
private fun ScreenContent(
    state: HomeUiState,
    onOpen: (Screen) -> Unit,
    onBack: () -> Unit,
    home: @Composable () -> Unit,
    onRestore: (List<io.github.tomerar.keepclear.engine.trash.TrashEntry>) -> Unit,
    onPurge: (List<io.github.tomerar.keepclear.engine.trash.TrashEntry>) -> Unit,
    safeActions: SafeActions,
    move: MoveUiState,
    moveActions: MoveActions,
    notificationsOff: Boolean,
    testMode: TestModeUi,
) {
    BackHandler(enabled = state.screens.size > 1, onBack = onBack)
    val summary = state.summary
    when (val screen = state.screens.last()) {
        Screen.Home -> {
            home()
        }

        is Screen.CategoryList -> {
            when {
                summary == null -> home()

                screen.category == io.github.tomerar.keepclear.core.model.Category.SAFE -> SafeScreen(
                    summary = summary,
                    result = state.cleanResult,
                    trimResult = state.trimResult,
                    showExternalCacheClear = state.allFiles == true,
                    onClean = safeActions.onClean,
                    onUndo = safeActions.onUndo,
                    onTrim = safeActions.onTrim,
                    onClearExternalCaches = safeActions.onClearExternalCaches,
                    onOpenGroup = { onOpen(Screen.Group(it, screen.category)) },
                    onOpenTrash = safeActions.onOpenTrash,
                    onBack = onBack,
                )

                screen.category == io.github.tomerar.keepclear.core.model.Category.MOVE -> MoveScreen(
                    summary = summary,
                    state = move,
                    now = System.currentTimeMillis(),
                    notificationsOff = notificationsOff,
                    actions = MoveActions(
                        onPickTarget = moveActions.onPickTarget,
                        onStart = moveActions.onStart,
                        onCancelPick = moveActions.onCancelPick,
                        onPause = moveActions.onPause,
                        onStop = moveActions.onStop,
                        onResume = moveActions.onResume,
                        onFree = moveActions.onFree,
                        onShare = moveActions.onShare,
                        onOpenGooglePhotos = moveActions.onOpenGooglePhotos,
                        onOpenGroup = { onOpen(Screen.Group(it, screen.category)) },
                    ),
                    onBack = onBack,
                )

                else -> CategoryListScreen(
                    summary,
                    screen.category,
                    onOpenGroup = { onOpen(Screen.Group(it, screen.category)) },
                    onBack = onBack,
                )
            }
        }

        is Screen.Group -> {
            // After deleting a whole group the rescan drops it; keep showing it (empty) while the result and its
            // Undo are there.
            val group = summary?.group(screen.ruleId, screen.category)
                ?: state.cleanResult?.let {
                    io.github.tomerar.keepclear.engine.classify.GroupSummary(
                        screen.ruleId,
                        screen.category,
                        io.github.tomerar.keepclear.engine.classify.Total(),
                        emptyList(),
                    )
                }
            if (group == null) {
                home()
            } else {
                GroupScreen(
                    group,
                    onOpenItem = { onOpen(Screen.Preview(it.item.path, screen.ruleId, screen.category)) },
                    onBack = onBack,
                    onDelete = safeActions.onClean,
                    result = state.cleanResult,
                    onUndo = safeActions.onUndo,
                )
            }
        }

        Screen.Settings -> {
            SettingsScreen(testMode.on, testMode.turnOn, testMode.turnOff, onBack)
        }

        Screen.Trash -> {
            TrashScreen(state.trash, onRestore, onPurge, onBack, onRestoreChanged = safeActions.onRestoreChanged)
        }

        is Screen.Preview -> {
            val item = summary?.group(screen.ruleId, screen.category)?.items?.firstOrNull {
                it.item.path == screen.path
            }
            if (item == null) home() else PreviewScreen(item, onBack)
        }
    }
}

class SafeActions(
    val onClean: (List<io.github.tomerar.keepclear.engine.classify.ClassifiedItem>) -> Unit = {},
    val onUndo: (CleanResult) -> Unit = {},
    val onTrim: () -> Unit = {},
    val onClearExternalCaches: () -> Unit = {},
    val onOpenTrash: () -> Unit = {},
    /** Owner decision 1.2: put a changed trashed item into a separate folder, after a confirm. */
    val onRestoreChanged: (io.github.tomerar.keepclear.engine.trash.TrashEntry) -> Unit = {},
    /** Owner decision 1.3: the user confirmed "this is the last copy" for the held-back items. */
    val onConfirmLastCopy: () -> Unit = {},
    val onDismissLastCopy: () -> Unit = {},
)
