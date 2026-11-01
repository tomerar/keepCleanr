// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Test mode (SAFETY-9) for [AppScreens]: the banner on every screen and the Settings switch. */
class TestModeUi(val on: Boolean = false, val turnOn: () -> Unit = {}, val turnOff: () -> Unit = {})

/** Every screen, with the always-visible test-mode banner on top while test mode is on. */
@Composable
fun AppScreens(
    state: HomeUiState,
    onOpen: (Screen) -> Unit,
    onBack: () -> Unit,
    home: @Composable () -> Unit,
    onRestore: (
        List<io.github.tomerar.keepclear.engine.trash.TrashEntry>,
        io.github.tomerar.keepclear.core.safety.Confirmation,
    ) -> Unit = { _, _ -> },
    onPurge: (
        List<io.github.tomerar.keepclear.engine.trash.TrashEntry>,
        io.github.tomerar.keepclear.core.safety.Confirmation,
    ) -> Unit = { _, _ -> },
    safeActions: SafeActions = SafeActions(),
    move: MoveUiState = MoveUiState(),
    moveActions: MoveActions = MoveActions(),
    notificationsOff: Boolean = false,
    testMode: TestModeUi = TestModeUi(),
    settingsActions: SettingsActions = SettingsActions(),
    appActions: AppActions = AppActions(),
    appsActions: AppsActions = AppsActions(),
    previewActions: PreviewActions = PreviewActions(),
) {
    Column {
        if (testMode.on) TestModeBanner()
        // The banner already pads for the status bar; the screens below must not pad for it again.
        Box(if (testMode.on) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier) {
            ReadableWidth {
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
                    settingsActions,
                    appActions,
                    appsActions,
                    previewActions,
                )
            }
        }
    }
    state.lastCopy?.let { ops ->
        LastCopyDialog(ops, onConfirm = safeActions.onConfirmLastCopy, onDismiss = safeActions.onDismissLastCopy)
    }
}

/**
 * UI-2: on a tablet, an unfolded foldable or in landscape, the screens keep a readable width in the middle instead
 * of stretching edge to edge. Phones are unchanged.
 */
@Composable
fun ReadableWidth(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth()) { content() }
    }
}

/** UI-2: wider than this, content is centered (Material's "medium" width class ends at 840dp). */
val MAX_CONTENT_WIDTH = 840.dp

