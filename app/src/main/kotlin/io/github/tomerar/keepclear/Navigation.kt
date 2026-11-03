// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.KeepClearTheme
import io.github.tomerar.keepclear.core.i18n.R
import io.github.tomerar.keepclear.core.model.Category

/**
 * The three main tabs (F1b, design-system.md §12), each with its own navigation stack. [root] is the screen the tab
 * starts on.
 */
enum class Tab(val root: Screen) {
    FREE(Screen.Home),
    BACKUP(Screen.Backup),
    APPS(Screen.Apps),
    ;

    companion object {
        /** The tab whose root is [screen], or null for a sub-screen. */
        fun rootOf(screen: Screen): Tab? = entries.firstOrNull { it.root == screen }
    }
}

/**
 * F1b: navigation as pure functions of [HomeUiState] (the ViewModel and the tests use the same rules).
 *
 * - Each tab keeps its own stack; switching tabs keeps the other tabs' stacks (and, through the screens' saved
 *   state, where each was).
 * - Opening a tab's root switches to that tab; the MOVE lane is the Backup tab.
 * - Back pops the current tab's stack; at a tab's root it goes to the first tab ("Free up space"); there it is
 *   Android's (the app closes).
 * - Results belong to the screen they came from: every move clears them (review PR C), and leaving a group with
 *   Back ends its selection (E4, C64).
 */
object Nav {
    fun open(state: HomeUiState, screen: Screen): HomeUiState {
        Tab.rootOf(screen)?.let { return selectTab(state, it) }
        if (screen == Screen.CategoryList(Category.MOVE)) return selectTab(state, Tab.BACKUP)
        // E13: a second tap on the same thing (a double tap) doesn't open it twice.
        if (state.screens.last() == screen) return state
        return state.copy(screens = state.screens + screen).clearedResults()
    }

    /** Back: pops the current stack; at a root other than the first tab's, goes to the first tab. */
    fun back(state: HomeUiState): HomeUiState = when {
        state.screens.size > 1 -> popTo(state, state.screens.size - 1)
        state.tab != Tab.FREE -> selectTab(state, Tab.FREE)
        else -> state
    }

    /** Whether [back] does something (else system Back is Android's: the app closes). */
    fun handlesBack(state: HomeUiState): Boolean = state.screens.size > 1 || state.tab != Tab.FREE

    /** Selecting the shown tab again goes back to its root; another tab comes back where it was left. */
    fun selectTab(state: HomeUiState, tab: Tab): HomeUiState {
        if (tab == state.tab) return if (state.screens.size > 1) popTo(state, 1) else state
        val others = state.tabStacks + (state.tab to state.screens) - tab
        return state.copy(tab = tab, screens = state.tabStacks[tab] ?: listOf(tab.root), tabStacks = others)
            .clearedResults()
    }

    /** Keeps the first [size] screens of the current stack; groups that close lose their selection (E4). */
    private fun popTo(state: HomeUiState, size: Int): HomeUiState {
        val closed = state.screens.drop(size).filterIsInstance<Screen.Group>().map {
            GroupKey.of(it.ruleId, it.category)
        }
        return state.copy(
            screens = state.screens.take(size),
            selections = state.selections - closed.toSet(),
            swipes = state.swipes - closed.toSet(),
        ).clearedResults()
    }

    private fun HomeUiState.clearedResults() = copy(cleanResult = null, measured = null, trimResult = null)
}

/** E4: every saved-state key in use: the shown stack and the stacks of the tabs left behind. */
internal fun HomeUiState.allScreenKeys(): List<String> =
    (tabStacks + (tab to screens)).flatMap { (t, stack) -> stack.mapIndexed { i, s -> screenKey(t, i, s) } }

/** UI-2: from this width on (Material's "medium" window class) the tabs are a NavigationRail at the start side. */
val RAIL_MIN_WIDTH: Dp = 600.dp

/** Test tag of the bottom navigation and of the rail. */
const val BOTTOM_NAV_TAG = "bottomNav"
const val NAV_RAIL_TAG = "navRail"

/** F1b: what the main tabs' frame needs (top app bar and tabs). */
class TabsUi(
    val onSelectTab: (Tab) -> Unit = {},
    val onOpenTrash: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    /** PR H3: the "?" opens the offline Help. */
    val onOpenHelp: () -> Unit = {},
)

