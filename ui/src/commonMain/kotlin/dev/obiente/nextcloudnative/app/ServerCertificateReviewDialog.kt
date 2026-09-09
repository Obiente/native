package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun ServerCertificateReviewDialog(
    review: ServerCertificateReview,
    checking: Boolean,
    error: String?,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!checking) onDismiss()
        },
        title = { Text("Unverified server certificate") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Text(
                    "Android cannot verify the identity of ${review.serverDisplayName}. " +
                        "Only continue if you obtained this fingerprint from your server administrator through a separate trusted channel.",
                )
                Text("Subject", style = MaterialTheme.typography.labelLarge)
                Text(review.subject, style = MaterialTheme.typography.bodySmall)
                Text("Issuer", style = MaterialTheme.typography.labelLarge)
                Text(review.issuer, style = MaterialTheme.typography.bodySmall)
                Text("SHA-256 fingerprint", style = MaterialTheme.typography.labelLarge)
                Text(review.sha256Fingerprint, style = MaterialTheme.typography.bodySmall)
                Text("Valid from ${review.validFrom} until ${review.validUntil}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Approval is limited to this exact certificate and server address. A changed or expired certificate will require a new review.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !checking,
                onClick = onDismiss,
            ) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                enabled = !checking,
                onClick = onConfirm,
            ) { Text(if (checking) "Checking..." else confirmLabel) }
        },
    )
}
