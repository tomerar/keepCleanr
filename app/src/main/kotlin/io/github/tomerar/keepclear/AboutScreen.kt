// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.common.Bidi
import io.github.tomerar.keepclear.core.designsystem.component.InfoSheet
import io.github.tomerar.keepclear.core.designsystem.component.KcButton
import io.github.tomerar.keepclear.core.designsystem.component.KcButtonStyle
import io.github.tomerar.keepclear.core.designsystem.component.KcCard
import io.github.tomerar.keepclear.core.i18n.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A licence text shipped in the APK (`assets/licenses/`, from core:designsystem). Its name is a proper name. */
enum class LicenceText(val title: String, val asset: String) {
    APACHE_2("Apache License 2.0", "licenses/Apache-2.0-Material-Symbols.txt"),
    OFL_RUBIK("SIL Open Font License 1.1", "licenses/OFL-1.1-Rubik.txt"),
    OFL_HEEBO("SIL Open Font License 1.1", "licenses/OFL-1.1-Heebo.txt"),
    MIT("MIT License", "licenses/MIT.txt"),
}

/**
 * One font, icon set or library in the APK, with its copyright line and licence. Names are proper names (never
 * translated). [covers] are the Maven group prefixes (or `font:`/`icons:` ids) it stands for: `LicencesTest` checks
 * every runtime dependency in the build files and every row of THIRD_PARTY.md is covered, without a licence plugin.
 */
data class Licence(val name: String, val notice: String, val text: LicenceText, val covers: List<String>)

object Licences {
    val all: List<Licence> = listOf(
        Licence("Rubik", "Copyright 2015 The Rubik Project Authors", LicenceText.OFL_RUBIK, listOf("font:rubik")),
        Licence("Heebo", "Copyright 2014 The Heebo Project Authors", LicenceText.OFL_HEEBO, listOf("font:heebo")),
        Licence("Material Symbols", "Copyright Google LLC", LicenceText.APACHE_2, listOf("icons:material-symbols")),
        Licence(
            "AndroidX, Jetpack Compose, Room, WorkManager",
            "Copyright The Android Open Source Project",
            LicenceText.APACHE_2,
            listOf("androidx.", "com.google.guava", "org.jspecify"),
        ),
        Licence(
            "Kotlin, kotlinx.coroutines, kotlinx.serialization",
            "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors",
            LicenceText.APACHE_2,
            listOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jetbrains:annotations"),
        ),
        Licence(
            "SMBJ, ASN-One",
            "Copyright the SMBJ and ASN-One contributors",
            LicenceText.APACHE_2,
            listOf("com.hierynomus"),
        ),
        Licence(
            "OkHttp, Okio",
            "Copyright Square, Inc.",
            LicenceText.APACHE_2,
            listOf("com.squareup.okhttp3", "com.squareup.okio"),
        ),
        Licence(
            "Bouncy Castle",
            "Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc.",
            LicenceText.MIT,
            listOf("org.bouncycastle"),
        ),
        Licence("SLF4J API", "Copyright (c) 2004-2022 QOS.ch Sarl", LicenceText.MIT, listOf("org.slf4j")),
        Licence("MBassador", "Copyright (c) 2012 Benjamin Diedrichsen", LicenceText.MIT, listOf("net.engio")),
    )

    fun covering(module: String): Licence? = all.firstOrNull { l -> l.covers.any { module.startsWith(it) } }
}

/** What the About screen shows and opens (links open in the browser; KeepClear itself never goes online for them). */
class AboutUi(
    val version: String = "",
    val versionCode: Long = 0,
    val onOpenSource: () -> Unit = {},
    val onOpenPrivacy: () -> Unit = {},
    val onTranslate: () -> Unit = {},
)

/**
 * Owner brief H3 item 5: About: version and build number, source code, privacy (in a sentence here, the full policy
 * in the browser; Settings' About card keeps the one "no ads, no tracking" text, H2c), help translate, translators,
 * and the licences of the fonts, icons and libraries in the APK, each readable in the app.
 */
