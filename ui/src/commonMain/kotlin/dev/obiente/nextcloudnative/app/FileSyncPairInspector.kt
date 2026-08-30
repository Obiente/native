package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Composable
internal fun FileSyncPairInspector(
    pair: FileSyncPairSummary?,
    busy: Boolean,
    actionsEnabled: Boolean,
    onRun: () -> Unit,
    onRemove: () -> Unit,
    onResolve: (FileSyncPairSummary, FileSyncConflictSummary, FileSyncDecisionChoice) -> Unit,
    onResolveBatch: (FileSyncPairSummary, List<FileSyncConflictSummary>, FileSyncDecisionChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTabName by rememberSaveable(pair?.id) {
        mutableStateOf(FileSyncInspectorTab.Overview.name)
    }
    val selectedTab = FileSyncInspectorTab.entries.firstOrNull { it.name == selectedTabName }
        ?: FileSyncInspectorTab.Overview
    Surface(
        modifier = modifier,
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (pair == null) {
            Text(
                "Select a sync to see its mapping, rules, and recovery actions.",
                modifier = Modifier.padding(NextcloudSpacing.Large),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pair.localDisplayName, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Sync details",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FileSyncHealthBadge(pair)
                }
                HorizontalDivider()
                PrimaryScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 0.dp) {
                    FileSyncInspectorTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTabName = tab.name },
                            text = { Text(tab.title, maxLines = 1) },
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    when (selectedTab) {
                        FileSyncInspectorTab.Overview -> FileSyncPairDetails(
                            pair = pair,
                            busy = busy,
                            actionsEnabled = actionsEnabled,
                            onRun = onRun,
                            onRemove = onRemove,
                            onResolve = { conflict, choice -> onResolve(pair, conflict, choice) },
                            onResolveBatch = { conflicts, choice -> onResolveBatch(pair, conflicts, choice) },
                            compact = false,
                        )
                        FileSyncInspectorTab.Activity -> FileSyncInspectorActivity(pair)
                        FileSyncInspectorTab.Rules -> FileSyncInspectorRules(pair)
                        FileSyncInspectorTab.Settings -> {
                            FileSyncInspectorSettings(pair)
                            TextButton(enabled = actionsEnabled, onClick = onRemove, modifier = Modifier.padding(16.dp)) {
                                Text("Remove sync", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FileSyncInspectorActivity(pair: FileSyncPairSummary) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        FileSyncDetailBlock("Transfer activity") {
            FileSyncHealthLine("Running now", pair.runningCount.toString(), problem = false)
            FileSyncHealthLine("Waiting", pair.readyCount.toString(), problem = false)
            FileSyncHealthLine("Completed", pair.completedCount.toString(), problem = false)
            FileSyncHealthLine("Failed", pair.failedCount.toString(), problem = pair.failedCount > 0)
        }
        FileSyncDetailBlock("Last checked") {
            Text(
                fileSyncCheckedTime(pair.lastScanEpochMillis),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                pair.scheduleDescription ?: "Run a check with Sync now. A check time is not a completed sync time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun FileSyncInspectorRules(pair: FileSyncPairSummary) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        FileSyncDetailBlock("Scope") {
            Text(pair.selectionSummary(), style = MaterialTheme.typography.bodyMedium)
        }
        FileSyncDetailBlock("Ignore patterns") {
            Text(pair.ignoreSummary(), style = MaterialTheme.typography.bodyMedium)
        }
        FileSyncDetailBlock("Transfer priority") {
            Text(pair.prioritySummary(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun FileSyncInspectorSettings(pair: FileSyncPairSummary) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        FileSyncDetailBlock("Device") {
            FileSyncHealthLine("Name", pair.configuration.deviceLabel, problem = false)
            FileSyncHealthLine(
                "Direction",
                pair.configuration.direction.syncDirectionTitle(),
                problem = false,
            )
        }
        FileSyncDetailBlock("Safety") {
            FileSyncHealthLine(
                "Conflicts",
                pair.configuration.conflictPolicy.syncConflictTitle(),
                problem = false,
            )
            FileSyncHealthLine(
                "Deletions",
                pair.configuration.deletionPolicy.syncDeletionTitle(),
                problem = false,
            )
        }
        FileSyncDetailBlock("Conditions") {
            FileSyncHealthLine(
                "Network",
                pair.configuration.networkPolicy.syncNetworkTitle(),
                problem = false,
            )
            FileSyncHealthLine(
                "Power",
                pair.configuration.powerPolicy.syncPowerTitle(),
                problem = false,
            )
        }
    }
}

@Composable
internal fun FileSyncPairDetails(
    pair: FileSyncPairSummary,
    busy: Boolean,
    actionsEnabled: Boolean,
    onRun: () -> Unit,
    onRemove: () -> Unit,
    onResolve: (FileSyncConflictSummary, FileSyncDecisionChoice) -> Unit,
    onResolveBatch: (List<FileSyncConflictSummary>, FileSyncDecisionChoice) -> Unit,
    compact: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(if (compact) NextcloudSpacing.Medium else NextcloudSpacing.Small),
    ) {
        FileSyncDetailBlock("Current work") {
            Text(pair.syncWorkSummary(), fontWeight = FontWeight.SemiBold)
            Text("Last checked: ${fileSyncCheckedTime(pair.lastScanEpochMillis)}",
                style = MaterialTheme.typography.bodySmall)
            pair.skippedReasons.forEach { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall)
            }
            if (pair.isFileSyncOffline()) Text("Waiting for a network connection.")
            if (pair.isFileSyncPaused()) Text("This sync is paused.")
        }
        FileSyncConflictBlock(pair, actionsEnabled, onResolve, onResolveBatch)
        Button(enabled = actionsEnabled, onClick = onRun, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Sync now")
        }
        FileSyncDetailBlock("Mapping") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileSyncLocationCard(
                    title = "This device",
                    path = pair.localRootPath ?: pair.localDisplayName,
                    icon = NextcloudIcons.FolderOpen,
                    modifier = Modifier.weight(1f),
                )
                Text(pair.configuration.direction.syncDirectionGlyph(), fontWeight = FontWeight.Bold)
                FileSyncLocationCard(
                    title = "Nextcloud",
                    path = "/${pair.remoteRootPath}",
                    icon = NextcloudIcons.Cloud,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                pair.configuration.direction.syncDirectionDescription(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

    }
}

@Composable
internal fun FileSyncLocationCard(
    title: String,
    path: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(title, style = MaterialTheme.typography.labelSmall)
            }
            Text(path, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun FileSyncDetailBlock(
    title: String,
    attention: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (attention) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
internal fun FileSyncHealthLine(label: String, value: String, problem: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (problem) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
