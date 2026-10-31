// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.tomerar.keepclear.core.i18n.R

/**
 * M0 placeholder Home: shows the three categories, nothing actionable (no scanning, no deleting).
 * [showDebugTools] adds a debug-only RTL flip so English layouts can be checked mirrored.
 */
@Composable
fun HomeScreen(showDebugTools: Boolean, modifier: Modifier = Modifier) {
    var forceFlip by rememberSaveable { mutableStateOf(false) }
    val systemDirection = LocalLayoutDirection.current
    val direction = when {
        !forceFlip -> systemDirection
        systemDirection == LayoutDirection.Ltr -> LayoutDirection.Rtl
        else -> LayoutDirection.Ltr
    }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Scaffold(modifier = modifier.fillMaxSize()) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Text(
                    stringResource(R.string.home_tagline),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("tagline"),
                )
                Text(stringResource(R.string.home_status_early), style = MaterialTheme.typography.bodyMedium)
                CategoryCard(R.string.home_card_safe_title, R.string.home_card_safe_body)
                CategoryCard(R.string.home_card_review_title, R.string.home_card_review_body)
                CategoryCard(R.string.home_card_move_title, R.string.home_card_move_body)
                Text(stringResource(R.string.home_privacy), style = MaterialTheme.typography.bodySmall)
                if (showDebugTools) {
                    TextButton(onClick = { forceFlip = !forceFlip }) {
                        Text(stringResource(R.string.debug_toggle_rtl))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(title: Int, body: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(R.string.home_coming_soon)) })
        }
    }
}
