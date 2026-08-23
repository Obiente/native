package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

internal enum class FileSyncListFilter(val title: String) {
    All("All"),
    Syncing("Syncing"),
    Attention("Attention"),
    Paused("Paused"),
    Offline("Offline"),
}

internal enum class FileSyncSetupStep(val title: String) {
    Locations("Locations"),
    Direction("Direction"),
    Rules("What syncs"),
    Review("Review"),
}

private enum class FileSyncRulePreset(val title: String, val supportingText: String) {
    Everything("Everything", "Sync the whole folder without priority groups."),
    PhotoRawFirst("Photos and RAW first", "Ignore temporary previews and transfer RAW before JPEG."),
    ChooseFolders("Choose folders", "Sync only the folders and files you select."),
}

private enum class FileSyncInspectorTab(val title: String) {
    Overview("Overview"),
    Activity("Activity"),
    Rules("Rules"),
    Settings("Settings"),
}

@Composable
internal fun FileSyncWorkspace(
    snapshot: FileSyncCenterSnapshot?,
    loading: Boolean,
    busyPairId: String?,
    busyPairIds: Set<String> = busyPairId?.let(::setOf).orEmpty(),
    onAdd: () -> Unit,
    onRun: (FileSyncPairSummary) -> Unit,
    onRemove: (FileSyncPairSummary) -> Unit,
    onResolve: (FileSyncPairSummary, FileSyncConflictSummary, FileSyncDecisionChoice) -> Unit,
    onResolveBatch: (FileSyncPairSummary, List<FileSyncConflictSummary>, FileSyncDecisionChoice) -> Unit =
        { _, _, _ -> },
    initialSelectedPairId: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    fillAvailableHeight: Boolean = false,
) {
    val pairs = snapshot?.pairs.orEmpty()
    var selectedPairId by rememberSaveable(initialSelectedPairId) { mutableStateOf(initialSelectedPairId) }
    var filter by rememberSaveable { mutableStateOf(FileSyncListFilter.All) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(pairs.map(FileSyncPairSummary::id)) {
        if (selectedPairId !in pairs.map(FileSyncPairSummary::id)) {
            selectedPairId = null
        }
    }
    val visiblePairs = remember(pairs, filter, searchQuery) {
        filterFileSyncPairs(pairs, filter, searchQuery)
    }
    val selectedPair = inspectedFileSyncPair(visiblePairs, selectedPairId)

    BoxWithConstraints(modifier = modifier) {
        val desktop = maxWidth >= 940.dp
        Column(
            modifier = if (fillAvailableHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            FileSyncWorkspaceHeader(
                pairs = pairs,
                loading = loading,
                actionsEnabled = true,
                onAdd = onAdd,
                compact = !desktop,
            )
            snapshot?.limitation?.let { limitation ->
                FileSyncNotice(limitation)
            }
            if (loading && snapshot == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!loading && pairs.isEmpty()) {
                FileSyncEmptyState(onAdd = onAdd, fillAvailableHeight = fillAvailableHeight)
            } else if (pairs.isNotEmpty()) {
                FileSyncFilters(
                    selected = filter,
                    pairs = pairs,
                    searchQuery = searchQuery,
                    onSelected = { filter = it },
                    onSearchQueryChanged = { searchQuery = it },
                )
                if (desktop) {
                    val inspectedPair = selectedPair
                    Row(
                        modifier = if (fillAvailableHeight) {
                            Modifier.weight(1f).fillMaxWidth()
                        } else {
                            Modifier.fillMaxWidth()
                        },
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        FileSyncMapTable(
                            pairs = visiblePairs,
                            selectedPairId = inspectedPair?.id,
                            busyPairIds = busyPairIds,
                            actionsEnabled = true,
                            onSelect = { selectedPairId = it.id },
                            onRun = onRun,
                            modifier = if (fillAvailableHeight) {
                                Modifier.weight(1.9f).fillMaxHeight()
                            } else {
                                Modifier.weight(1.9f).heightIn(min = 520.dp, max = 760.dp)
                            },
                        )
                        FileSyncPairInspector(
                            pair = inspectedPair,
                            busy = inspectedPair?.id in busyPairIds,
                            actionsEnabled = inspectedPair?.id !in busyPairIds,
                            onRun = { inspectedPair?.let(onRun) },
                            onRemove = { inspectedPair?.let(onRemove) },
                            onResolve = onResolve,
                            onResolveBatch = onResolveBatch,
                            modifier = if (fillAvailableHeight) {
                                Modifier.widthIn(min = 308.dp, max = 372.dp).fillMaxHeight()
                            } else {
                                Modifier.widthIn(min = 308.dp, max = 372.dp).heightIn(min = 520.dp, max = 760.dp)
                            },
                        )
                    }
                    FileSyncDesktopStatusBar(pair = inspectedPair)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        visiblePairs.forEach { pair ->
                            FileSyncMobilePairCard(
                                pair = pair,
                                expanded = pair.id == selectedPairId,
                                busy = pair.id in busyPairIds,
                                actionsEnabled = pair.id !in busyPairIds,
                                onSelect = {
                                    selectedPairId = if (selectedPairId == pair.id) null else pair.id
                                },
                                onRun = { onRun(pair) },
                                onRemove = { onRemove(pair) },
                                onResolve = { conflict, choice -> onResolve(pair, conflict, choice) },
                                onResolveBatch = { conflicts, choice -> onResolveBatch(pair, conflicts, choice) },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun inspectedFileSyncPair(
    visiblePairs: List<FileSyncPairSummary>,
    selectedPairId: String?,
): FileSyncPairSummary? = visiblePairs.firstOrNull { pair -> pair.id == selectedPairId }
    ?: visiblePairs.firstOrNull()

@Composable
private fun FileSyncWorkspaceHeader(
    pairs: List<FileSyncPairSummary>,
    loading: Boolean,
    actionsEnabled: Boolean,
    onAdd: () -> Unit,
    compact: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (!compact) {
                Text("Folder sync", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                when {
                    loading -> "Checking sync health..."
                    pairs.any { it.failedCount > 0 || it.conflicts.isNotEmpty() } ->
                        "${pairs.count { it.failedCount > 0 || it.conflicts.isNotEmpty() }} syncs need attention"
                    pairs.any { it.runningCount > 0 } -> "Syncing changes safely"
                    pairs.isNotEmpty() -> "All folder mappings are ready"
                    else -> "Keep chosen folders in sync with Nextcloud"
                },
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(enabled = actionsEnabled, onClick = onAdd) {
            Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(NextcloudSpacing.Small))
            Text("Add sync")
        }
    }
}

@Composable
private fun FileSyncFilters(
    selected: FileSyncListFilter,
    pairs: List<FileSyncPairSummary>,
    searchQuery: String,
    onSelected: (FileSyncListFilter) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
) {
    val counts = mapOf(
        FileSyncListFilter.All to pairs.size,
        FileSyncListFilter.Syncing to pairs.count { it.runningCount > 0 },
        FileSyncListFilter.Attention to pairs.count { it.failedCount > 0 || it.conflicts.isNotEmpty() },
        FileSyncListFilter.Paused to pairs.count(FileSyncPairSummary::isFileSyncPaused),
        FileSyncListFilter.Offline to pairs.count(FileSyncPairSummary::isFileSyncOffline),
    )
    @Composable
    fun filterChip(option: FileSyncListFilter, modifier: Modifier = Modifier, fill: Boolean = false) {
        FilterChip(
            selected = selected == option,
            onClick = { onSelected(option) },
            label = { Text("${option.title} ${counts.getValue(option)}") },
            modifier = if (fill) modifier.fillMaxWidth() else modifier,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 680.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChanged,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    singleLine = true,
                    leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                    placeholder = { Text("Search syncs") },
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    items(FileSyncListFilter.entries, key = FileSyncListFilter::name) { option ->
                        filterChip(option)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileSyncListFilter.entries.forEach { option -> filterChip(option) }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChanged,
                    modifier = Modifier.widthIn(min = 220.dp, max = 320.dp).height(52.dp),
                    singleLine = true,
                    leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                    placeholder = { Text("Search syncs") },
                )
            }
        }
    }
}

@Composable
private fun FileSyncMapTable(
    pairs: List<FileSyncPairSummary>,
    selectedPairId: String?,
    busyPairIds: Set<String>,
    actionsEnabled: Boolean,
    onSelect: (FileSyncPairSummary) -> Unit,
    onRun: (FileSyncPairSummary) -> Unit,
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FileSyncTableHeader("Sync pair", Modifier.weight(1.2f))
                FileSyncTableHeader("Local location", Modifier.weight(1.1f))
                FileSyncTableHeader("", Modifier.width(32.dp))
                FileSyncTableHeader("Nextcloud location", Modifier.weight(1.05f))
                FileSyncTableHeader("Status", Modifier.width(82.dp))
                FileSyncTableHeader("Queued", Modifier.width(62.dp))
                FileSyncTableHeader("Last sync", Modifier.width(62.dp))
                Spacer(Modifier.width(44.dp))
            }
            HorizontalDivider()
            if (pairs.isEmpty()) {
                Text(
                    "No syncs match this filter.",
                    modifier = Modifier.padding(NextcloudSpacing.Large),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            pairs.forEachIndexed { index, pair ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(pair) },
                    color = if (selectedPairId == pair.id) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f)
                    } else {
                        NextcloudTheme.colors.appTile
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text(
                                pair.localDisplayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                pair.configuration.direction.syncDirectionTitle(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            pair.localRootPath ?: "This device",
                            modifier = Modifier.weight(1.1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                pair.configuration.direction.fileSyncDirectionIcon(),
                                contentDescription = pair.configuration.direction.syncDirectionTitle(),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "/${pair.remoteRootPath}",
                            modifier = Modifier.weight(1.05f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Box(modifier = Modifier.width(82.dp), contentAlignment = Alignment.CenterStart) {
                            FileSyncHealthIndicator(pair)
                        }
                        Text(
                            pair.queuedCompactLabel(),
                            modifier = Modifier.width(62.dp),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                        Text(
                            pair.lastSyncCompactLabel(),
                            modifier = Modifier.width(62.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                        if (pair.id in busyPairIds) {
                            Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            IconButton(
                                enabled = actionsEnabled,
                                onClick = { onRun(pair) },
                                modifier = Modifier.width(44.dp),
                            ) {
                                Icon(
                                    if (pair.runningCount > 0) NextcloudIcons.Refresh else NextcloudIcons.Play,
                                    contentDescription = "Sync ${pair.localDisplayName} now",
                                )
                            }
                        }
                    }
                }
                if (index != pairs.lastIndex) HorizontalDivider()
            }
        }
    }
}

internal fun filterFileSyncPairs(
    pairs: List<FileSyncPairSummary>,
    filter: FileSyncListFilter,
    searchQuery: String,
): List<FileSyncPairSummary> {
    val normalizedQuery = searchQuery.trim().lowercase()
    return pairs.filter { pair ->
        val matchesFilter = when (filter) {
            FileSyncListFilter.All -> true
            FileSyncListFilter.Syncing -> pair.runningCount > 0
            FileSyncListFilter.Attention -> pair.failedCount > 0 || pair.conflicts.isNotEmpty()
            FileSyncListFilter.Paused -> pair.isFileSyncPaused()
            FileSyncListFilter.Offline -> pair.isFileSyncOffline()
        }
        val matchesSearch = normalizedQuery.isEmpty() || listOf(
            pair.localDisplayName,
            pair.localRootPath.orEmpty(),
            pair.remoteRootPath,
            pair.configuration.direction.syncDirectionTitle(),
        ).any { value -> normalizedQuery in value.lowercase() }
        matchesFilter && matchesSearch
    }
}

private fun FileSyncPairSummary.isFileSyncPaused(): Boolean =
    runState == FileSyncPairRunState.Paused

private fun FileSyncPairSummary.isFileSyncOffline(): Boolean =
    networkState == FileSyncNetworkState.WaitingForNetwork

private fun FileSyncDirection.fileSyncDirectionIcon() = when (this) {
    FileSyncDirection.Bidirectional -> NextcloudIcons.Refresh
    FileSyncDirection.UploadOnly -> NextcloudIcons.Cloud
    FileSyncDirection.DownloadOnly -> NextcloudIcons.FolderOpen
}

private fun FileSyncPairSummary.queuedCompactLabel(): String = when {
    runningCount > 0 && readyCount > 0 -> "$runningCount active\n$readyCount ready"
    runningCount > 0 -> "$runningCount active"
    readyCount > 0 -> "$readyCount ready"
    else -> "-"
}

private fun FileSyncPairSummary.lastSyncCompactLabel(): String =
    if (lastScanEpochMillis == null) "Not yet" else "Recently"

@Composable
private fun FileSyncTableHeader(label: String, modifier: Modifier) {
    Text(
        label,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FileSyncMobilePairCard(
    pair: FileSyncPairSummary,
    expanded: Boolean,
    busy: Boolean,
    actionsEnabled: Boolean,
    onSelect: () -> Unit,
    onRun: () -> Unit,
    onRemove: () -> Unit,
    onResolve: (FileSyncConflictSummary, FileSyncDecisionChoice) -> Unit,
    onResolveBatch: (List<FileSyncConflictSummary>, FileSyncDecisionChoice) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        border = BorderStroke(
            1.dp,
            if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)
                    .padding(NextcloudSpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(NextcloudIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(pair.localDisplayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        "This device ${pair.configuration.direction.syncDirectionGlyph()} /${pair.remoteRootPath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FileSyncHealthBadge(pair)
                Icon(
                    NextcloudIcons.ExpandMore,
                    contentDescription = if (expanded) "Collapse sync details" else "Expand sync details",
                )
            }
            if (pair.runningCount > 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
            }
            if (expanded) {
                HorizontalDivider()
                FileSyncPairDetails(
                    pair = pair,
                    busy = busy,
                    actionsEnabled = actionsEnabled,
                    onRun = onRun,
                    onRemove = onRemove,
                    onResolve = onResolve,
                    onResolveBatch = onResolveBatch,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun FileSyncPairInspector(
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
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
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
                        FileSyncInspectorTab.Settings -> FileSyncInspectorSettings(pair)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileSyncInspectorActivity(pair: FileSyncPairSummary) {
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
        FileSyncDetailBlock("Last sync") {
            Text(
                pair.lastSyncCompactLabel(),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                pair.scheduleDescription ?: "Runs when you choose Sync now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileSyncInspectorRules(pair: FileSyncPairSummary) {
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
private fun FileSyncInspectorSettings(pair: FileSyncPairSummary) {
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
private fun FileSyncPairDetails(
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
        if (compact) {
            FileSyncConflictBlock(pair, actionsEnabled, onResolve, onResolveBatch)
            FileSyncPrimaryActions(
                compact = true,
                busy = busy,
                actionsEnabled = actionsEnabled,
                onRun = onRun,
                onRemove = onRemove,
            )
        } else {
            FileSyncPrimaryActions(
                compact = false,
                busy = busy,
                actionsEnabled = actionsEnabled,
                onRun = onRun,
                onRemove = onRemove,
            )
            FileSyncConflictBlock(pair, actionsEnabled, onResolve, onResolveBatch)
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
        FileSyncDetailBlock("Health") {
            FileSyncHealthLine("Queued", pair.queuedLabel(), problem = false)
            FileSyncHealthLine("Completed", pair.completedCount.toString(), problem = false)
            FileSyncHealthLine(
                "Needs attention",
                "${pair.conflictCount} ${if (pair.conflictCount == 1) "conflict" else "conflicts"}, " +
                    "${pair.failedCount} failed",
                problem = pair.conflicts.isNotEmpty() || pair.failedCount > 0,
            )
            if (pair.skippedCount > 0) {
                FileSyncHealthLine(
                    "Paused",
                    "${pair.skippedCount} ${if (pair.skippedCount == 1) "item" else "items"}",
                    problem = true,
                )
                pair.skippedReasons.forEach { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            pair.scheduleDescription?.let { FileSyncHealthLine("Schedule", it, problem = false) }
        }
        FileSyncDetailBlock("Rules") {
            Text(pair.selectionSummary(), style = MaterialTheme.typography.bodySmall)
            Text(pair.ignoreSummary(), style = MaterialTheme.typography.bodySmall)
            Text(pair.prioritySummary(), style = MaterialTheme.typography.bodySmall)
            Text(
                "${pair.configuration.networkPolicy.syncNetworkTitle()} - " +
                    pair.configuration.powerPolicy.syncPowerTitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileSyncPrimaryActions(
    compact: Boolean,
    busy: Boolean,
    actionsEnabled: Boolean,
    onRun: () -> Unit,
    onRemove: () -> Unit,
) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            OutlinedButton(
                enabled = actionsEnabled,
                onClick = onRemove,
                modifier = Modifier.weight(1f),
            ) { Text(if (compact) "Remove" else "Remove sync") }
            Button(
                enabled = actionsEnabled,
                onClick = onRun,
                modifier = Modifier.weight(1f),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Sync now")
            }
        }
}

@Composable
private fun FileSyncLocationCard(
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
            Text(path, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
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
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f)
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
private fun FileSyncHealthLine(label: String, value: String, problem: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (problem) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun FileSyncHealthIndicator(pair: FileSyncPairSummary) {
    val attention = pair.failedCount > 0 || pair.conflicts.isNotEmpty()
    val paused = pair.isFileSyncPaused()
    val offline = pair.isFileSyncOffline()
    val label = when {
        attention -> "Attention"
        pair.runningCount > 0 -> "Syncing"
        paused -> "Paused"
        offline -> "Offline"
        else -> "Up to date"
    }
    val color = when {
        attention -> MaterialTheme.colorScheme.error
        pair.runningCount > 0 -> MaterialTheme.colorScheme.primary
        paused -> MaterialTheme.colorScheme.tertiary
        offline -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.secondary
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun FileSyncHealthBadge(pair: FileSyncPairSummary, modifier: Modifier = Modifier) {
    val attention = pair.failedCount > 0 || pair.conflicts.isNotEmpty()
    val paused = pair.isFileSyncPaused()
    val label = when {
        attention -> "Attention"
        pair.runningCount > 0 -> "Syncing"
        pair.readyCount > 0 -> "Ready"
        paused -> "Paused"
        pair.skippedCount > 0 -> "Skipped"
        else -> "Up to date"
    }
    Surface(
        modifier = modifier,
        color = when {
            attention -> MaterialTheme.colorScheme.errorContainer
            pair.runningCount > 0 -> MaterialTheme.colorScheme.primaryContainer
            paused -> MaterialTheme.colorScheme.tertiaryContainer
            pair.skippedCount > 0 -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = when {
                attention -> MaterialTheme.colorScheme.onErrorContainer
                pair.runningCount > 0 -> MaterialTheme.colorScheme.onPrimaryContainer
                paused -> MaterialTheme.colorScheme.onTertiaryContainer
                pair.skippedCount > 0 -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun FileSyncNotice(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Row(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NextcloudIcons.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FileSyncEmptyState(onAdd: () -> Unit, fillAvailableHeight: Boolean) {
    Surface(
        modifier = if (fillAvailableHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(NextcloudIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(40.dp))
            Text("Keep a folder available everywhere", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose a folder on this device and where it belongs in Nextcloud. Nothing is deleted while setup is incomplete.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAdd) { Text("Add your first sync") }
        }
    }
}

@Composable
private fun FileSyncDesktopStatusBar(pair: FileSyncPairSummary?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Small),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileSyncDesktopStatusItem("Sync engine", if (pair?.runningCount.orZero() > 0) "Running" else "Ready")
            FileSyncDesktopStatusItem(
                "Network",
                when (pair?.networkState) {
                    FileSyncNetworkState.Available -> "Connected"
                    FileSyncNetworkState.WaitingForNetwork -> "Offline"
                    FileSyncNetworkState.Unknown, null -> "Unknown"
                },
            )
            FileSyncDesktopStatusItem("Nextcloud", "Connected")
            Spacer(Modifier.weight(1f))
            pair?.let {
                Text(
                    "${it.completedCount} files indexed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileSyncDesktopStatusItem(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = NextcloudTheme.colors.success) {}
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0

@Composable
internal fun GuidedAddFolderSyncDialog(
    localRoot: FileSyncLocalRoot,
    mediaSuggestion: MediaSyncFolderSuggestion?,
    remotePath: String,
    configuration: FileSyncConfiguration,
    mediaPreview: MediaSyncFolderPreview?,
    mediaPreviewLoading: Boolean,
    mediaPreviewError: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChooseDestination: () -> Unit,
    onChooseSelectedPaths: () -> Unit = {},
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    onAdd: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Medium)) {
            val compact = maxWidth < 720.dp
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FileSyncSetupSurface(
                localRoot = localRoot,
                mediaSuggestion = mediaSuggestion,
                remotePath = remotePath,
                configuration = configuration,
                mediaPreview = mediaPreview,
                mediaPreviewLoading = mediaPreviewLoading,
                mediaPreviewError = mediaPreviewError,
                busy = busy,
                onDismiss = onDismiss,
                onChooseDestination = onChooseDestination,
                onChooseSelectedPaths = onChooseSelectedPaths,
                onConfigurationChanged = onConfigurationChanged,
                onAdd = onAdd,
                    modifier = if (compact) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.fillMaxWidth().widthIn(max = 920.dp).heightIn(min = 620.dp, max = 760.dp)
                    },
                )
            }
        }
    }
}

@Composable
internal fun FileSyncSetupSurface(
    localRoot: FileSyncLocalRoot,
    mediaSuggestion: MediaSyncFolderSuggestion?,
    remotePath: String,
    configuration: FileSyncConfiguration,
    mediaPreview: MediaSyncFolderPreview?,
    mediaPreviewLoading: Boolean,
    mediaPreviewError: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChooseDestination: () -> Unit,
    onChooseSelectedPaths: () -> Unit = {},
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    initialStep: FileSyncSetupStep = FileSyncSetupStep.Locations,
    initialAdvancedSettingsVisible: Boolean = false,
    syntheticScopeSummary: String? = null,
) {
    var stepName by rememberSaveable(localRoot.localRootId, initialStep.name) {
        mutableStateOf(initialStep.name)
    }
    val step = FileSyncSetupStep.entries.firstOrNull { it.name == stepName } ?: initialStep
    val setupStateHolder = rememberSaveableStateHolder()
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(NextcloudRadii.Large),
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val desktop = maxWidth >= 720.dp
            Column(Modifier.fillMaxSize()) {
                FileSyncSetupHeader(step = step, onDismiss = onDismiss, enabled = !busy)
                HorizontalDivider()
                if (desktop) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        FileSyncStepRail(
                            current = step,
                            onSelect = { stepName = it.name },
                            modifier = Modifier.width(210.dp).fillMaxHeight(),
                        )
                        HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())
                        setupStateHolder.SaveableStateProvider(step.name) {
                            FileSyncStepContent(
                                step = step,
                                localRoot = localRoot,
                                mediaSuggestion = mediaSuggestion,
                                remotePath = remotePath,
                                configuration = configuration,
                                mediaPreview = mediaPreview,
                                mediaPreviewLoading = mediaPreviewLoading,
                                mediaPreviewError = mediaPreviewError,
                                onChooseDestination = onChooseDestination,
                                onChooseSelectedPaths = onChooseSelectedPaths,
                                onConfigurationChanged = onConfigurationChanged,
                                initialAdvancedSettingsVisible = initialAdvancedSettingsVisible,
                                syntheticScopeSummary = syntheticScopeSummary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    FileSyncStepProgress(current = step, onSelect = { stepName = it.name })
                    HorizontalDivider()
                    setupStateHolder.SaveableStateProvider(step.name) {
                        FileSyncStepContent(
                            step = step,
                            localRoot = localRoot,
                            mediaSuggestion = mediaSuggestion,
                            remotePath = remotePath,
                            configuration = configuration,
                            mediaPreview = mediaPreview,
                            mediaPreviewLoading = mediaPreviewLoading,
                            mediaPreviewError = mediaPreviewError,
                            onChooseDestination = onChooseDestination,
                            onChooseSelectedPaths = onChooseSelectedPaths,
                            onConfigurationChanged = onConfigurationChanged,
                            initialAdvancedSettingsVisible = initialAdvancedSettingsVisible,
                            syntheticScopeSummary = syntheticScopeSummary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                HorizontalDivider()
                FileSyncSetupFooter(
                    step = step,
                    busy = busy,
                    configuration = configuration,
                    mediaReady = isMediaFolderPreviewReady(mediaSuggestion, mediaPreview),
                    onBack = { stepName = FileSyncSetupStep.entries[step.ordinal - 1].name },
                    onNext = { stepName = FileSyncSetupStep.entries[step.ordinal + 1].name },
                    onAdd = onAdd,
                )
            }
        }
    }
}

@Composable
private fun FileSyncSetupHeader(step: FileSyncSetupStep, onDismiss: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Add sync", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Step ${step.ordinal + 1} of ${FileSyncSetupStep.entries.size}: ${step.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(enabled = enabled, onClick = onDismiss) { Text("Close") }
    }
}

@Composable
private fun FileSyncStepRail(
    current: FileSyncSetupStep,
    onSelect: (FileSyncSetupStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        FileSyncSetupStep.entries.forEach { step ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(step) },
                color = if (step == current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                Row(
                    modifier = Modifier.padding(NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileSyncStepNumber(step = step, selected = step == current)
                    Text(step.title, fontWeight = if (step == current) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(NextcloudSpacing.Medium))
        Text(
            "Review each option before adding the sync. Setup never deletes existing files.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileSyncStepProgress(current: FileSyncSetupStep, onSelect: (FileSyncSetupStep) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FileSyncSetupStep.entries.forEach { step ->
            Column(
                modifier = Modifier.clickable { onSelect(step) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FileSyncStepNumber(step, selected = step == current)
                Text(step.title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FileSyncStepNumber(step: FileSyncSetupStep, selected: Boolean) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(999.dp),
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Text(
                (step.ordinal + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileSyncStepContent(
    step: FileSyncSetupStep,
    localRoot: FileSyncLocalRoot,
    mediaSuggestion: MediaSyncFolderSuggestion?,
    remotePath: String,
    configuration: FileSyncConfiguration,
    mediaPreview: MediaSyncFolderPreview?,
    mediaPreviewLoading: Boolean,
    mediaPreviewError: String?,
    onChooseDestination: () -> Unit,
    onChooseSelectedPaths: () -> Unit,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    initialAdvancedSettingsVisible: Boolean,
    syntheticScopeSummary: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        when (step) {
            FileSyncSetupStep.Locations -> FileSyncLocationsStep(
                localRoot = localRoot,
                mediaSuggestion = mediaSuggestion,
                remotePath = remotePath,
                mediaPreview = mediaPreview,
                mediaPreviewLoading = mediaPreviewLoading,
                mediaPreviewError = mediaPreviewError,
                onChooseDestination = onChooseDestination,
            )
            FileSyncSetupStep.Direction -> FileSyncDirectionStep(
                mediaSuggestion = mediaSuggestion,
                configuration = configuration,
                onConfigurationChanged = onConfigurationChanged,
            )
            FileSyncSetupStep.Rules -> FileSyncRulesStep(
                configuration = configuration,
                onConfigurationChanged = onConfigurationChanged,
                onChooseSelectedPaths = onChooseSelectedPaths,
                syntheticScopeSummary = syntheticScopeSummary,
            )
            FileSyncSetupStep.Review -> FileSyncReviewStep(
                localRoot = localRoot,
                remotePath = remotePath,
                configuration = configuration,
                onConfigurationChanged = onConfigurationChanged,
                initialAdvancedSettingsVisible = initialAdvancedSettingsVisible,
                syntheticScopeSummary = syntheticScopeSummary,
            )
        }
    }
}

@Composable
private fun FileSyncLocationsStep(
    localRoot: FileSyncLocalRoot,
    mediaSuggestion: MediaSyncFolderSuggestion?,
    remotePath: String,
    mediaPreview: MediaSyncFolderPreview?,
    mediaPreviewLoading: Boolean,
    mediaPreviewError: String?,
    onChooseDestination: () -> Unit,
) {
    FileSyncStepIntro("Where should changes go?", "Connect one folder on this device to one folder in Nextcloud.")
    FileSyncSetupLocationRow(
        icon = NextcloudIcons.FolderOpen,
        eyebrow = "Folder on this device",
        title = mediaSuggestion?.relativePath ?: localRoot.displayName,
        supporting = "Selected and kept under your control",
    )
    FileSyncSetupLocationRow(
        icon = NextcloudIcons.Cloud,
        eyebrow = "Folder in Nextcloud",
        title = if (remotePath.isBlank()) "Files root" else "/$remotePath",
        supporting = "This can have a different folder name",
        actionLabel = "Choose",
        onAction = onChooseDestination,
    )
    if (mediaSuggestion != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(NextcloudRadii.Small),
        ) {
            Column(Modifier.padding(NextcloudSpacing.Medium)) {
                Text("Media preview", style = MaterialTheme.typography.labelLarge)
                Text(
                    when {
                        mediaPreviewLoading -> "Checking the selected media folder..."
                        mediaPreviewError != null -> mediaPreviewError
                        mediaPreview != null -> "${mediaPreview.totalItems} items - ${mediaPreview.totalBytes.fileSyncBytes()}"
                        else -> "Preview will appear before sync is enabled."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mediaPreviewError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    FileSyncNotice("The first scan compares both locations before any transfer or deletion decision is made.")
}

@Composable
private fun FileSyncDirectionStep(
    mediaSuggestion: MediaSyncFolderSuggestion?,
    configuration: FileSyncConfiguration,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
) {
    FileSyncStepIntro("How should changes move?", "Choose the behavior that matches this folder.")
    val options = if (mediaSuggestion == null) FileSyncDirection.entries else listOf(FileSyncDirection.UploadOnly)
    options.forEach { direction ->
        FileSyncChoiceCard(
            selected = configuration.direction == direction,
            title = direction.syncDirectionTitle(),
            supporting = direction.syncDirectionDescription(),
            icon = when (direction) {
                FileSyncDirection.Bidirectional -> NextcloudIcons.Refresh
                FileSyncDirection.UploadOnly -> NextcloudIcons.Cloud
                FileSyncDirection.DownloadOnly -> NextcloudIcons.FolderOpen
            },
            onClick = { onConfigurationChanged(configuration.copy(direction = direction)) },
        )
    }
    if (mediaSuggestion != null) {
        FileSyncNotice("Detected media folders upload only. Nextcloud never writes back into the device's photo library.")
    }
}

@Composable
private fun FileSyncRulesStep(
    configuration: FileSyncConfiguration,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    onChooseSelectedPaths: () -> Unit,
    syntheticScopeSummary: String?,
) {
    var customEditorVisible by rememberSaveable {
        mutableStateOf(configuration.selectedPaths.isNotEmpty())
    }
    FileSyncStepIntro("Choose what syncs first", "Start with a safe preset, then refine it only if you need to.")
    FileSyncRulePreset.entries.forEach { preset ->
        val selected = preset.matches(configuration)
        FileSyncChoiceCard(
            selected = selected,
            title = preset.title,
            supporting = preset.supportingText,
            icon = when (preset) {
                FileSyncRulePreset.Everything -> NextcloudIcons.Folder
                FileSyncRulePreset.PhotoRawFirst -> NextcloudIcons.Photo
                FileSyncRulePreset.ChooseFolders -> NextcloudIcons.CheckCircle
            },
            onClick = {
                val next = preset.applyTo(configuration)
                customEditorVisible = preset == FileSyncRulePreset.ChooseFolders
                onConfigurationChanged(next)
            },
        )
    }
    FileSyncScopeSummary(configuration, syntheticScopeSummary)
    TextButton(onClick = { customEditorVisible = !customEditorVisible }) {
        Icon(NextcloudIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(NextcloudSpacing.Small))
        Text(if (customEditorVisible) "Hide custom rules" else "Customize rules")
    }
    if (customEditorVisible) {
        StructuredFileSyncRulesEditor(configuration, onConfigurationChanged, onChooseSelectedPaths)
    }
}

@Composable
private fun FileSyncReviewStep(
    localRoot: FileSyncLocalRoot,
    remotePath: String,
    configuration: FileSyncConfiguration,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    initialAdvancedSettingsVisible: Boolean,
    syntheticScopeSummary: String?,
) {
    var advancedVisible by rememberSaveable(initialAdvancedSettingsVisible) {
        mutableStateOf(initialAdvancedSettingsVisible)
    }
    FileSyncStepIntro("Review and start safely", "The first scan creates a plan. Conflicts and deletions still require your chosen policy.")
    FileSyncReviewRow("This device", localRoot.displayName)
    FileSyncReviewRow("Nextcloud", if (remotePath.isBlank()) "Files root" else "/$remotePath")
    FileSyncReviewRow("Direction", configuration.direction.syncDirectionTitle())
    FileSyncScopeSummary(configuration, syntheticScopeSummary)
    TextButton(onClick = { advancedVisible = !advancedVisible }) {
        Icon(NextcloudIcons.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(NextcloudSpacing.Small))
        Text(if (advancedVisible) "Hide safety settings" else "Safety, network, and power")
    }
    if (advancedVisible) {
        FileSyncAdvancedSettings(configuration, onConfigurationChanged)
    }
    FileSyncNotice("If the app closes or the network drops, completed work is preserved and the remaining queue resumes safely.")
}

@Composable
private fun FileSyncStepIntro(title: String, supporting: String) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FileSyncSetupLocationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    eyebrow: String,
    title: String,
    supporting: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun FileSyncChoiceCard(
    selected: Boolean,
    title: String,
    supporting: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(NextcloudIcons.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun FileSyncScopeSummary(configuration: FileSyncConfiguration, syntheticScopeSummary: String?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text("Scope preview", style = MaterialTheme.typography.labelLarge)
            syntheticScopeSummary?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                if (configuration.selectedPaths.isEmpty()) "Whole folder included" else "${configuration.selectedPaths.size} selected paths",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("${configuration.ignoredPatterns.size} ignore rules", style = MaterialTheme.typography.bodySmall)
            Text(
                if (configuration.priorityRules.isEmpty()) "Normal transfer order" else configuration.priorityRules.joinToString(
                    prefix = "Priority: ",
                    separator = " then ",
                    transform = { it.pattern.fileSyncFriendlyPattern() },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StructuredFileSyncRulesEditor(
    configuration: FileSyncConfiguration,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    onChooseSelectedPaths: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large)) {
        FileSyncSelectionEditor(
            values = configuration.selectedPaths,
            onChoose = onChooseSelectedPaths,
            onRemove = { removed ->
                onConfigurationChanged(
                    configuration.copy(selectedPaths = configuration.selectedPaths - removed),
                )
            },
        )
        FileSyncRuleListEditor(
            title = "Ignored files",
            supporting = "Temporary and generated files never enter the queue.",
            placeholder = "**/Cache/**",
            values = configuration.ignoredPatterns,
            validate = { value -> runCatching { requireValidFileSyncGlob(value) }.isSuccess },
            onValuesChanged = { onConfigurationChanged(configuration.copy(ignoredPatterns = it)) },
        )
        FileSyncRuleListEditor(
            title = "Transfer priority",
            supporting = "Higher rows transfer first. This does not skip lower rows.",
            placeholder = "**/*.raf",
            values = configuration.priorityRules.map(FileSyncPriorityRule::pattern),
            validate = { value -> runCatching { requireValidFileSyncGlob(value) }.isSuccess },
            reorderable = true,
            onValuesChanged = { values ->
                onConfigurationChanged(configuration.copy(priorityRules = values.map(::FileSyncPriorityRule)))
            },
        )
    }
}

@Composable
private fun FileSyncSelectionEditor(
    values: List<String>,
    onChoose: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text("Selected folders and files", style = MaterialTheme.typography.labelLarge)
        Text(
            "Choose verified items from the mapped Nextcloud folder. Leave empty to include everything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        values.forEach { value ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                Row(
                    modifier = Modifier.padding(start = NextcloudSpacing.Medium, end = NextcloudSpacing.XSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(NextcloudIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(NextcloudSpacing.Small))
                    Text(value, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onRemove(value) }) { Text("Remove") }
                }
            }
        }
        OutlinedButton(onClick = onChoose) {
            Icon(NextcloudIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(NextcloudSpacing.Small))
            Text(if (values.isEmpty()) "Choose folders or files" else "Change selection")
        }
    }
}

@Composable
private fun FileSyncRuleListEditor(
    title: String,
    supporting: String,
    placeholder: String,
    values: List<String>,
    validate: (String) -> Boolean,
    reorderable: Boolean = false,
    onValuesChanged: (List<String>) -> Unit,
) {
    var draft by rememberSaveable(title) { mutableStateOf("") }
    val normalizedDraft = draft.trim()
    val canAdd = normalizedDraft.isNotEmpty() && normalizedDraft !in values && validate(normalizedDraft)
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        values.forEachIndexed { index, value ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                Row(
                    modifier = Modifier.padding(start = NextcloudSpacing.Medium, end = NextcloudSpacing.XSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value.fileSyncFriendlyPattern(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (reorderable && index > 0) {
                        TextButton(onClick = {
                            val next = values.toMutableList()
                            val moved = next.removeAt(index)
                            next.add(index - 1, moved)
                            onValuesChanged(next)
                        }) { Text("Up") }
                    }
                    if (reorderable && index < values.lastIndex) {
                        TextButton(onClick = {
                            val next = values.toMutableList()
                            val moved = next.removeAt(index)
                            next.add(index + 1, moved)
                            onValuesChanged(next)
                        }) { Text("Down") }
                    }
                    TextButton(onClick = { onValuesChanged(values.filterIndexed { itemIndex, _ -> itemIndex != index }) }) {
                        Text("Remove")
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(1_024) },
                modifier = Modifier.weight(1f),
                label = { Text("Add rule") },
                placeholder = { Text(placeholder) },
                singleLine = true,
                isError = normalizedDraft.isNotEmpty() && !canAdd,
            )
            Button(
                enabled = canAdd,
                onClick = {
                    onValuesChanged(values + normalizedDraft)
                    draft = ""
                },
            ) { Text("Add") }
        }
    }
}

@Composable
private fun FileSyncAdvancedSettings(
    configuration: FileSyncConfiguration,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
) {
    var deviceLabelDraft by rememberSaveable { mutableStateOf(configuration.deviceLabel) }
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large)) {
        FileSyncSettingChoices(
            title = "When both copies changed",
            options = FileSyncConflictPolicy.entries,
            selected = configuration.conflictPolicy,
            label = FileSyncConflictPolicy::syncConflictTitle,
            onSelected = { onConfigurationChanged(configuration.copy(conflictPolicy = it)) },
        )
        FileSyncSettingChoices(
            title = "When a file was deleted",
            options = FileSyncDeletionPolicy.entries,
            selected = configuration.deletionPolicy,
            label = FileSyncDeletionPolicy::syncDeletionTitle,
            onSelected = { onConfigurationChanged(configuration.copy(deletionPolicy = it)) },
        )
        FileSyncSettingChoices(
            title = "Connection",
            options = FileSyncNetworkPolicy.entries,
            selected = configuration.networkPolicy,
            label = FileSyncNetworkPolicy::syncNetworkTitle,
            onSelected = { onConfigurationChanged(configuration.copy(networkPolicy = it)) },
        )
        FileSyncSettingChoices(
            title = "Power",
            options = FileSyncPowerPolicy.entries,
            selected = configuration.powerPolicy,
            label = FileSyncPowerPolicy::syncPowerTitle,
            onSelected = { onConfigurationChanged(configuration.copy(powerPolicy = it)) },
        )
        OutlinedTextField(
            value = deviceLabelDraft,
            onValueChange = { value ->
                deviceLabelDraft = value.take(128)
                if (deviceLabelDraft.isNotBlank()) {
                    onConfigurationChanged(configuration.copy(deviceLabel = deviceLabelDraft))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Device label for conflict copies") },
            singleLine = true,
            isError = deviceLabelDraft.isBlank(),
            supportingText = if (deviceLabelDraft.isBlank()) {
                { Text("Enter a device label before continuing.") }
            } else {
                null
            },
        )
    }
}

@Composable
private fun <T> FileSyncSettingChoices(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        options.forEach { option ->
            FileSyncChoiceCard(
                selected = option == selected,
                title = label(option),
                supporting = if (option == selected) "Selected" else "Tap to select",
                icon = if (option == selected) NextcloudIcons.CheckCircle else NextcloudIcons.Info,
                onClick = { onSelected(option) },
            )
        }
    }
}

@Composable
private fun FileSyncReviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FileSyncSetupFooter(
    step: FileSyncSetupStep,
    busy: Boolean,
    configuration: FileSyncConfiguration,
    mediaReady: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step != FileSyncSetupStep.Locations) {
            OutlinedButton(enabled = !busy, onClick = onBack) { Text("Back") }
        }
        if (step != FileSyncSetupStep.Review) {
            Button(enabled = !busy, onClick = onNext) { Text("Continue") }
        } else {
            Button(
                enabled = !busy && configuration.deviceLabel.isNotBlank() && mediaReady,
                onClick = onAdd,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Add sync")
            }
        }
    }
}

private fun FileSyncRulePreset.matches(configuration: FileSyncConfiguration): Boolean = when (this) {
    FileSyncRulePreset.Everything -> configuration.selectedPaths.isEmpty() &&
        configuration.ignoredPatterns.isEmpty() && configuration.priorityRules.isEmpty()
    FileSyncRulePreset.PhotoRawFirst -> configuration.selectedPaths.isEmpty() &&
        configuration.priorityRules.map(FileSyncPriorityRule::pattern) == listOf("**/*.raf", "**/*.jpg", "**/*.jpeg")
    FileSyncRulePreset.ChooseFolders -> configuration.selectedPaths.isNotEmpty()
}

private fun FileSyncRulePreset.applyTo(configuration: FileSyncConfiguration): FileSyncConfiguration = when (this) {
    FileSyncRulePreset.Everything -> configuration.copy(
        selectedPaths = emptyList(),
        ignoredPatterns = emptyList(),
        priorityRules = emptyList(),
    )
    FileSyncRulePreset.PhotoRawFirst -> configuration.copy(
        selectedPaths = emptyList(),
        ignoredPatterns = listOf("*.part", "**/.thumbnails/**", "**/Cache/**"),
        priorityRules = listOf(
            FileSyncPriorityRule("**/*.raf"),
            FileSyncPriorityRule("**/*.jpg"),
            FileSyncPriorityRule("**/*.jpeg"),
        ),
    )
    FileSyncRulePreset.ChooseFolders -> configuration
}

private fun FileSyncDirection.syncDirectionTitle(): String = when (this) {
    FileSyncDirection.Bidirectional -> "Two-way"
    FileSyncDirection.UploadOnly -> "Device to Nextcloud"
    FileSyncDirection.DownloadOnly -> "Nextcloud to device"
}

private fun FileSyncDirection.syncDirectionDescription(): String = when (this) {
    FileSyncDirection.Bidirectional -> "Changes on either side are copied to the other side."
    FileSyncDirection.UploadOnly -> "Changes from this device upload; Nextcloud never writes back."
    FileSyncDirection.DownloadOnly -> "Changes from Nextcloud download; local changes never upload."
}

private fun FileSyncDirection.syncDirectionGlyph(): String = when (this) {
    FileSyncDirection.Bidirectional -> "<->"
    FileSyncDirection.UploadOnly -> "->"
    FileSyncDirection.DownloadOnly -> "<-"
}

private fun FileSyncPairSummary.queuedLabel(): String = when {
    runningCount > 0 -> "$runningCount active"
    readyCount > 0 -> "$readyCount ready"
    else -> "None"
}

private fun FileSyncPairSummary.selectionSummary(): String = if (configuration.selectedPaths.isEmpty()) {
    "Everything in this folder"
} else {
    "${configuration.selectedPaths.size} selected paths"
}

private fun FileSyncPairSummary.ignoreSummary(): String = if (configuration.ignoredPatterns.isEmpty()) {
    "No ignored patterns"
} else {
    "Ignore ${configuration.ignoredPatterns.size} patterns"
}

private fun FileSyncPairSummary.prioritySummary(): String = if (configuration.priorityRules.isEmpty()) {
    "Normal transfer priority"
} else {
    configuration.priorityRules.joinToString(
        prefix = "Priority: ",
        separator = " then ",
        transform = { it.pattern.fileSyncFriendlyPattern() },
    )
}

private fun String.fileSyncFriendlyPattern(): String = when (lowercase()) {
    "**/*.raf" -> "RAW (.raf)"
    "**/*.jpg" -> "JPEG (.jpg)"
    "**/*.jpeg" -> "JPEG (.jpeg)"
    else -> this
}

private fun FileSyncConflictPolicy.syncConflictTitle(): String = when (this) {
    FileSyncConflictPolicy.Ask -> "Ask before changing either copy"
    FileSyncConflictPolicy.KeepBoth -> "Keep both copies"
    FileSyncConflictPolicy.PreferLocal -> "Prefer this device"
    FileSyncConflictPolicy.PreferRemote -> "Prefer Nextcloud"
}

private fun FileSyncDeletionPolicy.syncDeletionTitle(): String = when (this) {
    FileSyncDeletionPolicy.Ask -> "Ask before deleting the other copy"
    FileSyncDeletionPolicy.Propagate -> "Delete the other copy"
    FileSyncDeletionPolicy.RestoreMissing -> "Restore the missing copy"
}

private fun FileSyncNetworkPolicy.syncNetworkTitle(): String = when (this) {
    FileSyncNetworkPolicy.AnyConnection -> "Wi-Fi or mobile data"
    FileSyncNetworkPolicy.Unmetered -> "Unmetered network only"
}

private fun FileSyncPowerPolicy.syncPowerTitle(): String = when (this) {
    FileSyncPowerPolicy.AnyPower -> "Any battery level"
    FileSyncPowerPolicy.BatteryNotLow -> "Pause when battery is low"
    FileSyncPowerPolicy.Charging -> "Only while charging"
}
