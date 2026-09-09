package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.time.Instant

/** Describes the observed queue, not an unverified successful synchronization. */
internal enum class FileSyncObservedState(val label: String) {
    Attention("Needs review"),
    Syncing("Syncing"),
    Paused("Paused"),
    Offline("Waiting for network"),
    Skipped("Items skipped"),
    Queued("Queued"),
    Unchecked("Not checked yet"),
    Idle("No pending work"),
}

internal fun FileSyncPairSummary.observedSyncState(): FileSyncObservedState = when {
    conflictCount > 0 || failedCount > 0 -> FileSyncObservedState.Attention
    runningCount > 0 -> FileSyncObservedState.Syncing
    runState == FileSyncPairRunState.Paused -> FileSyncObservedState.Paused
    networkState == FileSyncNetworkState.WaitingForNetwork -> FileSyncObservedState.Offline
    skippedCount > 0 -> FileSyncObservedState.Skipped
    readyCount > 0 -> FileSyncObservedState.Queued
    lastScanEpochMillis == null -> FileSyncObservedState.Unchecked
    else -> FileSyncObservedState.Idle
}

internal fun FileSyncPairSummary.syncWorkSummary(): String = buildList {
    if (runningCount > 0) add("$runningCount active")
    if (readyCount > 0) add("$readyCount queued")
    if (conflictCount > 0) add("$conflictCount ${if (conflictCount == 1) "conflict" else "conflicts"}")
    if (failedCount > 0) add("$failedCount failed")
    if (skippedCount > 0) add("$skippedCount skipped")
}.joinToString(" / ").ifEmpty { observedSyncState().label }

internal fun fileSyncWorkspaceSummary(pairs: List<FileSyncPairSummary>): String {
    if (pairs.isEmpty()) return "Keep chosen folders in sync with Nextcloud"
    val attention = pairs.count { it.conflictCount > 0 || it.failedCount > 0 }
    val active = pairs.sumOf { it.runningCount }
    val queued = pairs.sumOf { it.readyCount }
    return buildList {
        if (attention > 0) add("$attention ${if (attention == 1) "sync needs" else "syncs need"} review")
        if (active > 0) add("$active active")
        if (queued > 0) add("$queued queued")
    }.joinToString(" / ").ifEmpty {
        when {
            pairs.any { it.isFileSyncPaused() } -> "Some syncs are paused"
            pairs.any { it.isFileSyncOffline() } -> "Waiting for a network connection"
            pairs.any { it.skippedCount > 0 } -> "Some items were skipped"
            pairs.any { it.lastScanEpochMillis == null } -> "Waiting for the first check"
            else -> "No pending work in the last check"
        }
    }
}

internal fun fileSyncCheckedTime(epochMillis: Long?): String {
    if (epochMillis == null) return "Not checked yet"
    if (epochMillis < 0) return "Check time unavailable"
    return Instant.fromEpochMilliseconds(epochMillis).toString().replace('T', ' ').take(16) + " UTC"
}

@Composable
internal fun FileSyncHealthBadge(pair: FileSyncPairSummary, modifier: Modifier = Modifier) {
    val state = pair.observedSyncState()
    val colors = MaterialTheme.colorScheme
    val attention = state == FileSyncObservedState.Attention
    Surface(
        modifier = modifier,
        color = if (attention) colors.errorContainer else colors.surfaceContainerHighest,
        contentColor = if (attention) colors.onErrorContainer else colors.onSurface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(state.label, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
    }
}
