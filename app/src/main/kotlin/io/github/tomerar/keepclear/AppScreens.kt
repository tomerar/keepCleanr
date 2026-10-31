// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Shows the top of the navigation stack; system back pops it. */
@Composable
fun AppScreens(
    state: HomeUiState,
    onOpen: (Screen) -> Unit,
    onBack: () -> Unit,
    home: @Composable () -> Unit,
    onRestore: (List<io.github.tomerar.keepclear.engine.trash.TrashEntry>) -> Unit = {},
    onPurge: (List<io.github.tomerar.keepclear.engine.trash.TrashEntry>) -> Unit = {},
    safeActions: SafeActions = SafeActions(),
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

        Screen.Trash -> {
            TrashScreen(state.trash, onRestore, onPurge, onBack)
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
)
