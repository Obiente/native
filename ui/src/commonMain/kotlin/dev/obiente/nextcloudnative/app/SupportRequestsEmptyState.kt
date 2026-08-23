package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun SupportRequestsEmptyState(
    state: SupportDiagnosticsSubmissionState,
    onOpenRecovery: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium)) {
            when (state) {
                SupportDiagnosticsSubmissionState.Initializing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Restoring support requests stored on this device...")
                }
                SupportDiagnosticsSubmissionState.Packaging -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Preparing the private report...", style = MaterialTheme.typography.titleMedium)
                    Text("Requests will update when preparation finishes.")
                }
                is SupportDiagnosticsSubmissionState.Uploading -> {
                    if (state.progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text("Sending the private report...", style = MaterialTheme.typography.titleMedium)
                    Text("Keep the app open until sending finishes or cancel it from New report.")
                }
                SupportDiagnosticsSubmissionState.DeletingSubmittedReport -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Deleting the support request...", style = MaterialTheme.typography.titleMedium)
                    Text("Requests will update after deletion is confirmed.")
                }
                SupportDiagnosticsSubmissionState.Cancelling -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Finishing report cancellation...", style = MaterialTheme.typography.titleMedium)
                    Text("No new report can be started until cancellation finishes safely.")
                }
                is SupportDiagnosticsSubmissionState.RetryableFailure -> {
                    Text("Report recovery needed", style = MaterialTheme.typography.titleMedium)
                    Text("A previous report still needs a safe retry or discard decision.")
                    Button(onClick = onOpenRecovery) { Text("Open report recovery") }
                }
                is SupportDiagnosticsSubmissionState.BlockedByAnotherAccount -> {
                    Text("Report belongs to another account", style = MaterialTheme.typography.titleMedium)
                    Text(state.message)
                    Text(
                        "Switch to the account that started this report to retry or discard it. " +
                            "The active account cannot change another account's pending report.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is SupportDiagnosticsSubmissionState.Rejected -> {
                    Text("Report was not sent", style = MaterialTheme.typography.titleMedium)
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Text("Review the report details before trying a new submission.")
                    Button(onClick = onOpenRecovery) { Text("Review report") }
                }
                SupportDiagnosticsSubmissionState.Cancelled -> {
                    Text("Report submission cancelled", style = MaterialTheme.typography.titleMedium)
                    Text("The cancelled report was not added to Requests.")
                }
                SupportDiagnosticsSubmissionState.AccountRequired -> {
                    Text("Sign in to view support requests", style = MaterialTheme.typography.titleMedium)
                    Text("Private support receipts are scoped to the account that created them.")
                }
                is SupportDiagnosticsSubmissionState.Unsupported -> {
                    Text("Support requests are unavailable", style = MaterialTheme.typography.titleMedium)
                    Text(state.reason)
                }
                SupportDiagnosticsSubmissionState.Idle,
                is SupportDiagnosticsSubmissionState.Submitted,
                -> {
                    Text("No support requests on this device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "This list contains private receipts created by this installation for the active account. " +
                            "Requests from another device cannot be discovered from a support code.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
