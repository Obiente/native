package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
    val status: IncomingShareUploadFileStatus = IncomingShareUploadFileStatus.Pending,
    val uploadedName: String? = null,
)

enum class IncomingShareUploadFileStatus {
    Pending,
    Uploaded,
    Failed,
    OutcomeUnknown,
}

data class IncomingShareUploadPresentation(
    val id: String,
    val files: List<IncomingShareUploadFilePresentation>,
    val state: IncomingShareUploadState,
    val destinationPath: String?,
    val completedFiles: Int,
    val message: String?,
    val canVerifyOutcome: Boolean = false,
)

@Composable
internal fun IncomingShareRecoveryCard(
    requests: List<IncomingShareUploadPresentation>,
    onOpen: (String) -> Unit,
) {
    val request = requests.primaryIncomingShareRecovery() ?: return
    val needsAttention = request.state in setOf(
        IncomingShareUploadState.Failed,
        IncomingShareUploadState.OutcomeUnknown,
        IncomingShareUploadState.Canceled,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (needsAttention) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (needsAttention) "Shared upload needs review" else "Shared upload in progress",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${request.completedFiles} of ${request.files.size} files confirmed" +
                        if (requests.size == 1) "." else "; ${requests.size} recoverable shares total.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = { onOpen(request.id) }) { Text("Open") }
        }
    }
}

internal fun List<IncomingShareUploadPresentation>.primaryIncomingShareRecovery():
    IncomingShareUploadPresentation? = firstOrNull { candidate ->
        candidate.state in setOf(
            IncomingShareUploadState.Failed,
            IncomingShareUploadState.OutcomeUnknown,
            IncomingShareUploadState.Canceled,
        )
    } ?: firstOrNull()

