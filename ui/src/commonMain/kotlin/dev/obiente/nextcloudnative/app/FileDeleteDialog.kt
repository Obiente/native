package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** Confirms one Files delete. Closing it while the delete runs never cancels the request. */
@Composable
internal fun FileDeleteDialog(
    target: NextcloudFile,
    running: Boolean,
    error: String?,
    retryBlocked: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${target.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text(
                    if (target.isDirectory) {
                        "This removes the folder and everything inside it from Nextcloud. This cannot be undone here."
                    } else {
                        "This removes the file from Nextcloud. This cannot be undone here."
                    },
                )
                Text(
                    if (running) {
                        "You can close this dialog. The delete continues and the folder refreshes when it finishes."
                    } else {
                        "The delete is ETag-protected and will stop if the item changed since this folder was loaded."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (running) "Close" else "Cancel") }
        },
        confirmButton = {
            Button(
                enabled = !running && !retryBlocked,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                onClick = onConfirm,
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (running) "Deleting..." else "Delete")
            }
        },
    )
}
