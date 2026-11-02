// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * M12, E12: shows Android's confirmation for the first pending request of [consent], one dialog at a time. Each
 * request has its own id; once its dialog is launched ([onLaunched]) it is never launched again, also not after the
 * activity is recreated (rotation, dark theme, language), because the ViewModel remembers it. The answer comes back
 * through the activity-result launcher and counts only for that request.
 */
@Composable
fun MediaConsentEffect(
    consent: MediaConsent?,
    /** Builds the request right before it shows (a second request of the same kind would cancel the first). */
    build: suspend () -> Any?,
    onLaunched: (id: Int) -> Unit,
    launch: (request: Any) -> Unit,
) {
    LaunchedEffect(consent?.headId, consent?.needsLaunch) {
        val c = consent ?: return@LaunchedEffect
        if (!c.needsLaunch) return@LaunchedEffect
        val request = build() ?: return@LaunchedEffect
        onLaunched(c.headId)
        launch(request)
    }
}
