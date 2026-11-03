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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tomerar.keepclear.devcheck.DeviceCheckScreen
import io.github.tomerar.keepclear.devcheck.DeviceCheckUi

/** Test mode (SAFETY-9) for [AppScreens]: the banner on every screen and the Settings switch. */
class TestModeUi(val on: Boolean = false, val turnOn: () -> Unit = {}, val turnOff: () -> Unit = {})

/**
 * Every screen, with the always-visible test-mode banner on top while test mode is on. F1b: a tab's root shows in the
 * main frame (top app bar with the Trash chip and Settings, and the tabs, [MainTabs]); a sub-screen has its own top
 * app bar with a back arrow.
 */
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
    deviceCheck: DeviceCheckUi = DeviceCheckUi(),
    nextcloud: NextcloudUi = NextcloudUi(),
    /** E4: group selections kept by the ViewModel; null keeps them in each group screen (tests). */
    selectionActions: SelectionActions? = null,
    /** E7: the MOVE state as a flow, read only by the MOVE screen (not by every screen on each copied file). */
    moveFlow: kotlinx.coroutines.flow.StateFlow<MoveUiState>? = null,
    /** F1b: the tabs, the Trash chip and the Settings gear. */
    tabs: TabsUi = TabsUi(),
) {
    // F1b: system Back pops the shown tab's stack, then goes to the first tab; there it closes the app.
    BackHandler(enabled = Nav.handlesBack(state), onBack = onBack)
    // E4: each screen in every tab's stack keeps its own saved UI state (scroll position, open sections, choices)
    // while another screen or tab is shown, and loses it when it is popped. The holder sits above the frame, so a
    // screen keeps its state whether it shows inside the tabs' frame or not.
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val keys = state.allScreenKeys().toSet()
    val seen = androidx.compose.runtime.remember { mutableSetOf<String>() }
    androidx.compose.runtime.SideEffect {
        (seen - keys).forEach { holder.removeState(it) }
        seen.retainAll(keys)
        seen += keys
    }
    val current = state.screens.last()
    val content: @Composable () -> Unit = {
        ReadableWidth {
            holder.SaveableStateProvider(screenKey(state.tab, state.screens.lastIndex, current)) {
                CurrentScreen(
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
                    deviceCheck,
                    nextcloud,
                    selectionActions,
                    moveFlow,
                    tabs,
                )
            }
        }
    }
    Column {
        if (testMode.on) TestModeBanner()
        // The banner already pads for the status bar; the screens below must not pad for it again.
        Box(if (testMode.on) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier) {
            if (state.screens.size == 1 && Tab.rootOf(current) != null) {
                MainTabs(state.tab, state.trash, tabs, content)
            } else {
                content()
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

/** Owner brief H2 item 12: the main top bar at the same readable width as the content below it, centred. */
@Composable
fun ReadableWidthBar(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth()) { content() }
    }
}

/** UI-2: wider than this, content is centered (Material's "medium" width class ends at 840dp). */
val MAX_CONTENT_WIDTH = 840.dp

/**
 * E4: the saved-state key of the screen at [index] in [tab]'s stack (the same screen twice gets two keys; the same
 * screen in two tabs too).
 */
internal fun screenKey(tab: Tab, index: Int, screen: Screen): String = "${tab.name}:$index:$screen"

/** F1b: the Backup tab (MOVE-1…18): the MOVE screen, also without a scan, with the backup places. */
@Composable
private fun BackupTab(
    state: HomeUiState,
    moveFlow: kotlinx.coroutines.flow.StateFlow<MoveUiState>?,
    moveValue: MoveUiState,
    notificationsOff: Boolean,
    moveActions: MoveActions,
    settingsActions: SettingsActions,
    onOpen: (Screen) -> Unit,
    onBack: (() -> Unit)?,
) {
    val pending = when {
        state.summary != null -> {
            null
        }

        state.mediaAccess == io.github.tomerar.keepclear.core.storage.media.MediaAccess.NONE -> {
            androidx.compose.ui.res.stringResource(
                io.github.tomerar.keepclear.core.i18n.R.string.home_card_needs_access,
            )
        }

        else -> {
            androidx.compose.ui.res.stringResource(io.github.tomerar.keepclear.core.i18n.R.string.home_card_counting)
        }
    }
    MoveScreen(
        summary = state.summary,
        // E7: only this screen reads the MOVE state, so progress doesn't recompose the others.
        state = moveFlow?.let { flow -> flow.collectAsStateWithLifecycle().value } ?: moveValue,
        now = LocalClock.current(),
        notificationsOff = notificationsOff,
        // Every action is forwarded (a field-by-field copy once dropped "Check backup").
        actions = moveActions.withOpenGroup(
            open = { onOpen(Screen.Group(it, io.github.tomerar.keepclear.core.model.Category.MOVE)) },
            openFreeTab = { onOpen(Screen.Home) },
        ),
        onBack = onBack,
        pending = pending,
        places = BackupPlaces(
            networkHost = settingsActions.networkHost,
            nextcloudHost = settingsActions.nextcloudHost,
            onSetUp = { onOpen(Screen.Settings) },
        ),
    )
}

/** E4: the ViewModel's group selections, for [GroupScreen]. */
class SelectionActions(
    val onSelect: (key: String, paths: Set<String>) -> Unit = { _, _ -> },
    val onSwipe: (key: String, decisions: List<SwipeDecision>?) -> Unit = { _, _ -> },
)

@Composable
private fun CurrentScreen(
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
    moveValue: MoveUiState,
    moveActions: MoveActions,
    notificationsOff: Boolean,
    testMode: TestModeUi,
    settingsActions: SettingsActions,
    appActions: AppActions,
    appsActions: AppsActions,
    previewActions: PreviewActions,
    deviceCheck: DeviceCheckUi,
    nextcloud: NextcloudUi,
    selectionActions: SelectionActions?,
    moveFlow: kotlinx.coroutines.flow.StateFlow<MoveUiState>?,
    tabs: TabsUi,
) {
    val summary = state.summary
    val current = state.screens.last()
    // Review PR C: a result belongs to the screen it came from (no stale Undo elsewhere).
    val result = state.cleanResult?.takeIf { it.origin == null || it.origin == current }
    when (val screen = current) {
        Screen.Home -> {
            home()
        }

        Screen.Backup -> {
            BackupTab(state, moveFlow, moveValue, notificationsOff, moveActions, settingsActions, onOpen, onBack = null)
        }

        is Screen.CategoryList -> {
            when {
                // F1b: the MOVE lane is the Backup tab; [Nav.open] goes there, this only keeps an old stack working.
                screen.category == io.github.tomerar.keepclear.core.model.Category.MOVE -> BackupTab(
                    state,
                    moveFlow,
                    moveValue,
                    notificationsOff,
                    moveActions,
                    settingsActions,
                    onOpen,
                    onBack = onBack,
                )

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
                    onBackUp = { onOpen(Screen.Backup) },
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
                val key = GroupKey.of(screen.ruleId, screen.category)
                GroupScreen(
                    group,
                    onOpenItem = { onOpen(Screen.Preview(it.item.path, screen.ruleId, screen.category)) },
                    onBack = onBack,
                    onDelete = safeActions.onClean,
                    result = result,
                    onUndo = safeActions.onUndo,
                    onOpenTrash = safeActions.onOpenTrash,
                    selection = selectionActions?.let { a ->
                        GroupSelection(
                            selected = state.selections[key].orEmpty(),
                            onSelect = { a.onSelect(key, it) },
                            swipe = state.swipes[key],
                            onSwipe = { a.onSwipe(key, it) },
                        )
                    },
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
            AppsScreen(
                state.appsInfo,
                LocalClock.current(),
                appsActions,
                loading = !state.appsLoaded,
                onBack = onBack.takeIf { state.screens.size > 1 },
                links = AppsTabLinks(
                    onOneByOne = safeActions.onOneByOne,
                    // SAFE-9: other apps' own folders, when the scan found any.
                    onAppContent = summary?.groups(io.github.tomerar.keepclear.core.model.Category.REVIEW)
                        ?.any { it.ruleId == io.github.tomerar.keepclear.engine.classify.AppContent.RULE_ID }
                        ?.takeIf { it }
                        ?.let { { onOpen(Screen.AppContent) } },
                    clonedHidden = state.cloned.hidden,
                    onTrim = safeActions.onTrim,
                    trimResult = state.trimResult,
                    busy = state.busy,
                ),
            )
        }

        Screen.DeviceCheck -> {
            // Test builds only: a release build never shows it, even if asked to open it.
            if (deviceCheck.enabled) {
                DeviceCheckScreen(deviceCheck, onBack)
            } else {
                home()
            }
        }

        Screen.Nextcloud -> {
            NextcloudScreen(nextcloud, onBack)
        }

        Screen.Settings -> {
            SettingsScreen(testMode.on, testMode.turnOn, testMode.turnOff, onBack, settingsActions)
        }

        Screen.Trash -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            // F2: read once per visit; the note stays for this visit and is gone on the next one.
            val noteSeen = androidx.compose.runtime.remember { TrashNotes.uninstallNoteSeen(context) }
            TrashScreen(
                state.trash,
                onRestore,
                onPurge,
                onBack,
                onRestoreChanged = safeActions.onRestoreChanged,
                measured = state.measured?.takeIf { it.origin == Screen.Trash },
                busy = state.busy,
                onAndroidTrash = safeActions.onAndroidTrash,
                onRelease = safeActions.onRelease ?: onPurge,
                showUninstallNote = !noteSeen,
                onUninstallNoteShown = { TrashNotes.setUninstallNoteSeen(context) },
                // H1c: the empty trash's next step: an older photo may be in a backup.
                onRestoreFromBackup = {
                    onBack()
                    tabs.onSelectTab(Tab.BACKUP)
                    moveActions.restore.onStart()
                },
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
    /**
     * D6: "Release now/all": the backup copies are re-checked (size and SHA-256) right before the permanent delete;
     * null = plain purge (tests).
     */
    val onRelease: (
        (
            List<io.github.tomerar.keepclear.engine.trash.TrashEntry>,
            io.github.tomerar.keepclear.core.safety.Confirmation,
        ) -> Unit
    )? = null,
)
