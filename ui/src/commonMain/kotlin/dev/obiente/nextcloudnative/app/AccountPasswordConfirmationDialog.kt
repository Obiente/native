package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/**
 * Confirms one change with the account password.
 *
 * The typed password lives only in this dialog's remembered state. It is never saved for
 * restoration, and it is wiped from the dialog when the dialog closes. [onConfirm] receives a
 * one-use secret that the caller clears after its requests.
 */
@Composable
internal fun AccountPasswordConfirmationDialog(
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: (AccountPasswordConfirmationSecret) -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    busy: Boolean = false,
    errorMessage: String? = null,
    transportAvailable: Boolean = true,
) {
    var password by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { password = "" } }
    val submit = {
        if (password.isNotEmpty() && password.length <= MAX_ACCOUNT_PASSWORD_LENGTH && !busy && transportAvailable) {
            val secret = AccountPasswordConfirmationSecret(password.toCharArray())
            password = ""
            onConfirm(secret)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                if (transportAvailable) {
                    Text(
                        "Nextcloud asks for your account password before this change. It is sent with this " +
                            "change only and is not saved on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { value -> password = value.take(MAX_ACCOUNT_PASSWORD_LENGTH) },
                        modifier = Modifier.fillMaxWidth().testTag("account-password-confirmation-field"),
                        label = { Text("Account password") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                } else {
                    Text(
                        ConfirmedWriteRejection.TransportMissing.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (busy) {
                    Text(
                        "Applying the change...",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                errorMessage?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                onClick = submit,
                enabled = transportAvailable && !busy && password.isNotEmpty(),
                modifier = Modifier.testTag("account-password-confirmation-submit"),
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(confirmLabel) }
        },
    )
}

/** UI copy for a confirmed write that did not apply. */
internal fun ConfirmedWriteOutcome.failureMessage(): String? = when (this) {
    ConfirmedWriteOutcome.Applied -> null
    is ConfirmedWriteOutcome.Rejected -> listOfNotNull(reason.message, serverMessage).joinToString(" ")
    ConfirmedWriteOutcome.Unverified ->
        "The server did not confirm the change. The list was refreshed; check it before trying again."
}