@Composable
fun AboutScreen(ui: AboutUi, onBack: () -> Unit) {
    var reading by rememberSaveable { mutableStateOf<LicenceText?>(null) }
    Scaffold(topBar = { SubScreenTopBar(stringResource(R.string.settings_about), onBack) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("aboutScreen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.about_version, Bidi.isolate(ui.version), ui.versionCode),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("aboutVersion"),
            )
            KcCard {
                Text(stringResource(R.string.about_source_body), style = MaterialTheme.typography.bodyMedium)
                KcButton(
                    stringResource(R.string.about_source),
                    ui.onOpenSource,
                    Modifier.testTag("aboutSource"),
                    style = KcButtonStyle.Text,
                )
            }
            KcCard {
                Text(stringResource(R.string.about_privacy_body), style = MaterialTheme.typography.bodyMedium)
                KcButton(
                    stringResource(R.string.settings_privacy),
                    ui.onOpenPrivacy,
                    Modifier.testTag("privacy"),
                    style = KcButtonStyle.Text,
                )
            }
            KcCard {
                Text(stringResource(R.string.about_translators), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.about_translators_body), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_translate_body), style = MaterialTheme.typography.bodyMedium)
                KcButton(
                    stringResource(R.string.about_translate),
                    ui.onTranslate,
                    Modifier.testTag("aboutTranslate"),
                    style = KcButtonStyle.Text,
                )
            }
            Text(
                stringResource(R.string.about_licences),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.about_licences_body), style = MaterialTheme.typography.bodyMedium)
            Licences.all.forEach { licence ->
                KcCard(Modifier.testTag("licence")) {
                    // Proper names in Latin letters: left to right inside a Hebrew screen.
                    val ltr = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr)
                    Text(
                        licence.name,
                        style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr),
                    )
                    Text("${licence.notice} · ${licence.text.title}", style = ltr)
                    KcButton(
                        stringResource(R.string.about_licence_read),
                        onClick = { reading = licence.text },
                        style = KcButtonStyle.Text,
                    )
                }
            }
        }
    }
    reading?.let { text -> LicenceSheet(text) { reading = null } }
}

@Composable
private fun LicenceSheet(text: LicenceText, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var body by remember(text) { mutableStateOf("") }
    // E1: read off the main thread.
    LaunchedEffect(text) {
        body = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(text.asset).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    InfoSheet(
        title = text.title,
        dismissText = stringResource(R.string.swipe_close),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("licenceSheet"),
    ) {
        Text(body, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
    }
}

/** Owner brief H3 item 5: a "What's new" note for a bigger update; data, gated by version code. */
data class WhatsNewEntry(val versionCode: Long, val body: Int)

object WhatsNew {
    /**
     * Only bigger updates get an entry (the owner decides which; none yet: the first release has the intro). Add
     * `WhatsNewEntry(<versionCode>, R.string.whats_new_<n>)` with its en and he text.
     */
    val entries: List<WhatsNewEntry> = emptyList()

    /**
     * The note to show once after an update from [lastSeen] to [current]: the newest entry in (lastSeen, current].
     * A first install ([lastSeen] 0) shows none (the intro explains the app).
     */
    fun toShow(lastSeen: Long, current: Long, entries: List<WhatsNewEntry> = this.entries): WhatsNewEntry? =
        if (lastSeen <=
            0
        ) {
            null
        } else {
            entries.filter { it.versionCode in (lastSeen + 1)..current }.maxByOrNull { it.versionCode }
        }

    const val PREFS = "whats_new"
    const val KEY = "lastSeenVersionCode"

    /** Reads and moves the mark to [current] (so a note shows once); returns the note to show, if any. */
    fun take(
        prefs: android.content.SharedPreferences,
        current: Long,
        entries: List<WhatsNewEntry> = this.entries,
    ): WhatsNewEntry? {
        val lastSeen = prefs.getLong(KEY, 0L)
        if (lastSeen != current) prefs.edit().putLong(KEY, current).apply()
        return toShow(lastSeen, current, entries)
    }
}

@Composable
fun WhatsNewSheet(entry: WhatsNewEntry, onDismiss: () -> Unit) {
    InfoSheet(
        title = stringResource(R.string.whats_new_title),
        dismissText = stringResource(R.string.whats_new_ok),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("whatsNew"),
    ) {
        Text(stringResource(entry.body), style = MaterialTheme.typography.bodyLarge)
    }
}
