package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions

/**
 * Bounded transfer-history surface.
 *
 * [state] contains at most [MEDIA_TRANSFER_CENTER_PAGE_SIZE] records. Paging replaces the current
 * window rather than appending forever, keeping composition and memory use independent of the
 * persisted history size.
 */
@Composable
internal fun MediaTransferCenterScreen(
    state: MediaTransferCenterState,
    loading: Boolean,
    busyLocalKey: String?,
    onBack: () -> Unit,
    onSelectSection: (MediaTransferSection) -> Unit,
    onLoadNewer: () -> Unit,
    onLoadOlder: (MediaBackupLedgerCursor) -> Unit,
    onAction: (MediaBackupLedgerRecord, MediaTransferAction) -> Unit,
    onClearCompleted: () -> Unit,
    visibleActions: (MediaBackupLedgerRecord) -> Set<MediaTransferAction> = {
        it.availableTransferActions()
    },
) {
    var clearHistoryConfirmation by remember {
        mutableStateOf(MediaTransferClearHistoryConfirmation.Hidden)
    }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = "Transfers",
            subtitle = "${state.summary.total} recent media uploads",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            item(key = "summary") {
                MediaTransferSummary(state.summary)
            }
            item(key = "filters") {
                MediaTransferFilters(
                    summary = state.summary,
                    selected = state.page.section,
                    onSelect = onSelectSection,
                    onClearCompleted = {
                        clearHistoryConfirmation = requestMediaTransferClearHistory(
                            completedCount = state.summary.succeeded,
                        )
                    },
                )
            }
            if (loading) {
                item(key = "loading") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (!loading && state.page.records.isEmpty()) {
                item(key = "empty") {
                    Surface(
                        color = NextcloudTheme.colors.appTile,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Text(
                            state.page.section.emptyMessage(),
                            modifier = Modifier.padding(NextcloudSpacing.XLarge),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(
                items = state.page.records,
                key = { "${it.accountId}:${it.localKey}" },
                contentType = { "media-transfer" },
            ) { record ->
                MediaTransferCard(
                    record = record,
                    busy = busyLocalKey == record.localKey,
                    visibleActions = visibleActions(record),
                    onAction = { action -> onAction(record, action) },
                )
            }
            if (state.canLoadNewer || state.page.nextCursor != null) {
                item(key = "pagination") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium, Alignment.End),
                    ) {
                        if (state.canLoadNewer) {
                            OutlinedButton(enabled = !loading, onClick = onLoadNewer) {
                                Text("Newer")
                            }
                        }
                        state.page.nextCursor?.let { cursor ->
                            OutlinedButton(
                                enabled = !loading,
                                onClick = { onLoadOlder(cursor) },
                            ) {
                                Text("Older")
                            }
                        }
                    }
                }
            }
        }
    }
    if (clearHistoryConfirmation == MediaTransferClearHistoryConfirmation.Requested) {
        AlertDialog(
            onDismissRequest = {
                clearHistoryConfirmation = MediaTransferClearHistoryConfirmation.Hidden
            },
            title = { Text(MEDIA_TRANSFER_CLEAR_HISTORY_TITLE) },
            text = { Text(MEDIA_TRANSFER_CLEAR_HISTORY_MESSAGE) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearHistoryConfirmation = confirmMediaTransferClearHistory(
                            confirmation = clearHistoryConfirmation,
                            onConfirmed = onClearCompleted,
                        )
                    },
                ) {
                    Text(
                        "Clear local history",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        clearHistoryConfirmation = MediaTransferClearHistoryConfirmation.Hidden
                    },
                ) {
                    Text("Keep history")
                }
            },
        )
    }
}