@Composable
fun IncomingShareUploadScreen(
    request: IncomingShareUploadPresentation?,
    accountLabel: String?,
    loading: Boolean,
    queueing: Boolean,
    error: String?,
    corruptRecoveryAvailable: Boolean = false,
    corruptRemovalConfirmationVisible: Boolean = false,
    discardConfirmationVisible: Boolean = false,
    destinationReady: Boolean = true,
    folderPickerOperations: RemoteFolderPickerOperations?,
    folderPickerVisible: Boolean,
    onChooseDestination: () -> Unit,
    onDestinationSelected: (String) -> Unit,
    onFolderPickerDismissed: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onVerifyOutcome: () -> Unit = {},
    onConfirmDiscard: () -> Unit = {},
    onDismissDiscard: () -> Unit = {},
    onRemoveCorruptRecovery: () -> Unit = {},
    onConfirmRemoveCorruptRecovery: () -> Unit = {},
    onDismissRemoveCorruptRecovery: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(NextcloudSpacing.Large),
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
                if (corruptRecoveryAvailable) {
                    Text(
                        "The staged files are preserved. Remove them only if you no longer need to recover this share.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onRemoveCorruptRecovery) { Text("Remove staged files") }
                }
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
                                Text(
                                    file.incomingShareStatusLabel(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (file.status) {
                                        IncomingShareUploadFileStatus.Uploaded ->
                                            MaterialTheme.colorScheme.primary
                                        IncomingShareUploadFileStatus.Failed,
                                        IncomingShareUploadFileStatus.OutcomeUnknown,
                                        -> MaterialTheme.colorScheme.error
                                        IncomingShareUploadFileStatus.Pending ->
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                IncomingShareActions(
                    request,
                    queueing,
                    destinationReady,
                    onChooseDestination,
                    onCancel,
                    onDone,
                    onVerifyOutcome,
                )
            }
        }
    }
    if (folderPickerVisible && folderPickerOperations != null) {
        RemoteFolderPickerDialog(
            operations = folderPickerOperations,
            initialPath = request?.destinationPath.orEmpty(),
            onDismiss = onFolderPickerDismissed,
            onSelected = onDestinationSelected,
        )
    }
    if (corruptRemovalConfirmationVisible) {
        AlertDialog(
            onDismissRequest = onDismissRemoveCorruptRecovery,
            title = { Text("Remove staged files?") },
            text = {
                Text("This permanently deletes the local recovery copy for this share. Files already uploaded to Nextcloud are not removed.")
            },
            confirmButton = {
                Button(onClick = onConfirmRemoveCorruptRecovery) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = onDismissRemoveCorruptRecovery) { Text("Keep files") }
            },
        )
    }
    if (discardConfirmationVisible) {
        AlertDialog(
            onDismissRequest = onDismissDiscard,
            title = { Text("Discard staged files?") },
            text = {
                Text("This permanently deletes the private recovery copy. Choose Keep for later to close without losing access to this share.")
            },
            confirmButton = {
                Button(onClick = onConfirmDiscard) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscard) { Text("Keep for later") }
            },
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
        IncomingShareUploadState.Failed ->
            "${request.completedFiles} of ${request.files.size} files uploaded. " +
                (request.message ?: "The remaining upload could not continue.")
        IncomingShareUploadState.OutcomeUnknown ->
            "${request.completedFiles} of ${request.files.size} files are confirmed uploaded. " +
                (request.message ?: "The next file result is unknown. Check Files before trying again.")
        IncomingShareUploadState.Canceled -> request.message ?: "Upload canceled."
    }
    if (request.state in setOf(IncomingShareUploadState.Queued, IncomingShareUploadState.Uploading)) {
        LinearProgressIndicator(
            progress = { request.completedFiles.toFloat() / request.files.size },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        label,
        color = if (
            request.state in setOf(IncomingShareUploadState.Failed, IncomingShareUploadState.OutcomeUnknown) ||
            request.files.any { it.status == IncomingShareUploadFileStatus.OutcomeUnknown }
        ) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

private fun IncomingShareUploadFilePresentation.incomingShareStatusLabel(): String = when (status) {
    IncomingShareUploadFileStatus.Pending -> "Waiting to upload"
    IncomingShareUploadFileStatus.Uploaded ->
        uploadedName?.let { "Uploaded as $it" } ?: "Uploaded"
    IncomingShareUploadFileStatus.Failed -> "Not uploaded; safe to retry"
    IncomingShareUploadFileStatus.OutcomeUnknown -> "Result unknown; check Nextcloud Files"
}

@Composable
private fun IncomingShareActions(
    request: IncomingShareUploadPresentation,
    queueing: Boolean,
    destinationReady: Boolean,
    onChooseDestination: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onVerifyOutcome: () -> Unit,
) {
    when (request.state) {
        IncomingShareUploadState.Staged,
        IncomingShareUploadState.Failed,
        -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Button(
                enabled = !queueing,
                onClick = onChooseDestination,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (queueing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    when {
                        !destinationReady -> "Retry account"
                        request.state == IncomingShareUploadState.Failed -> "Retry"
                        else -> "Choose folder"
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
            ) {
                TextButton(enabled = !queueing, onClick = onDone) { Text("Keep for later") }
                TextButton(enabled = !queueing, onClick = onCancel) { Text("Discard...") }
            }
        }
        IncomingShareUploadState.Queued,
        IncomingShareUploadState.Uploading,
        -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCancel) { Text("Cancel upload") }
            TextButton(onClick = onDone) { Text("Run in background") }
        }
        IncomingShareUploadState.Completed ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDone) { Text("Done") }
            }
        IncomingShareUploadState.OutcomeUnknown ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(enabled = !queueing, onClick = onDone) { Text("Close") }
                Button(enabled = !queueing && request.canVerifyOutcome, onClick = onVerifyOutcome) {
                    if (queueing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text("Verify result")
                }
            }
        IncomingShareUploadState.Canceled -> {
            val outcomeUnknown = request.files.any { it.status == IncomingShareUploadFileStatus.OutcomeUnknown }
            if (outcomeUnknown) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(enabled = !queueing, onClick = onDone) { Text("Close") }
                    Button(enabled = !queueing && request.canVerifyOutcome, onClick = onVerifyOutcome) {
                        Text("Verify result")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
                ) {
                    TextButton(enabled = !queueing, onClick = onDone) { Text("Keep for later") }
                    TextButton(enabled = !queueing, onClick = onCancel) { Text("Discard...") }
                }
            }
        }
    }
}

private fun incomingShareFileSizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes bytes"
}

internal fun incomingShareRecoveryRefreshMillis(hasRecoveries: Boolean): Long =
    if (hasRecoveries) INCOMING_SHARE_RECOVERY_REFRESH_MILLIS else EMPTY_INCOMING_SHARE_RECOVERY_REFRESH_MILLIS

private const val INCOMING_SHARE_RECOVERY_REFRESH_MILLIS = 2_000L
private const val EMPTY_INCOMING_SHARE_RECOVERY_REFRESH_MILLIS = 5_000L
