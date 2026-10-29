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
 * The callbacks a fallback notice can invoke. A missing callback hides its action, so a screen that
 * cannot leave for account settings shows only the explanation for that state.
 */
internal class DynamicContractFallbackActions(
    val onCheckAgain: () -> Unit,
    val onOpenAccountSettings: (() -> Unit)? = null,
)

/** The callback for [notice]'s action, or null when this screen cannot perform it. */
internal fun DynamicContractFallbackActions.callbackFor(notice: DynamicContractFallbackNotice): (() -> Unit)? =
    when (notice.action) {
        DynamicContractFallbackAction.CheckAgain -> onCheckAgain
        DynamicContractFallbackAction.OpenAccountSettings -> onOpenAccountSettings
    }

/**
 * Shown instead of the app-identity record when discovery found no verified API for an app.
 *
 * The identity record (app ID, name, "server-installed") is not useful to a person, so this
 * explains what happened, from the typed [reason], and offers the action that can help, if any.
 * Discovery diagnostics stay in support diagnostics; they can contain exception text.
 */
@Composable
internal fun DynamicMetadataFallbackNotice(
    appId: String,
    appName: String,
    reason: DynamicContractFallbackReason?,
    actions: DynamicContractFallbackActions,
) {
    val notice = dynamicMetadataFallbackNotice(appName, reason)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium, Alignment.CenterVertically),
    ) {
        Icon(NextcloudIcons.app(appId), contentDescription = null, modifier = Modifier.size(40.dp))
        Text(
            notice.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            notice.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        actions.callbackFor(notice)?.let { onClick ->
            OutlinedButton(onClick = onClick) { Text(notice.actionLabel) }
        }
    }
}

/**
 * The notice for [reason]. When the installed version offers no verifiable interface, the copy names
 * the app; every other reason, such as an unreachable source or rejected credentials, keeps its own
 * explanation and action.
 */
internal fun dynamicMetadataFallbackNotice(
    appName: String,
    reason: DynamicContractFallbackReason?,
): DynamicContractFallbackNotice = if (reason == DynamicContractFallbackReason.NoVerifiedContract) {
    DynamicContractFallbackNotice(dynamicMetadataFallbackTitle(appName), dynamicMetadataFallbackMessage(appName), "Check again")
} else {
    reason.fallbackNotice()
}

internal fun dynamicMetadataFallbackTitle(appName: String): String = "$appName can't be shown natively yet"

internal fun dynamicMetadataFallbackMessage(appName: String): String =
    "The installed version of $appName does not publish an interface that nati.ve can verify, " +
        "so nati.ve shows nothing rather than guessing. Your $appName data on the server is unchanged. " +
        "Check again after the app is updated on the server."
