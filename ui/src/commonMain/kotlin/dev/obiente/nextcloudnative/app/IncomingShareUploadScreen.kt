package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

enum class IncomingShareUploadState {
    Staged,
    Queued,
    Uploading,
    Completed,
    Failed,
    OutcomeUnknown,
    Canceled,
}

data class IncomingShareUploadFilePresentation(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
)

data class IncomingShareUploadPresentation(
    val id: String,
    val files: List<IncomingShareUploadFilePresentation>,
    val state: IncomingShareUploadState,
    val destinationPath: String?,
    val completedFiles: Int,
    val message: String?,
)

@Composable
fun IncomingShareUploadScreen(
    request: IncomingShareUploadPresentation?,
    accountLabel: String?,
    loading: Boolean,
    queueing: Boolean,
    error: String?,
    services: NextcloudPlatformServices,
    session: NextcloudSession?,
    userId: String?,
    folderPickerVisible: Boolean,
    onChooseDestination: () -> Unit,
    onDestinationSelected: (String) -> Unit,
    onFolderPickerDismissed: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Text("Upload to Nextcloud", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        when {
            loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Preparing shared files in private storage. You can cancel without blocking the rest of the app.")
            }
            error != null && request == null -> {
                Text(error, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onCancel) { Text("Close") }
            }
            request != null -> {
                accountLabel?.let { label ->
                    Text(
                        "Account: $label",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                request.destinationPath?.let { destinationPath ->
                    Text(
                        "Folder: /${destinationPath.ifEmpty { "Nextcloud" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IncomingShareProgress(request)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    items(request.files, key = IncomingShareUploadFilePresentation::id) { file ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(Modifier.padding(NextcloudSpacing.Medium)) {
                                Text(file.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    incomingShareFileSizeLabel(file.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                IncomingShareActions(request, queueing, onChooseDestination, onCancel, onDone)
            }
        }
    }
    if (folderPickerVisible && session != null && userId != null) {
        RemoteFolderPickerDialog(
            services = services,
            session = session,
            userId = userId,
            initialPath = request?.destinationPath.orEmpty(),
            onDismiss = onFolderPickerDismissed,
            onSelected = onDestinationSelected,
        )
    }
}

@Composable
private fun IncomingShareProgress(request: IncomingShareUploadPresentation) {
    val label = when (request.state) {
        IncomingShareUploadState.Staged ->
            "Choose a destination. Existing names are kept as numbered copies, never overwritten."
        IncomingShareUploadState.Queued -> "Waiting for a network connection."
        IncomingShareUploadState.Uploading -> "${request.completedFiles} of ${request.files.size} files uploaded."
        IncomingShareUploadState.Completed -> "All files were uploaded."
        IncomingShareUploadState.Failed -> request.message ?: "The upload could not start."
        IncomingShareUploadState.OutcomeUnknown ->
            request.message ?: "The server result is unknown. Check Files before trying again."
        IncomingShareUploadState.Canceled -> "Upload canceled."
    }
    if (request.state in setOf(IncomingShareUploadState.Queued, IncomingShareUploadState.Uploading)) {
        LinearProgressIndicator(
            progress = { request.completedFiles.toFloat() / request.files.size },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        label,
        color = if (request.state in setOf(IncomingShareUploadState.Failed, IncomingShareUploadState.OutcomeUnknown)) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun IncomingShareActions(
    request: IncomingShareUploadPresentation,
    queueing: Boolean,
    onChooseDestination: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (request.state) {
            IncomingShareUploadState.Staged,
            IncomingShareUploadState.Failed,
            -> {
                TextButton(enabled = !queueing, onClick = onCancel) { Text("Cancel") }
                Button(enabled = !queueing, onClick = onChooseDestination) {
                    if (queueing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (request.state == IncomingShareUploadState.Failed) "Retry" else "Choose folder")
                }
            }
            IncomingShareUploadState.Queued,
            IncomingShareUploadState.Uploading,
            -> {
                OutlinedButton(onClick = onCancel) { Text("Cancel upload") }
                TextButton(onClick = onDone) { Text("Run in background") }
            }
            IncomingShareUploadState.Completed,
            IncomingShareUploadState.OutcomeUnknown,
            IncomingShareUploadState.Canceled,
            -> Button(onClick = onDone) { Text("Done") }
        }
    }
}

private fun incomingShareFileSizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes bytes"
}
