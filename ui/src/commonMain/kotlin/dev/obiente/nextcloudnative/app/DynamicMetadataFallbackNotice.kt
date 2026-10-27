package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/**
 * Shown instead of the app-identity record when discovery found no verified API for an app.
 *
 * The identity record (app ID, name, "server-installed") is not useful to a person, and the
 * situation is not a transient load failure, so this explains what happened and what is safe.
 * Discovery diagnostics stay in support diagnostics; they can contain exception text.
 */
@Composable
internal fun DynamicMetadataFallbackNotice(
    appId: String,
    appName: String,
    onCheckAgain: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium, Alignment.CenterVertically),
    ) {
        Icon(NextcloudIcons.app(appId), contentDescription = null, modifier = Modifier.size(40.dp))
        Text(
            dynamicMetadataFallbackTitle(appName),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            dynamicMetadataFallbackMessage(appName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        OutlinedButton(onClick = onCheckAgain) { Text("Check again") }
    }
}

internal fun dynamicMetadataFallbackTitle(appName: String): String = "$appName can't be shown natively yet"

internal fun dynamicMetadataFallbackMessage(appName: String): String =
    "The installed version of $appName does not publish an interface that nati.ve can verify, " +
        "so nati.ve shows nothing rather than guessing. Your $appName data on the server is unchanged. " +
        "Check again after the app is updated on the server."
