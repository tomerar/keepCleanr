// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import java.util.Locale

/**
 * Owner brief H4 item 7: language codes compared by meaning, not by spelling. Hebrew is "he" or the old "iw":
 * Java 17+ and Android 15 no longer turn "he" into "iw", older ones do, so a check for only one of them breaks on
 * some phones.
 */
object Languages {
    fun isHebrew(locale: Locale?): Boolean = locale != null && isHebrew(locale.language)

    fun isHebrew(language: String?): Boolean = language != null &&
        (language.equals("he", ignoreCase = true) || language.equals("iw", ignoreCase = true))

    /** The tag Settings' language chips use: "iw" for Hebrew in either spelling, otherwise the language itself. */
    fun chipTag(language: String?): String? = when {
        language.isNullOrEmpty() -> null
        isHebrew(language) -> "iw"
        else -> language.lowercase(Locale.ROOT)
    }
}

/**
 * Owner brief H4 item 7: the in-app language. Android 13+ keeps it itself (LocaleManager, per-app language, also in
 * Android's own settings page). Android 11 and 12 have no per-app language, so KeepClear keeps the choice in its own
 * settings and applies it to its screens and its background work ([wrap] in each component's
 * `attachBaseContext`). No AppCompat dependency is needed for that.
 */
object AppLanguage {
    private const val PREFS = "language"
    private const val KEY = "tag"

    /** True where Android itself keeps the per-app language. */
    fun systemKeeps(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.TIRAMISU

    /** Android 11–12: the stored choice ("iw", "en"), or null for the phone's language. */
    fun stored(context: Context): String? = MainThreadPolicy.settings {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }

    fun store(context: Context, tag: String?) = MainThreadPolicy.settings {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            if (tag == null) remove(KEY) else putString(KEY, tag)
        }
    }

    /** [base] in the stored language on Android 11–12; unchanged on 13+ (Android applies it) or with no choice. */
    fun wrap(base: Context, sdk: Int = Build.VERSION.SDK_INT): Context {
        if (systemKeeps(sdk)) return base
        val tag = runCatching { stored(base) }.getOrNull()
        // No choice stored: the phone's own languages, applied explicitly. The application context may still carry
        // the language chosen when the process started (review of PR #60), so returning [base] would keep it.
        val locales = if (tag == null) {
            android.content.res.Resources.getSystem().configuration.locales
        } else {
            android.os.LocaleList(Locale.forLanguageTag(tag))
        }
        if (tag == null && base.resources.configuration.locales == locales) return base
        Locale.setDefault(locales[0])
        val config = Configuration(base.resources.configuration).apply { setLocales(locales) }
        return base.createConfigurationContext(config)
    }

    /** Android 13+: Android's own per-app language page for KeepClear. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun systemSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${context.packageName}".toUri())
}
