// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.designsystem.KcIcons
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.i18n.R

/** Where a Help topic can take the user (screens only: a link never deletes or changes anything). */
enum class HelpLink { TRASH, RESTORE_FROM_BACKUP }

/** One Help topic: its title and its body (paragraphs split by a blank line), both string resources. */
data class HelpTopic(
    val id: String,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val links: List<HelpLink> = emptyList(),
    /** Owner brief H4 item 5: the topic ends with this phone maker's steps to keep the backup running. */
    val batterySteps: Boolean = false,
)

/**
 * Owner brief H3 item 1: the offline Help, as data (string resources per language, I18N-1 parity). The order is the
 * brief's; PR H4 added "Manage space / keep data on uninstall" and "Private space" at the end. Nothing else
 * needs to change for a new topic.
 */
object HelpTopics {
    fun all(foss: Boolean = BuildConfig.FLAVOR == "foss"): List<HelpTopic> = listOf(
        HelpTopic("how", R.string.help_how_title, R.string.help_how_body),
        HelpTopic("safe", R.string.help_safe_title, R.string.help_safe_body),
        HelpTopic("permanent", R.string.help_permanent_title, R.string.help_permanent_body, listOf(HelpLink.TRASH)),
        HelpTopic(
            GET_BACK,
            R.string.help_get_back_title,
            R.string.help_get_back_body,
            listOf(HelpLink.TRASH, HelpLink.RESTORE_FROM_BACKUP),
        ),
        // The `play` build has no "All files access" (ADR 0005): it asks for WhatsApp's folder instead.
        if (foss) {
            HelpTopic("access", R.string.help_access_title, R.string.help_access_body)
        } else {
            HelpTopic("access", R.string.help_access_title_play, R.string.help_access_body_play)
        },
        HelpTopic("whatsapp", R.string.help_whatsapp_title, R.string.help_whatsapp_body),
        HelpTopic("stopped", R.string.help_stopped_title, R.string.help_stopped_body, batterySteps = true),
        HelpTopic(
            "new_phone",
            R.string.help_new_phone_title,
            R.string.help_new_phone_body,
            listOf(HelpLink.RESTORE_FROM_BACKUP),
        ),
        // PR H4 (owner brief H4 items 1 and 9).
        HelpTopic(
            MANAGE_SPACE,
            R.string.help_manage_space_title,
            R.string.help_manage_space_body,
            listOf(HelpLink.TRASH),
        ),
        HelpTopic("private_space", R.string.help_private_space_title, R.string.help_private_space_body),
        // G4a: the read-only comparison ("Find similar photos").
        HelpTopic(SIMILAR, R.string.help_similar_title, R.string.help_similar_body),
    )

    fun byId(id: String, foss: Boolean = BuildConfig.FLAVOR == "foss"): HelpTopic? = all(foss).firstOrNull {
        it.id == id
    }

    /** "How to restore a file", which Settings' card also opens. */
    const val GET_BACK = "get_back"

    /** PR H4: "KeepClear's storage and uninstalling" (Android's "Manage space" and keeping data on uninstall). */
    const val MANAGE_SPACE = "manage_space"

    /** G4a: "Similar photos" (what the comparison does and does not do). */
    const val SIMILAR = "similar"
}

/** A topic's body as its paragraphs. */
fun helpParagraphs(body: String): List<String> = body.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }

/** Owner brief H3 item 1: the list of Help topics (from Settings and from the "?" in the top bar). */
@Composable
fun HelpScreen(topics: List<HelpTopic>, onOpenTopic: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { SubScreenTopBar(stringResource(R.string.help_title), onBack) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("help"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.help_intro), style = MaterialTheme.typography.bodyLarge)
            topics.forEach { topic ->
                KcCard(Modifier.testTag("helpTopic_${topic.id}"), onClick = { onOpenTopic(topic.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(topic.title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(KcIcons.Chevron, contentDescription = null)
                    }
                }
            }
            Text(
                stringResource(R.string.help_more),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One Help topic, with buttons to the screens it talks about. */
@Composable
fun HelpTopicScreen(
    topic: HelpTopic,
    onLink: (HelpLink) -> Unit,
    onBack: () -> Unit,
    battery: BatteryActions = BatteryActions(),
) {
    Scaffold(topBar = { SubScreenTopBar(stringResource(topic.title), onBack) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("helpTopic"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            helpParagraphs(stringResource(topic.body)).forEach { paragraph ->
                Text(paragraph, style = MaterialTheme.typography.bodyLarge)
            }
            topic.links.forEach { link ->
                KcButton(
                    stringResource(
                        when (link) {
                            HelpLink.TRASH -> R.string.settings_get_back_trash
                            HelpLink.RESTORE_FROM_BACKUP -> R.string.restore_start
                        },
                    ),
                    onClick = { onLink(link) },
                    modifier = Modifier.fillMaxWidth().testTag("helpLink_${link.name}"),
                    style = KcButtonStyle.Tonal,
                )
            }
            if (topic.batterySteps) {
                Text(stringResource(R.string.help_battery_steps), style = MaterialTheme.typography.bodyLarge)
                BatteryHelp(battery, Modifier.testTag("helpBattery"))
            }
        }
    }
}
