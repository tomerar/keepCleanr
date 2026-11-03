// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Intent

/**
 * Owner brief H3 item 7: the launcher's static shortcuts (`res/xml/shortcuts.xml`): "Free up space", "Trash", "Back
 * up". They only open a screen. MainActivity is exported (it is the launcher's), so any app can send these intents:
 * [ShortcutNavigator] has nothing but navigation, so a shortcut can't start, confirm or delete anything.
 */
enum class ShortcutRoute(val extra: String) {
    FREE("free"),
    TRASH("trash"),
    BACKUP("backup"),
    ;

    companion object {
        const val EXTRA = "io.github.tomerar.keepclear.shortcut"
        const val ACTION = "io.github.tomerar.keepclear.action.SHORTCUT"

        /** The route an intent asks for, or null (anything else is ignored). */
        fun of(intent: Intent?): ShortcutRoute? {
            if (intent?.action != ACTION) return null
            val name = intent.getStringExtra(EXTRA) ?: return null
            return entries.firstOrNull { it.extra == name }
        }
    }
}

/** The only things a shortcut may do: show a tab or the Trash. */
interface ShortcutNavigator {
    fun selectTab(tab: Tab)

    fun openTrash()
}

object Shortcuts {
    fun handle(route: ShortcutRoute, nav: ShortcutNavigator) {
        when (route) {
            ShortcutRoute.FREE -> nav.selectTab(Tab.FREE)
            ShortcutRoute.TRASH -> nav.openTrash()
            ShortcutRoute.BACKUP -> nav.selectTab(Tab.BACKUP)
        }
    }

    /** HomeViewModel's navigation, nothing else of it. */
    fun navigator(vm: HomeViewModel) = object : ShortcutNavigator {
        override fun selectTab(tab: Tab) = vm.selectTab(tab)

        override fun openTrash() {
            vm.loadTrash()
            vm.open(Screen.Trash)
        }
    }
}
