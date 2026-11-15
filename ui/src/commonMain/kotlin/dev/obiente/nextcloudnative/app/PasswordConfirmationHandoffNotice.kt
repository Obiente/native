package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/**
 * Why profile and administrator changes are read-only.
 *
 * Every provisioning and app-store write route carries `PasswordConfirmationRequired`, and
 * Nextcloud's middleware does not accept a sign-in made with an app password as that
 * confirmation. nati.ve signs in with an app password and never collects the account password,
 * so these changes wait for an explicit authenticated handoff that is not built yet.
 */
internal const val PASSWORD_CONFIRMATION_HANDOFF_TITLE = "Changes need a password confirmation"

internal const val PASSWORD_CONFIRMATION_HANDOFF_MESSAGE =
    "Nextcloud asks for a password confirmation before these changes, and the app's sign-in cannot " +
        "provide one. nati.ve never asks for your account password, so this stays read-only until a " +
        "secure sign-in handoff for confirmations is available."

@Composable
internal fun PasswordConfirmationHandoffNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("password-confirmation-handoff-notice"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(Modifier.padding(NextcloudSpacing.Medium), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(PASSWORD_CONFIRMATION_HANDOFF_TITLE, style = MaterialTheme.typography.titleSmall)
            Text(
                PASSWORD_CONFIRMATION_HANDOFF_MESSAGE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