/**
 * A main tab: the top app bar (wordmark, Trash chip while the trash isn't empty, Settings) above [content], and the
 * tabs: a bottom bar on phones, a NavigationRail at the start side on wide screens.
 */
@Composable
fun MainTabs(tab: Tab, trash: TrashUi, ui: TabsUi, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= RAIL_MIN_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.testTag(NAV_RAIL_TAG),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = t == tab,
                            onClick = { ui.onSelectTab(t) },
                            icon = { Icon(tabIcon(t), contentDescription = null) },
                            label = { RailLabel(stringResource(tabLabel(t)), Modifier.testTag("tabLabel_${t.name}")) },
                            modifier = Modifier.testTag("tab_${t.name}"),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    // Owner brief H2 item 12: the bar lines up with the content's centred column.
                    ReadableWidthBar { MainTopBar(trash, ui) }
                    Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) { content() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                MainTopBar(trash, ui)
                // The top bar pads for the status bar and the tabs for the navigation bar; the screen between pads
                // for neither again.
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.systemBars)) { content() }
                NavigationBar(
                    modifier = Modifier.testTag(BOTTOM_NAV_TAG),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { ui.onSelectTab(t) },
                            icon = { Icon(tabIcon(t), contentDescription = null) },
                            label = { TabLabel(stringResource(tabLabel(t)), Modifier.testTag("tabLabel_${t.name}")) },
                            modifier = Modifier.testTag("tab_${t.name}"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * F3: a bottom tab's label on one line; at large font sizes it shrinks just enough to fit its tab ("Free up space"
 * was cut to "Free up" at 200%). It never gets smaller than the default size at 100%.
 */
@Composable
private fun TabLabel(text: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelMedium
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    Text(
        text,
        // Owner brief H2 item 12: a margin from the tab's edges, also when the label shrinks at 200%.
        modifier = Modifier.padding(horizontal = 4.dp).then(modifier),
        maxLines = 1,
        softWrap = false,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
            minFontSize = style.fontSize / fontScale.coerceAtLeast(1f),
            maxFontSize = style.fontSize,
            stepSize = 0.5.sp,
        ),
    )
}

/**
 * Owner brief H2 item 12: a rail tab's label (tablets, foldables) on at most two lines, wrapping only between words
 * and never wider than the rail; at large font sizes it shrinks just enough, never below the default size at 100%.
 */
@Composable
private fun RailLabel(text: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelMedium
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    Text(
        text,
        modifier = modifier,
        maxLines = 2,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        autoSize = io.github.tomerar.keepclear.core.designsystem.component.WholeWordsAutoSize(
            min = style.fontSize / fontScale.coerceAtLeast(1f),
            max = style.fontSize,
        ),
    )
}

private fun tabLabel(tab: Tab) = when (tab) {
    Tab.FREE -> R.string.tab_free
    Tab.BACKUP -> R.string.tab_backup
    Tab.APPS -> R.string.tab_apps
}

@Composable
private fun tabIcon(tab: Tab): ImageVector = when (tab) {
    Tab.FREE -> KcIcons.Safe
    Tab.BACKUP -> KcIcons.Move
    Tab.APPS -> KcIcons.Apps
}

/**
 * The main tabs' top app bar: a small wordmark (the screen's heading), the Trash chip with its size whenever the
 * trash isn't empty (KeepClear's trash and Android's, C73), Help ("?", PR H3) and the Settings gear.
 */
@Composable
fun MainTopBar(trash: TrashUi, ui: TabsUi) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .heightIn(min = 64.dp)
                .padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // F3: the wordmark is a logo and keeps its size at large font sizes (it would take the Trash chip's
            // room at 200%, cutting its size to "2…"); everything else on the bar still grows with the font.
            val title = MaterialTheme.typography.titleLarge
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            Text(
                stringResource(R.string.app_name),
                style = title.copy(
                    fontSize = title.fontSize / fontScale,
                    lineHeight = if (title.lineHeight.isSp) title.lineHeight / fontScale else title.lineHeight,
                ),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.semantics { heading() }.testTag("wordmark"),
            )
            // The wordmark keeps its width; the chip takes what is left and shortens its label if it must.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { TrashChip(trash, ui.onOpenTrash) }
            // Owner brief H3 item 1: Help is one tap away from every main tab.
            IconButton(onClick = ui.onOpenHelp, modifier = Modifier.testTag("openHelp")) {
                Icon(KcIcons.Help, contentDescription = stringResource(R.string.help_title))
            }
            IconButton(onClick = ui.onOpenSettings, modifier = Modifier.testTag("openSettings")) {
                Icon(KcIcons.Settings, contentDescription = stringResource(R.string.settings_title))
            }
        }
    }
}

