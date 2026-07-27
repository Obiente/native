package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun NativeMediaTransferCenterHost(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onBack: () -> Unit,
) {
    var sectionName by rememberSaveable(session.serverUrl, session.loginName) {
        mutableStateOf(MediaTransferSection.Pending.name)
    }
    val section = MediaTransferSection.entries.firstOrNull { it.name == sectionName }
        ?: MediaTransferSection.Pending
    var encodedCursorHistory by rememberSaveable(
        session.serverUrl,
        session.loginName,
        sectionName,
    ) {
        mutableStateOf(encodeMediaTransferCursorHistory(listOf(null)))
    }
    val cursorHistory = remember(encodedCursorHistory) {
        restoreMediaTransferCursorHistory(encodedCursorHistory)
    }
    var state by remember(session) { mutableStateOf<MediaTransferCenterState?>(null) }
    var loading by remember(session) { mutableStateOf(true) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var refreshAttempt by remember(session) { mutableIntStateOf(0) }
    var details by remember(session) { mutableStateOf<MediaBackupLedgerRecord?>(null) }
    var notice by remember(session) { mutableStateOf<String?>(null) }
    var clearingCompleted by remember(session) { mutableStateOf(false) }
    var clearCompletedPending by rememberSaveable(session.serverUrl, session.loginName) {
        mutableStateOf(false)
    }
    var retryOperation by remember(session) {
        mutableStateOf(MediaTransferRetryOperation.Load)
    }
    val cursor = cursorHistory.last()
    val listState = rememberSaveable(
        session.serverUrl,
        session.loginName,
        sectionName,
        encodedCursorHistory,
        saver = LazyListState.Saver,
    ) {
        LazyListState()
    }

    fun clearCompletedHistory() {
        if (clearingCompleted || clearCompletedPending) return
        error = null
        notice = null
        clearCompletedPending = true
    }

    LaunchedEffect(session, clearCompletedPending) {
        if (!clearCompletedPending) return@LaunchedEffect
        clearingCompleted = true
        try {
            val removed = services.clearCompletedMediaTransferHistory(session)
            notice = if (removed == 1) {
                "Cleared 1 completed transfer from local history."
            } else {
                "Cleared $removed completed transfers from local history."
            }
            encodedCursorHistory = encodeMediaTransferCursorHistory(listOf(null))
            retryOperation = MediaTransferRetryOperation.Load
            clearCompletedPending = false
            refreshAttempt += 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            clearCompletedPending = false
            retryOperation = MediaTransferRetryOperation.ClearCompleted
            error = failure.message ?: "Could not clear completed transfer history."
        } finally {
            clearingCompleted = false
        }
    }

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
            retryOperation = MediaTransferRetryOperation.Load
            error = failure.message ?: "Could not load media transfers."
        } finally {
            loading = false
        }
    }

    val current = state
    when {
        current != null -> MediaTransferCenterScreen(
            state = current,
            listState = listState,
            loading = loading,
            busyLocalKey = null,
            clearingCompleted = clearingCompleted,
            statusMessage = error ?: notice,
            statusMessageIsError = error != null,
            onBack = onBack,
            onSelectSection = { selected ->
                if (section != selected) {
                    state = null
                    sectionName = selected.name
                    encodedCursorHistory = encodeMediaTransferCursorHistory(listOf(null))
                    notice = null
                    error = null
                    details = null
                }
            },
            onLoadNewer = {
                if (cursorHistory.size > 1) {
                    encodedCursorHistory = encodeMediaTransferCursorHistory(cursorHistory.dropLast(1))
                }
            },
            onLoadOlder = { nextCursor ->
                if (cursorHistory.last() != nextCursor) {
                    encodedCursorHistory = encodeMediaTransferCursorHistory(
                        boundedMediaTransferCursorHistory(cursorHistory + nextCursor),
                    )
                }
            },
            onRetry = {
                if (retryOperation == MediaTransferRetryOperation.ClearCompleted) {
                    clearCompletedHistory()
                } else {
                    refreshAttempt += 1
                }
            },
            onAction = { record, action ->
                if (action == MediaTransferAction.Details) details = record
            },
            onClearCompleted = ::clearCompletedHistory,
            visibleActions = { PRODUCTION_MEDIA_TRANSFER_ACTIONS },
        )
        else -> Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
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

private enum class MediaTransferRetryOperation {
    Load,
    ClearCompleted,
}
