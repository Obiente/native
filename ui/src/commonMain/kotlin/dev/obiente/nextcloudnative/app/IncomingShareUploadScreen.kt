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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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

data class IncomingShareRecoveryPage(
    val requests: List<IncomingShareUploadPresentation> = emptyList(),
    val nextCursor: String? = null,
)

internal class IncomingShareRecoveryPagerState {
    var page by mutableStateOf(IncomingShareRecoveryPage())
    var cursors by mutableStateOf(listOf<String?>(null))
    var pageIndex by mutableIntStateOf(0)
    val cursor: String? get() = cursors[pageIndex]
    val pageNumber: Int get() = pageIndex + 1
    val isVisible: Boolean get() = page.requests.isNotEmpty() || page.nextCursor != null || pageIndex > 0

    fun previous() {
        if (pageIndex > 0) {
            page = IncomingShareRecoveryPage()
            pageIndex -= 1
        }
    }

    fun next(cursor: String) {
        val nextIndex = pageIndex + 1
        page = IncomingShareRecoveryPage()
        cursors = cursors.take(nextIndex) + cursor
        pageIndex = nextIndex
    }
}

@Composable
internal fun rememberIncomingShareRecoveryPager(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    refreshAttempt: Int,
): IncomingShareRecoveryPagerState {
    val pager = remember(session, userId) { IncomingShareRecoveryPagerState() }
    LaunchedEffect(session, userId, refreshAttempt, pager.cursor) {
        if (userId.isNotBlank()) {
            while (true) {
                try {
                    pager.page = services.loadIncomingShareRecoveries(session, userId, pager.cursor)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    pager.page = IncomingShareRecoveryPage()
                }
                delay(incomingShareRecoveryRefreshMillis(pager.isVisible))
            }
        }
    }
    return pager
}

@Composable
internal fun IncomingShareRecoveryCard(
    page: IncomingShareRecoveryPage,
    pageNumber: Int,
    onOpen: (String) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: (String) -> Unit,
) {
    val requests = page.requests
    if (requests.isEmpty() && pageNumber == 1 && page.nextCursor == null) return
    val ordered = requests.filter(IncomingShareUploadPresentation::incomingShareRecoveryNeedsAttention) +
        requests.filterNot(IncomingShareUploadPresentation::incomingShareRecoveryNeedsAttention)
    var pageIndex by remember(ordered.map(IncomingShareUploadPresentation::id)) {
        mutableIntStateOf(0)
    }
    val selectedIndex = if (ordered.isEmpty()) 0 else pageIndex.coerceIn(ordered.indices)
    val request = ordered.getOrNull(selectedIndex)
    val needsAttention = request?.state in setOf(
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
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            request == null -> "No shared uploads on this page"
                            needsAttention -> "Shared upload needs review"
                            else -> "Shared upload in progress"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (request != null) {
                        Text(
                            "${request.completedFiles} of ${request.files.size} files confirmed.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("Continue to check older recoveries.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                request?.let { selected ->
                    Button(onClick = { onOpen(selected.id) }) { Text("Open") }
                }
            }
            val canGoPrevious = selectedIndex > 0 || pageNumber > 1
            val canGoNext = selectedIndex < ordered.lastIndex || page.nextCursor != null
            if (canGoPrevious || canGoNext) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = canGoPrevious,
                        onClick = {
                            if (selectedIndex > 0) pageIndex = selectedIndex - 1 else onPreviousPage()
                        },
                    ) { Text("Previous") }
                    Text(
                        if (request == null) {
                            "Recovery page $pageNumber"
                        } else {
                            "Page $pageNumber, ${selectedIndex + 1} of ${ordered.size}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        enabled = canGoNext,
                        onClick = {
                            if (selectedIndex < ordered.lastIndex) {
                                pageIndex = selectedIndex + 1
                            } else {
                                page.nextCursor?.let(onNextPage)
                            }
                        },
                    ) { Text("Next") }
                }
            } else {
                Text(
                    "Recovery page $pageNumber",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun IncomingShareUploadPresentation.incomingShareRecoveryNeedsAttention(): Boolean =
    state in setOf(
        IncomingShareUploadState.Failed,
        IncomingShareUploadState.OutcomeUnknown,
        IncomingShareUploadState.Canceled,
    )

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
        IncomingShareUploadState.OutcomeUnknown -> AmbiguousIncomingShareActions(
            request,
            queueing,
            onVerifyOutcome,
            onCancel,
            onDone,
        )
        IncomingShareUploadState.Canceled -> {
            val outcomeUnknown = request.files.any { it.status == IncomingShareUploadFileStatus.OutcomeUnknown }
            if (outcomeUnknown) {
                AmbiguousIncomingShareActions(request, queueing, onVerifyOutcome, onCancel, onDone)
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

@Composable
private fun AmbiguousIncomingShareActions(
    request: IncomingShareUploadPresentation,
    queueing: Boolean,
    onVerifyOutcome: () -> Unit,
    onDiscard: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Button(
            enabled = !queueing && request.canVerifyOutcome,
            onClick = onVerifyOutcome,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (queueing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text("Verify result")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
        ) {
            TextButton(enabled = !queueing, onClick = onClose) { Text("Close") }
            TextButton(enabled = !queueing, onClick = onDiscard) { Text("Discard...") }
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
