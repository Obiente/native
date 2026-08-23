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
                is SupportDiagnosticsSubmissionState.RetryableFailure,
                is SupportDiagnosticsSubmissionState.BlockedByAnotherAccount,
                -> {
                    Text("Report recovery needed", style = MaterialTheme.typography.titleMedium)
                    Text("A previous report still needs a safe retry or discard decision.")
                    Button(onClick = onOpenRecovery) { Text("Open report recovery") }
                }
                else -> {
                    Text("No support requests on this device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "This list contains private receipts created by this installation for the active account. " +
                            "Requests from another device cannot be discovered from a support code.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when (state) {
                        SupportDiagnosticsSubmissionState.AccountRequired ->
                            Text("Sign in to view account-scoped receipts.")
                        is SupportDiagnosticsSubmissionState.Unsupported -> Text(state.reason)
                        else -> Unit
                    }
                }
            }
        }
    }
}
