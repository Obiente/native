package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudDetailSheet
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

/**
 * Phone details for one sync: a tall sheet over the sync list. The inspector already names the
 * sync, so the sheet adds only Close. Selection is the workspace's saveable pair id, so the
 * sheet reopens on the same sync after rotation or process restoration, and confirmation
 * dialogs for removal or conflict choices open above it.
 */
@Composable
internal fun FileSyncPairDetailSheet(
    pair: FileSyncPairSummary,
    busyPairIds: Set<String>,
    onClose: () -> Unit,
    onRun: (FileSyncPairSummary) -> Unit,
    onRemove: (FileSyncPairSummary) -> Unit,
    onResolve: (FileSyncPairSummary, FileSyncConflictSummary, FileSyncDecisionChoice) -> Unit,
    onResolveBatch: (FileSyncPairSummary, List<FileSyncConflictSummary>, FileSyncDecisionChoice) -> Unit,
) {
    NextcloudDetailSheet(
        title = null,
        onDismiss = onClose,
        closeLabel = "Close sync details",
        bodyHeightFraction = 0.88f,
    ) {
        FileSyncPairInspector(
            pair = pair,
            busy = pair.id in busyPairIds,
            actionsEnabled = pair.id !in busyPairIds,
            onRun = { onRun(pair) },
            onRemove = { onRemove(pair) },
            onResolve = onResolve,
            onResolveBatch = onResolveBatch,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * Details for one sync in a desktop window too narrow for the side inspector. The page replaces
 * the list, and Back or "All syncs" returns to it.
 */
@Composable
internal fun FileSyncPairDetailPage(
    pair: FileSyncPairSummary,
    busyPairIds: Set<String>,
    fillAvailableHeight: Boolean,
    onClose: () -> Unit,
    onRun: (FileSyncPairSummary) -> Unit,
    onRemove: (FileSyncPairSummary) -> Unit,
    onResolve: (FileSyncPairSummary, FileSyncConflictSummary, FileSyncDecisionChoice) -> Unit,
    onResolveBatch: (FileSyncPairSummary, List<FileSyncConflictSummary>, FileSyncDecisionChoice) -> Unit,
) {
    PlatformBackHandler(enabled = true, onBack = onClose)
    Column(modifier = if (fillAvailableHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        TextButton(onClick = onClose) {
            Icon(NextcloudIcons.Back, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("All syncs")
        }
        FileSyncPairInspector(
            pair = pair,
            busy = pair.id in busyPairIds,
            actionsEnabled = pair.id !in busyPairIds,
            onRun = { onRun(pair) },
            onRemove = { onRemove(pair) },
            onResolve = onResolve,
            onResolveBatch = onResolveBatch,
            modifier = if (fillAvailableHeight) Modifier.weight(1f).fillMaxWidth()
                else Modifier.fillMaxWidth().heightIn(min = 560.dp, max = 760.dp),
        )
    }
}
