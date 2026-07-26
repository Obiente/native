package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun FileShareCreationFields(
    target: FileShareTarget,
    capabilities: NextcloudFileSharingCapabilities,
    details: FileShareCreationDetails,
    enabled: Boolean,
    onDetailsChanged: (FileShareCreationDetails) -> Unit,
) {
    val passwordPolicy = capabilities.passwordPolicy(target)
    val expirationPolicy = capabilities.expirationPolicy(target)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        if (passwordPolicy.supported) {
            OutlinedTextField(
                value = details.password,
                enabled = enabled,
                onValueChange = { onDetailsChanged(details.copy(password = it)) },
                label = {
                    Text(if (passwordPolicy.enforced) "Password (required)" else "Password (optional)")
                },
                supportingText = {
                    Text("The password is sent securely and is not stored by this app.")
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (expirationPolicy.supported) {
            Text("Expiration", style = MaterialTheme.typography.labelLarge)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                contentPadding = PaddingValues(end = NextcloudSpacing.Small),
            ) {
                item {
                    FilterChip(
                        selected = details.expiration == FileShareExpiration.ServerDefault,
                        enabled = enabled,
                        onClick = {
                            onDetailsChanged(details.copy(expiration = FileShareExpiration.ServerDefault))
                        },
                        label = { Text("Server default", maxLines = 1) },
                    )
                }
                if (!expirationPolicy.enforced) {
                    item {
                        FilterChip(
                            selected = details.expiration == FileShareExpiration.NoExpiration,
                            enabled = enabled,
                            onClick = {
                                onDetailsChanged(details.copy(expiration = FileShareExpiration.NoExpiration))
                            },
                            label = { Text("No expiration", maxLines = 1) },
                        )
                    }
                }
                item {
                    FilterChip(
                        selected = details.expiration is FileShareExpiration.OnDate,
                        enabled = enabled,
                        onClick = {
                            val current = (details.expiration as? FileShareExpiration.OnDate)?.isoDate.orEmpty()
                            onDetailsChanged(details.copy(expiration = FileShareExpiration.OnDate(current)))
                        },
                        label = { Text("Choose date", maxLines = 1) },
                    )
                }
            }
            (details.expiration as? FileShareExpiration.OnDate)?.let { expiration ->
                OutlinedTextField(
                    value = expiration.isoDate,
                    enabled = enabled,
                    onValueChange = {
                        onDetailsChanged(details.copy(expiration = FileShareExpiration.OnDate(it)))
                    },
                    label = { Text("Expiration date") },
                    placeholder = { Text("YYYY-MM-DD") },
                    supportingText = { Text("The date uses your Nextcloud account timezone.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        OutlinedTextField(
            value = details.note,
            enabled = enabled,
            onValueChange = { onDetailsChanged(details.copy(note = it)) },
            label = { Text("Note (optional)") },
            supportingText = { Text("This note is stored with the share.") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
