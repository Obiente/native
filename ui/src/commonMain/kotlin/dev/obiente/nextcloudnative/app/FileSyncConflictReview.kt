package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlin.time.Instant

internal data class PendingFileSyncDecision(
    val pair: FileSyncPairSummary,
    val conflicts: List<FileSyncConflictSummary>,
    val choice: FileSyncDecisionChoice,
) {
    init {
        require(conflicts.isNotEmpty())
        require(conflicts.map(FileSyncConflictSummary::workId).distinct().size == conflicts.size)
        require(conflicts.all { choice in it.choices })
    }
}

@Composable
internal fun FileSyncConflictBlock(
    pair: FileSyncPairSummary,
    actionsEnabled: Boolean,
    onResolve: (FileSyncConflictSummary, FileSyncDecisionChoice) -> Unit,
    onResolveBatch: (List<FileSyncConflictSummary>, FileSyncDecisionChoice) -> Unit,
) {
    val conflicts = pair.conflicts
    if (conflicts.isEmpty()) return
    val commonChoices = FileSyncDecisionChoice.entries.filter { choice ->
        conflicts.all { conflict -> choice in conflict.choices }
    }
    FileSyncDetailBlock(
        "${pair.conflictCount} ${if (pair.conflictCount == 1) "conflict" else "conflicts"} need review",
        attention = true,
    ) {
        if (conflicts.size > 1 && commonChoices.isNotEmpty()) {
            Text(
                "Apply one choice to the ${conflicts.size} conflicts shown below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            commonChoices.chunked(2).forEach { choices ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    choices.forEach { choice ->
                        Button(
                            enabled = actionsEnabled,
                            onClick = { onResolveBatch(conflicts, choice) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("${choice.syncDecisionTitle()} all", maxLines = 1)
                        }
                    }
                    if (choices.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            HorizontalDivider()
        }
        conflicts.forEachIndexed { index, conflict ->
            if (index > 0) HorizontalDivider()
            Text(
                conflict.relativePath,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                conflict.reason.syncDecisionReasonTitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            FileSyncConflictSideRow("This device", conflict.local)
            FileSyncConflictSideRow("Nextcloud", conflict.remote)
            Text(
                "The selected source can use its latest version only while the other side stays unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                conflict.choices.sortedBy(FileSyncDecisionChoice::ordinal).chunked(2).forEach { choices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        choices.forEach { choice ->
                            OutlinedButton(
                                enabled = actionsEnabled,
                                onClick = { onResolve(conflict, choice) },
                                modifier = Modifier.weight(1f),
                            ) { Text(choice.syncDecisionTitle(), maxLines = 1) }
                        }
                        if (choices.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (pair.conflictCount > conflicts.size) {
            Text(
                "Showing ${conflicts.size} of ${pair.conflictCount}. Resolve this batch to load the next conflicts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileSyncConflictSideRow(
    label: String,
    side: FileSyncConflictSideSummary?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.width(88.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            side?.syncConflictSideDescription() ?: "Missing",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun FileSyncDecisionReason.syncDecisionReasonTitle(): String = when (this) {
    FileSyncDecisionReason.FirstSyncCollision -> "Both folders already contain this path."
    FileSyncDecisionReason.SimultaneousEdit -> "Both copies changed since the last completed sync."
    FileSyncDecisionReason.LocalDeletion -> "The device copy was deleted."
    FileSyncDecisionReason.RemoteDeletion -> "The Nextcloud copy was deleted."
    FileSyncDecisionReason.TypeChanged -> "One side is a file and the other is a folder."
}

private fun FileSyncDecisionChoice.syncDecisionTitle(): String = when (this) {
    FileSyncDecisionChoice.UseLocal -> "Use device copy"
    FileSyncDecisionChoice.UseRemote -> "Use Nextcloud copy"
    FileSyncDecisionChoice.KeepBoth -> "Keep both copies"
    FileSyncDecisionChoice.PropagateDeletion -> "Delete other copy"
    FileSyncDecisionChoice.RestoreMissing -> "Restore missing copy"
    FileSyncDecisionChoice.Skip -> "Skip this version"
}

private fun FileSyncConflictSideSummary.syncConflictSideDescription(): String = buildString {
    append(if (kind == SyncEntryKind.File) "File" else "Folder")
    sizeBytes?.let { append(" | ").append(it.fileSyncBytes()) }
    modifiedEpochMillis?.let { append(" | Modified ").append(it.fileSyncModifiedTime()) }
}

private fun Long.fileSyncModifiedTime(): String = runCatching {
    Instant.fromEpochMilliseconds(this).toString().replace('T', ' ').take(16) + " UTC"
}.getOrDefault("Unknown time")

internal fun Long.fileSyncBytes(): String = when {
    this >= 1024L * 1024L * 1024L -> "${this / (1024L * 1024L * 1024L)} GB"
    this >= 1024L * 1024L -> "${this / (1024L * 1024L)} MB"
    this >= 1024L -> "${this / 1024L} KB"
    else -> "$this B"
}
