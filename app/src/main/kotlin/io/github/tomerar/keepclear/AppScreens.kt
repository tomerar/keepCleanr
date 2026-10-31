// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Shows the top of the navigation stack; system back pops it. */
@Composable
fun AppScreens(state: HomeUiState, onOpen: (Screen) -> Unit, onBack: () -> Unit, home: @Composable () -> Unit) {
    BackHandler(enabled = state.screens.size > 1, onBack = onBack)
    val summary = state.summary
    when (val screen = state.screens.last()) {
        Screen.Home -> {
            home()
        }

        is Screen.CategoryList -> {
            if (summary == null) {
                home()
            } else {
                CategoryListScreen(summary, screen.category, onOpenGroup = {
                    onOpen(Screen.Group(it, screen.category))
                }, onBack = onBack)
            }
        }

        is Screen.Group -> {
            val group = summary?.group(screen.ruleId, screen.category)
            if (group == null) {
                home()
            } else {
                GroupScreen(group, onOpenItem = {
                    onOpen(Screen.Preview(it.item.path, screen.ruleId, screen.category))
                }, onBack = onBack)
            }
        }

        is Screen.Preview -> {
            val item = summary?.group(screen.ruleId, screen.category)?.items?.firstOrNull {
                it.item.path == screen.path
            }
            if (item == null) home() else PreviewScreen(item, onBack)
        }
    }
}
