package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun DurableMutationRecoveryDialog(
    title: String,
    recordReadable: Boolean,
    resetting: Boolean,
    onCheckAgain: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!resetting) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text(
                    if (recordReadable) {
                        "The previous change is safely recorded, but the server still shows its earlier state. " +
                            "Check the server and try verification again."
                    } else {
                        "The previous change may have reached the server, but its local recovery record can no " +
                            "longer be read. The app will keep writes blocked to avoid repeating it."
                    },
                )
                Text(
                    "Reset only after you have checked the server. Resetting removes the safety record and " +
                        "allows new changes; it does not undo a change that already reached Nextcloud.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onReset,
                enabled = !resetting,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(if (resetting) "Resetting..." else "Reset recovery")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                TextButton(onClick = onCheckAgain, enabled = !resetting) { Text("Check again") }
                TextButton(onClick = onDismiss, enabled = !resetting) { Text("Keep protected") }
            }
        },
    )
}