/** Shows the top of the navigation stack; system back pops it. */
@Composable
private fun ScreenContent(
    state: HomeUiState,
    onOpen: (Screen) -> Unit,
    onBack: () -> Unit,
    home: @Composable () -> Unit,
    onRestore: (
        List<io.github.tomerar.keepclear.engine.trash.TrashEntry>,
        io.github.tomerar.keepclear.core.safety.Confirmation,
    ) -> Unit,
    onPurge: (
        List<io.github.tomerar.keepclear.engine.trash.TrashEntry>,
        io.github.tomerar.keepclear.core.safety.Confirmation,
    ) -> Unit,
    safeActions: SafeActions,
    move: MoveUiState,
    moveActions: MoveActions,
    notificationsOff: Boolean,
    testMode: TestModeUi,
    settingsActions: SettingsActions,
    appActions: AppActions,
    appsActions: AppsActions,
    previewActions: PreviewActions,
) {
    BackHandler(enabled = state.screens.size > 1, onBack = onBack)
    val summary = state.summary
    val current = state.screens.last()
    // Review PR C: a result belongs to the screen it came from (no stale Undo elsewhere).
    val result = state.cleanResult?.takeIf { it.origin == null || it.origin == current }
    when (val screen = current) {
        Screen.Home -> {
            home()
        }

        is Screen.CategoryList -> {
            when {
                summary == null -> home()

                screen.category == io.github.tomerar.keepclear.core.model.Category.SAFE -> SafeScreen(
                    summary = summary,
                    result = result,
                    trimResult = state.trimResult,
                    showExternalCacheClear = state.allFiles == true,
                    onClean = safeActions.onClean,
                    onUndo = safeActions.onUndo,
                    onTrim = safeActions.onTrim,
                    onClearExternalCaches = safeActions.onClearExternalCaches,
                    onOpenGroup = { onOpen(Screen.Group(it, screen.category)) },
                    onOpenTrash = safeActions.onOpenTrash,
                    onBack = onBack,
                    busy = state.busy,
                    onOneByOne = safeActions.onOneByOne,
                )

                screen.category == io.github.tomerar.keepclear.core.model.Category.MOVE -> MoveScreen(
                    summary = summary,
                    state = move,
                    now = System.currentTimeMillis(),
                    notificationsOff = notificationsOff,
                    // Every action is forwarded (a field-by-field copy once dropped "Check backup").
                    actions = moveActions.withOpenGroup { onOpen(Screen.Group(it, screen.category)) },
                    onBack = onBack,
                )

                else -> CategoryListScreen(
                    summary,
                    screen.category,
                    onOpenGroup = { ruleId ->
                        // SAFE-9: other apps' folders open the per-app screen, never a deletable grid.
                        onOpen(
                            if (ruleId == io.github.tomerar.keepclear.engine.classify.AppContent.RULE_ID) {
                                Screen.AppContent
                            } else {
                                Screen.Group(ruleId, screen.category)
                            },
                        )
                    },
                    onBack = onBack,
                )
            }
        }

        is Screen.Group -> {
            // After deleting a whole group the rescan drops it; keep showing it (empty) while the result and its
            // Undo are there.
            val group = summary?.group(screen.ruleId, screen.category)
                ?: result?.let {
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
                    result = result,
                    onUndo = safeActions.onUndo,
                )
            }
        }

        Screen.AppContent -> {
            val apps = summary?.groups(io.github.tomerar.keepclear.core.model.Category.REVIEW)
                ?.firstOrNull { it.ruleId == io.github.tomerar.keepclear.engine.classify.AppContent.RULE_ID }
                ?.items.orEmpty()
            AppContentScreen(io.github.tomerar.keepclear.engine.classify.AppContent.byApp(apps), appActions, onBack)
        }

        Screen.OneAppAtATime -> {
            OneAppAtATimeScreen(state.apps, state.perAppFreed, appActions, onBack)
        }

        Screen.Apps -> {
            AppsScreen(state.appsInfo, System.currentTimeMillis(), appsActions, onBack)
        }

        Screen.Settings -> {
            SettingsScreen(testMode.on, testMode.turnOn, testMode.turnOff, onBack, settingsActions)
        }

        Screen.Trash -> {
            TrashScreen(
                state.trash,
                onRestore,
                onPurge,
                onBack,
                onRestoreChanged = safeActions.onRestoreChanged,
                measured = state.measured?.takeIf { it.origin == Screen.Trash },
                busy = state.busy,
                onAndroidTrash = safeActions.onAndroidTrash,
            )
        }

        is Screen.Preview -> {
            val item = summary?.group(screen.ruleId, screen.category)?.items?.firstOrNull {
                it.item.path == screen.path
            }
            if (item == null) home() else PreviewScreen(item, onBack, previewActions)
        }
    }
}

class SafeActions(
    val onClean: (
        List<io.github.tomerar.keepclear.engine.classify.ClassifiedItem>,
        io.github.tomerar.keepclear.core.safety.Confirmation,
    ) -> Unit = { _, _ -> },
    val onUndo: (CleanResult, io.github.tomerar.keepclear.core.safety.Confirmation) -> Unit = { _, _ -> },
    val onTrim: () -> Unit = {},
    val onClearExternalCaches: () -> Unit = {},
    val onOpenTrash: () -> Unit = {},
    /** Owner decision 1.2: put a changed trashed item into a separate folder, after a confirm. */
    val onRestoreChanged: (
        io.github.tomerar.keepclear.engine.trash.TrashEntry,
        io.github.tomerar.keepclear.core.safety.Confirmation,
    ) -> Unit = { _, _ -> },
    /** Owner decision 1.3: the user confirmed "this is the last copy" for the held-back items. */
    val onConfirmLastCopy: (io.github.tomerar.keepclear.core.safety.Confirmation) -> Unit = {},
    val onOneByOne: () -> Unit = {},
    /** M12: restore (true) or delete now (false) items in Android's trash. */
    val onAndroidTrash: (
        List<io.github.tomerar.keepclear.engine.scan.ScanItem>,
        Boolean,
        io.github.tomerar.keepclear.core.safety.Confirmation,
    ) -> Unit = { _, _, _ -> },
    val onDismissLastCopy: () -> Unit = {},
)
