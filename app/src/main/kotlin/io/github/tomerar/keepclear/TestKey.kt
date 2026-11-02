// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.i18n.R

/**
 * L6 (owner review 2026-11-02): test builds signed with the **public** test key (keystore/) can be updated by
 * anyone who signs an APK with that key, and an update reads what the test app saved, network passwords included.
 * Until CI signs test builds with the owner's private test key (`BuildConfig.PRIVATE_TEST_KEY`, docs/progress.md
 * "Waiting for owner"), the network set-up screens say plainly to use a test account.
 */
object TestKey {
    val showNotice: Boolean get() = BuildConfig.DEBUG && !BuildConfig.PRIVATE_TEST_KEY
}

/** L6: "This is a test build … use a test account" on the network set-up screens. */
@Composable
fun TestAccountNotice() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("testAccountNotice"),
    ) {
        Text(stringResource(R.string.test_build_use_test_account), modifier = Modifier.padding(12.dp))
    }
}
