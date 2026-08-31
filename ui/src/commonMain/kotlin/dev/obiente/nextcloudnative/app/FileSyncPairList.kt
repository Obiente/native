package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Composable
internal fun FileSyncMapTable(
    pairs: List<FileSyncPairSummary>,
    selectedPairId: String?,
    busyPairIds: Set<String>,
    onSelect: (FileSyncPairSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Folders", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
                Text("Current work", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("Last checked", Modifier.width(126.dp), style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                if (pairs.isEmpty()) item {
                    Text("No syncs match this filter.", Modifier.padding(16.dp))
                }
                items(pairs, key = FileSyncPairSummary::id) { pair ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(pair) },
                        color = if (pair.id == selectedPairId) MaterialTheme.colorScheme.secondaryContainer
                            else NextcloudTheme.colors.appTile,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(pair.localDisplayName, fontWeight = FontWeight.SemiBold)
                                Text("/${pair.remoteRootPath}", style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(pair.configuration.direction.syncDirectionTitle(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                FileSyncHealthBadge(pair)
                                Text(pair.syncWorkSummary(), style = MaterialTheme.typography.bodySmall)
                                if (pair.id in busyPairIds || pair.runningCount > 0) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                            Text(fileSyncCheckedTime(pair.lastScanEpochMillis), Modifier.width(126.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
internal fun FileSyncMobilePairCard(pair: FileSyncPairSummary, onSelect: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pair.localDisplayName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Icon(NextcloudIcons.ChevronRight, contentDescription = "Open sync details")
            }
            FileSyncHealthBadge(pair)
            Text(
                "${pair.localRootPath ?: pair.localDisplayName} ${pair.configuration.direction.syncDirectionGlyph()} /${pair.remoteRootPath}",
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(pair.syncWorkSummary(), style = MaterialTheme.typography.bodySmall)
            Text("Checked: ${fileSyncCheckedTime(pair.lastScanEpochMillis)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pair.runningCount > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