/** Whether the Trash chip looks empty (muted); tests read it. */
val TrashChipEmpty = androidx.compose.ui.semantics.SemanticsPropertyKey<Boolean>("TrashChipEmpty")

/** Whether the Trash chip's words fit whole (never cut); tests read it. */
val TrashChipFits = androidx.compose.ui.semantics.SemanticsPropertyKey<Boolean>("TrashChipFits")

/** The Trash chip's visible words (TalkBack hears its description instead); tests read it. */
val TrashChipLabel = androidx.compose.ui.semantics.SemanticsPropertyKey<String>("TrashChipLabel")

/**
 * Owner brief H2 item 3: the Trash chip with a word, „סל · 258MB” (TalkBack: „סל המחזור · 258MB”). It is always
 * there, so the Trash is always one tap away; when the trash is empty it reads „סל” and looks muted (outline and
 * text in the variant colour), but it still opens the Trash. Never "0B".
 */
@Composable
private fun TrashChip(trash: TrashUi, onOpenTrash: () -> Unit) {
    val bytes = trashChipBytes(trash)
    val locale = LocalConfiguration.current.locales[0]
    val empty = bytes == null
    val size = bytes?.let { Bidi.isolate(ByteSize.format(it, locale)) }
    val visible = if (size != null) {
        stringResource(R.string.trash_chip_label, size)
    } else {
        stringResource(R.string.trash_chip_empty_label)
    }
    val spoken = if (size != null) {
        stringResource(R.string.trash_chip, size)
    } else {
        stringResource(R.string.trash_chip_empty_description)
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val large = fontScale >= 1.5f
    val labelStyle = MaterialTheme.typography.labelLarge
    var fits by remember { mutableStateOf(true) }
    AssistChip(
        onClick = onOpenTrash,
        label = {
            Text(
                visible,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = KeepClearTheme.type.number.fontFamily,
                    fontFeatureSettings = KeepClearTheme.type.number.fontFeatureSettings,
                ),
                maxLines = 1,
                softWrap = false,
                onTextLayout = { fits = !it.hasVisualOverflow },
                // Shrinks rather than cutting the size, never below its size at 100% font.
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                    minFontSize = labelStyle.fontSize / fontScale.coerceAtLeast(1f),
                    maxFontSize = labelStyle.fontSize,
                    stepSize = 0.5.sp,
                ),
            )
        },
        // At large font sizes the word „סל” says what the chip is; the icon gives its room to the size (F3: the
        // whole size next to the wordmark at 200%).
        leadingIcon = if (large) {
            null
        } else {
            { Icon(KcIcons.Trash, contentDescription = null, Modifier.size(18.dp)) }
        },
        shape = io.github.tomerar.keepclear.core.designsystem.KcShapes.chip,
        colors = if (empty) {
            androidx.compose.material3.AssistChipDefaults.assistChipColors(
                labelColor = muted,
                leadingIconContentColor = muted,
            )
        } else {
            androidx.compose.material3.AssistChipDefaults.assistChipColors()
        },
        border = if (empty) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            androidx.compose.material3.AssistChipDefaults.assistChipBorder(enabled = true)
        },
        modifier = Modifier.clearAndSetSemantics {
            testTag = "trashChip"
            contentDescription = spoken
            role = Role.Button
            this[TrashChipEmpty] = empty
            this[TrashChipLabel] = visible
            this[TrashChipFits] = fits
            onClick {
                onOpenTrash()
                true
            }
        },
    )
}

/** The Trash chip's size, or null when there is nothing in the trash (the chip then reads „סל”, muted). */
internal fun trashChipBytes(trash: TrashUi): Long? =
    if (trash.entries.isEmpty() && trash.android.isEmpty()) null else trash.bytes + trash.android.sumOf { it.size }

/**
 * A sub-screen's top app bar: the auto-mirrored back arrow and the screen's [title] (a heading). Replaces the
 * "Back" text link (F1b).
 */
@Composable
fun SubScreenTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                Icon(KcIcons.Back, contentDescription = stringResource(R.string.back))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 16.dp).semantics { heading() }.testTag("screenTitle"),
            )
        }
    }
}
