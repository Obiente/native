package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun TrustedCertificateSettings(
    certificate: TrustedServerCertificate,
    error: String?,
    onRemove: () -> Unit,
) {
    var confirmRemoval by remember { mutableStateOf(false) }
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("Stop trusting this certificate?") },
            text = {
                Text(
                    "Nextcloud Native will return to the operating system's normal certificate checks. " +
                        "The account may stop connecting until the server uses a trusted certificate.",
                )
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Cancel") } },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRemoval = false
                        onRemove()
                    },
                ) { Text("Stop trusting") }
            },
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text("Explicitly trusted server certificate", style = MaterialTheme.typography.titleSmall)
            Text(
                "Nextcloud Native accepts only this SHA-256 fingerprint for the current server address.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(certificate.sha256Fingerprint, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmRemoval = true }) { Text("Stop trusting") }
            error?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