@Composable
private fun MediaTransferSummary(summary: MediaBackupLedgerSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text("Upload activity", style = MaterialTheme.typography.titleMedium)
            Text(
                "${summary.uploading} active | ${summary.pending} pending",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${summary.failed} need attention | ${summary.succeeded} completed",
                style = MaterialTheme.typography.bodySmall,
                color = if (summary.failed > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (summary.uploading > 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MediaTransferFilters(
    summary: MediaBackupLedgerSummary,
    selected: MediaTransferSection,
    onSelect: (MediaTransferSection) -> Unit,
    onClearCompleted: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            items(MediaTransferSection.entries, key = MediaTransferSection::name) { section ->
                FilterChip(
                    selected = selected == section,
                    onClick = { onSelect(section) },
                    label = { Text("${section.label()} ${summary.count(section)}") },
                )
            }
        }
        NextcloudCardOverflow(
            itemLabel = "transfer history",
            actions = listOf(
                NextcloudCardAction(
                    label = "Clear completed history",
                    destructive = true,
                    enabled = summary.succeeded > 0,
                    onClick = onClearCompleted,
                ),
            ),
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
        )
    }
}

@Composable
private fun MediaTransferCard(
    record: MediaBackupLedgerRecord,
    busy: Boolean,
    visibleActions: Set<MediaTransferAction>,
    onAction: (MediaTransferAction) -> Unit,
) {
    var menuExpanded by remember(record.localKey) { mutableStateOf(false) }
    val label = record.local?.displayName
        ?: record.receipt?.remotePath?.substringAfterLast('/')
        ?: "Media upload"
    val actions = visibleActions.map { action ->
        NextcloudCardAction(
            label = action.label(),
            destructive = action == MediaTransferAction.Cancel,
            enabled = !busy,
            onClick = { onAction(action) },
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = { onAction(MediaTransferAction.Details) },
            onShowActions = { menuExpanded = true },
            openLabel = "Open details for $label",
            actionsLabel = "Show actions for $label",
        ),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    record.receipt?.remotePath?.let { path ->
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                MediaBackupStatusIndicator(record.resolveMediaBackupStatus())
                NextcloudCardOverflow(
                    itemLabel = label,
                    actions = actions,
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                )
            }
            val metadata = buildList {
                record.local?.size?.let { add(formatTransferBytes(it)) }
                if (record.attemptCount > 0) add("Attempt ${record.attemptCount}")
            }
            if (metadata.isNotEmpty()) {
                Text(
                    metadata.joinToString(" | "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            record.failureMessage?.let { failure ->
                Text(
                    failure,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun MediaTransferSection.label(): String = when (this) {
    MediaTransferSection.Pending -> "Pending"
    MediaTransferSection.Active -> "Active"
    MediaTransferSection.Failed -> "Failed"
    MediaTransferSection.Completed -> "Completed"
}

private fun MediaTransferSection.emptyMessage(): String = when (this) {
    MediaTransferSection.Pending -> "Nothing is waiting to upload."
    MediaTransferSection.Active -> "No media is uploading right now."
    MediaTransferSection.Failed -> "No uploads need attention."
    MediaTransferSection.Completed -> "No completed uploads are in local history."
}

private fun MediaTransferAction.label(): String = when (this) {
    MediaTransferAction.Details -> "Details"
    MediaTransferAction.Retry -> "Retry upload"
    MediaTransferAction.Cancel -> "Cancel upload"
}

private fun formatTransferBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

internal enum class MediaTransferClearHistoryConfirmation {
    Hidden,
    Requested,
}

internal fun requestMediaTransferClearHistory(
    completedCount: Int,
): MediaTransferClearHistoryConfirmation {
    require(completedCount >= 0)
    return if (completedCount > 0) {
        MediaTransferClearHistoryConfirmation.Requested
    } else {
        MediaTransferClearHistoryConfirmation.Hidden
    }
}

internal inline fun confirmMediaTransferClearHistory(
    confirmation: MediaTransferClearHistoryConfirmation,
    onConfirmed: () -> Unit,
): MediaTransferClearHistoryConfirmation {
    if (confirmation == MediaTransferClearHistoryConfirmation.Requested) {
        onConfirmed()
    }
    return MediaTransferClearHistoryConfirmation.Hidden
}

internal const val MEDIA_TRANSFER_CLEAR_HISTORY_TITLE = "Clear completed transfer history?"
internal const val MEDIA_TRANSFER_CLEAR_HISTORY_MESSAGE =
    "This only removes completed entries from local transfer history. " +
        "It does not delete media from this device or Nextcloud."
