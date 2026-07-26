package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun NativeMediaTransferCenterHost(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onBack: () -> Unit,
) {
    var section by remember(session) { mutableStateOf(MediaTransferSection.Pending) }
    var cursorHistory by remember(session, section) {
        mutableStateOf<List<MediaBackupLedgerCursor?>>(listOf(null))
    }
    var state by remember(session) { mutableStateOf<MediaTransferCenterState?>(null) }
    var loading by remember(session) { mutableStateOf(true) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var refreshAttempt by remember(session) { mutableIntStateOf(0) }
    var details by remember(session) { mutableStateOf<MediaBackupLedgerRecord?>(null) }
    var notice by remember(session) { mutableStateOf<String?>(null) }
    var clearingCompleted by remember(session) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cursor = cursorHistory.last()

    LaunchedEffect(session) {
        services.observeMediaBackupStatusChanges(session).collectLatest {
            refreshAttempt += 1
        }
    }
    LaunchedEffect(session, section, cursor, refreshAttempt) {
        loading = true
        error = null
        try {
            state = services.loadMediaTransferCenter(session, section, cursor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message ?: "Could not load media transfers."
        } finally {
            loading = false
        }
    }

    val current = state
    when {
        current != null -> MediaTransferCenterScreen(
            state = current,
            loading = loading,
            busyLocalKey = null,
            clearingCompleted = clearingCompleted,
            statusMessage = error ?: notice,
            statusMessageIsError = error != null,
            onBack = onBack,
            onSelectSection = { selected ->
                if (section != selected) {
                    state = null
                    section = selected
                    notice = null
                    error = null
                    details = null
                }
            },
            onLoadNewer = {
                if (cursorHistory.size > 1) {
                    cursorHistory = cursorHistory.dropLast(1)
                }
            },
            onLoadOlder = { nextCursor ->
                if (cursorHistory.last() != nextCursor) {
                    cursorHistory = cursorHistory + nextCursor
                }
            },
            onRetry = { refreshAttempt += 1 },
            onAction = { record, action ->
                if (action == MediaTransferAction.Details) details = record
            },
            onClearCompleted = {
                if (!clearingCompleted) {
                    scope.launch {
                        clearingCompleted = true
                        error = null
                        notice = null
                        try {
                            val removed = services.clearCompletedMediaTransferHistory(session)
                            notice = if (removed == 1) {
                                "Cleared 1 completed transfer from local history."
                            } else {
                                "Cleared $removed completed transfers from local history."
                            }
                            cursorHistory = listOf(null)
                            refreshAttempt += 1
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            error = failure.message ?: "Could not clear completed transfer history."
                        } finally {
                            clearingCompleted = false
                        }
                    }
                }
            },
            visibleActions = { setOf(MediaTransferAction.Details) },
        )
        else -> Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader("Transfers", "Device-local upload history", onBack)
            Column(
                modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(
                    NextcloudSpacing.Large,
                    Alignment.CenterVertically,
                ),
            ) {
                if (loading) CircularProgressIndicator()
                Text(
                    error ?: "Loading media transfers...",
                    color = if (error == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (error != null) {
                    OutlinedButton(onClick = { refreshAttempt += 1 }) {
                        Text("Try again")
                    }
                }
            }
        }
    }

    details?.let { record ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(record.local?.displayName ?: "Transfer details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    Text("Status: ${record.transferState.name}")
                    record.receipt?.remotePath?.let { Text("Nextcloud: /$it") }
                    record.local?.size?.let { Text("Size: $it bytes") }
                    Text("Attempts: ${record.attemptCount}")
                    record.failureMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            },
            confirmButton = {
                TextButton(onClick = { details = null }) {
                    Text("Close")
                }
            },
        )
    }
}
