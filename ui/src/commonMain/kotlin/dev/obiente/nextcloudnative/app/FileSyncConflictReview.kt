package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
        require(
            if (conflicts.size == 1) {
                choice in availableFileSyncItemChoices(pair, conflicts.single())
            } else {
                choice in availableFileSyncBatchChoices(pair, conflicts)
            },
        )
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
    val commonChoices = availableFileSyncBatchChoices(pair, conflicts)
    var batchVisible by remember(pair.id, conflicts) { mutableStateOf(false) }
    FileSyncDetailBlock(
        "${pair.conflictCount} ${if (pair.conflictCount == 1) "conflict needs" else "conflicts need"} review",
        attention = true,
    ) {
        if (conflicts.size > 1 && commonChoices.isNotEmpty()) {
            TextButton(onClick = { batchVisible = !batchVisible }) {
                Text(if (batchVisible) "Review individually" else "Apply a choice to ${conflicts.size} shown conflicts")
            }
        }
        if (batchVisible && conflicts.size > 1 && commonChoices.isNotEmpty()) {
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
                            colors = if (choice.isDestructiveSyncDecision()) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                        ) {
                            Text("${choice.readableDecision()} all", maxLines = 1)
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
                conflict.reason.readableDecisionReason(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            FileSyncConflictSideRow("This device", conflict.local)
            FileSyncConflictSideRow("Nextcloud", conflict.remote)
            FileSyncConflictDecisionOptions(pair, conflict, actionsEnabled, onResolve)

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
private fun FileSyncConflictDecisionOptions(
    pair: FileSyncPairSummary,
    conflict: FileSyncConflictSummary,
    enabled: Boolean,
    onResolve: (FileSyncConflictSummary, FileSyncDecisionChoice) -> Unit,
) {
    val choices = availableFileSyncItemChoices(pair, conflict)
    var selected by remember(pair.id, conflict) { mutableStateOf<FileSyncDecisionChoice?>(null) }
    Text("Compare the copies, then choose what to keep. You will confirm before anything changes.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    choices.forEach { choice ->
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                selected = selected == choice,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { selected = choice },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == choice, onClick = null, enabled = enabled)
            Spacer(Modifier.width(NextcloudSpacing.Small))
            Text(choice.readableDecision(), color = if (choice.isDestructiveSyncDecision())
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
    selected?.takeIf { it in choices }?.let { choice ->
        Text(choice.decisionGuidance(), style = MaterialTheme.typography.bodySmall)
        Button(enabled = enabled, onClick = { onResolve(conflict, choice) }, modifier = Modifier.fillMaxWidth()) {
            Text("Review this choice")
        }
    }
}

internal fun FileSyncDecisionChoice.decisionGuidance(): String = when (this) {
    FileSyncDecisionChoice.UseLocal -> "Replace the Nextcloud copy with the latest device copy. Check the details before replacing."
    FileSyncDecisionChoice.UseRemote -> "Replace the device copy with the latest Nextcloud copy. Check the details before replacing."
    FileSyncDecisionChoice.KeepBoth -> "Keep both as named conflict copies. The Nextcloud version stays at the original path."
    FileSyncDecisionChoice.PropagateDeletion -> "Permanently delete the surviving copy from the other location."
    FileSyncDecisionChoice.RestoreMissing -> "Copy the surviving version back to the location where it is missing."
    FileSyncDecisionChoice.Skip -> "Leave this exact conflict unchanged. It returns for review if either side changes."
}

internal fun availableFileSyncBatchChoices(
    pair: FileSyncPairSummary,
    conflicts: List<FileSyncConflictSummary> = pair.conflicts,
): List<FileSyncDecisionChoice> {
    val guardedDirectoryDeletion = conflicts.any { it.isGuardedDirectoryDeletion(pair) }
    return FileSyncDecisionChoice.entries.filter { choice ->
        conflicts.all { conflict -> choice in conflict.choices } &&
            !(choice == FileSyncDecisionChoice.PropagateDeletion && guardedDirectoryDeletion)
    }
}

internal fun availableFileSyncItemChoices(
    pair: FileSyncPairSummary,
    conflict: FileSyncConflictSummary,
): List<FileSyncDecisionChoice> = conflict.choices
    .filterNot { choice ->
        choice == FileSyncDecisionChoice.PropagateDeletion && conflict.isGuardedDirectoryDeletion(pair)
    }
    .sortedBy(FileSyncDecisionChoice::ordinal)

private fun FileSyncConflictSummary.isGuardedDirectoryDeletion(pair: FileSyncPairSummary): Boolean {
    if (pair.configuration.selectedPaths.isEmpty() && pair.configuration.ignoredPatterns.isEmpty()) return false
    return when (reason) {
    FileSyncDecisionReason.LocalDeletion -> remote?.kind == SyncEntryKind.Directory
    FileSyncDecisionReason.RemoteDeletion -> local?.kind == SyncEntryKind.Directory
    else -> false
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

internal fun FileSyncDecisionReason.readableDecisionReason(): String = when (this) {
    FileSyncDecisionReason.FirstSyncCollision -> "Both folders already contain this path."
    FileSyncDecisionReason.SimultaneousEdit -> "Both copies changed since the last completed sync."
    FileSyncDecisionReason.LocalDeletion -> "The device copy was deleted."
    FileSyncDecisionReason.RemoteDeletion -> "The Nextcloud copy was deleted."
    FileSyncDecisionReason.TypeChanged -> "One side is a file and the other is a folder."
}

internal fun FileSyncDecisionChoice.readableDecision(): String = when (this) {
    FileSyncDecisionChoice.UseLocal -> "Use device copy"
    FileSyncDecisionChoice.UseRemote -> "Use Nextcloud copy"
    FileSyncDecisionChoice.KeepBoth -> "Keep both copies"
    FileSyncDecisionChoice.PropagateDeletion -> "Delete other copy"
    FileSyncDecisionChoice.RestoreMissing -> "Restore missing copy"
    FileSyncDecisionChoice.Skip -> "Skip this version"
}

internal fun FileSyncDecisionChoice.confirmationText(
    path: String,
    reason: FileSyncDecisionReason,
): String = when (this) {
    FileSyncDecisionChoice.UseLocal ->
        "Use the latest device version of $path. The current Nextcloud version will be replaced " +
            "only if it has not changed since this conflict was shown."
    FileSyncDecisionChoice.UseRemote ->
        "Use the latest Nextcloud version of $path. The current device version will be replaced " +
            "only if it has not changed since this conflict was shown."
    FileSyncDecisionChoice.KeepBoth ->
        "Preserve both versions of $path as named conflict copies and keep the Nextcloud version " +
            "at the original path. Review again if either side changes before this starts."
    FileSyncDecisionChoice.PropagateDeletion ->
        "Apply the deletion for $path to the other location. This permanently removes the other copy " +
            "only if its observed revision is unchanged."
    FileSyncDecisionChoice.RestoreMissing ->
        "Restore the missing copy of $path from the latest surviving version, only if the missing " +
            "side is still empty."
    FileSyncDecisionChoice.Skip ->
        "Skip $path for this exact observed conflict (${reason.readableDecisionReason()}). " +
            "It will be reconsidered if either side changes."
}

internal fun FileSyncDecisionChoice.isDestructiveSyncDecision(): Boolean =
    this == FileSyncDecisionChoice.PropagateDeletion

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
