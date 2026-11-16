package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import dev.obiente.nextcloudnative.app.design.NextcloudFilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudContentEntrance
import dev.obiente.nextcloudnative.app.design.NextcloudEntranceAxis
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Composable
internal fun FileSyncWorkspace(
    snapshot: FileSyncCenterSnapshot?,
    loading: Boolean,
    busyPairId: String?,
    busyPairIds: Set<String> = busyPairId?.let(::setOf).orEmpty(),
    addEnabled: Boolean = true,
    onAdd: () -> Unit,
    mutationsEnabled: Boolean = true,
    onEdit: ((FileSyncPairSummary) -> Unit)? = null,
    detailSheetVisible: Boolean = true,
    onRun: (FileSyncPairSummary) -> Unit,
    onRemove: (FileSyncPairSummary) -> Unit,
    onResolve: (FileSyncPairSummary, FileSyncConflictSummary, FileSyncDecisionChoice) -> Unit,
    onResolveBatch: (FileSyncPairSummary, List<FileSyncConflictSummary>, FileSyncDecisionChoice) -> Unit =
        { _, _, _ -> },
    initialSelectedPairId: String? = null,
    selection: FileSyncWorkspaceSelection = rememberFileSyncWorkspaceSelection(initialSelectedPairId = initialSelectedPairId),
    modifier: Modifier = Modifier.fillMaxWidth(),
    fillAvailableHeight: Boolean = false,
) {
    val pairs = snapshot?.pairs.orEmpty()
    val selectedPairId = selection.pairId
    val detailTab = selection.tabName
    var filter by rememberSaveable { mutableStateOf(FileSyncListFilter.All) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filtersVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(snapshot, loading, selection) {
        if (snapshot != null && !loading && pairs.none { it.id == selectedPairId }) {
            selection.select(null)
        }
    }
    val visiblePairs = remember(pairs, filter, searchQuery) {
        filterFileSyncPairs(pairs, filter, searchQuery)
    }
    val selectedPair = inspectedFileSyncPair(visiblePairs, selectedPairId)

    BoxWithConstraints(modifier = modifier) {
        val desktop = maxWidth >= 940.dp
        val phonePair = visiblePairs.firstOrNull { it.id == selectedPairId }
        if (!desktop && phonePair != null) {
            val close = { selection.select(null) }
            if (LocalNextcloudWorkspaceCapabilities.current.isDesktop) {
                FileSyncPairDetailPage(
                    phonePair, busyPairIds, fillAvailableHeight, close, onRun, onRemove, onResolve, onResolveBatch,
                    mutationsEnabled, onEdit, detailTab, selection::selectTab,
                )
                return@BoxWithConstraints
            }
            if (detailSheetVisible) {
                FileSyncPairDetailSheet(phonePair, busyPairIds, close, onRun, onRemove, onResolve, onResolveBatch,
                    mutationsEnabled, onEdit, detailTab, selection::selectTab)
            }
        }
        Column(
            modifier = if (fillAvailableHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            FileSyncWorkspaceHeader(
                pairs = pairs,
                loading = loading,
                actionsEnabled = addEnabled,
                onAdd = onAdd,
                compact = !desktop,
            )
            snapshot?.limitation?.let { limitation ->
                FileSyncNotice(limitation)
            }
            if (loading && snapshot == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!loading && snapshot != null && pairs.isEmpty()) {
                FileSyncEmptyState(
                    onAdd = onAdd,
                    enabled = addEnabled,
                    fillAvailableHeight = fillAvailableHeight,
                )
            } else if (pairs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val issuePair = pairs.firstOrNull { it.needsSyncAttention() }
                    if (issuePair != null) {
                        TextButton(onClick = {
                            filter = FileSyncListFilter.Attention
                            searchQuery = ""
                            selection.select(issuePair.id)
                        }) { Text("Review issues") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { filtersVisible = !filtersVisible }) {
                        Icon(NextcloudIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (filtersVisible) "Hide filters" else "Search and filter")
                    }
                }
                if (filtersVisible || filter != FileSyncListFilter.All || searchQuery.isNotBlank()) {
                    FileSyncFilters(
                        selected = filter, pairs = pairs, searchQuery = searchQuery,
                        onSelected = { filter = it }, onSearchQueryChanged = { searchQuery = it },
                    )
                }
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
                            onSelect = { selection.select(it.id) },
                            modifier = if (fillAvailableHeight) {
                                Modifier.weight(1.9f).fillMaxHeight()
                            } else {
                                Modifier.weight(1.9f).heightIn(min = 520.dp, max = 760.dp)
                            },
                        )
                        // Choosing another sync slides its details in beside the table.
                        NextcloudContentEntrance(inspectedPair?.id, NextcloudEntranceAxis.Horizontal) {
                            FileSyncPairInspector(
                                pair = inspectedPair,
                                busy = inspectedPair?.id in busyPairIds,
                                actionsEnabled = mutationsEnabled && inspectedPair?.id !in busyPairIds,
                                onEdit = onEdit?.let { edit -> { inspectedPair?.let(edit); Unit } },
                                selectedTabName = detailTab, onTabSelected = selection::selectTab,
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
                    }
                    FileSyncDesktopStatusBar(pair = inspectedPair)
                } else if (fillAvailableHeight) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        items(visiblePairs, key = { it.id }) { pair ->
                            FileSyncMobilePairCard(pair = pair, onSelect = { selection.select(pair.id) })
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        visiblePairs.forEach { pair ->
                            key(pair.id) {
                                FileSyncMobilePairCard(pair = pair, onSelect = { selection.select(pair.id) })
                            }
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
    ?: visiblePairs.firstOrNull { it.needsSyncAttention() }
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
                if (loading) "Checking for changes..." else fileSyncWorkspaceSummary(pairs),
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
        FileSyncListFilter.Attention to pairs.count(FileSyncPairSummary::needsSyncAttention),
        FileSyncListFilter.Paused to pairs.count(FileSyncPairSummary::isFileSyncPaused),
        FileSyncListFilter.Offline to pairs.count(FileSyncPairSummary::isFileSyncOffline),
    )
    @Composable
    fun filterChip(option: FileSyncListFilter, modifier: Modifier = Modifier, fill: Boolean = false) {
        NextcloudFilterChip(
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
            FileSyncListFilter.Attention -> pair.needsSyncAttention()
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

internal fun FileSyncPairSummary.isFileSyncPaused(): Boolean =
    runState == FileSyncPairRunState.Paused

internal fun FileSyncPairSummary.isFileSyncOffline(): Boolean =
    networkState == FileSyncNetworkState.WaitingForNetwork

internal fun FileSyncDirection.fileSyncDirectionIcon() = when (this) {
    FileSyncDirection.Bidirectional -> NextcloudIcons.Refresh
    FileSyncDirection.UploadOnly -> NextcloudIcons.Cloud
    FileSyncDirection.DownloadOnly -> NextcloudIcons.FolderOpen
}

internal fun FileSyncPairSummary.queuedCompactLabel(): String = when {
    runningCount > 0 && readyCount > 0 -> "$runningCount active\n$readyCount queued"
    runningCount > 0 -> "$runningCount active"
    readyCount > 0 -> "$readyCount queued"
    else -> "-"
}

@Composable
internal fun FileSyncNotice(message: String) {
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
private fun FileSyncEmptyState(onAdd: () -> Unit, enabled: Boolean, fillAvailableHeight: Boolean) {
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
            Button(enabled = enabled, onClick = onAdd) { Text("Add your first sync") }
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
            FileSyncDesktopStatusItem("Selected sync", pair?.observedSyncState()?.label ?: "None")
            FileSyncDesktopStatusItem(
                "Network",
                when (pair?.networkState) {
                    FileSyncNetworkState.Available -> "Connected"
                    FileSyncNetworkState.WaitingForNetwork -> "Offline"
                    FileSyncNetworkState.Unknown, null -> "Unknown"
                },
            )
            Spacer(Modifier.weight(1f))
            pair?.let {
                Text(
                    "${it.completedCount} completed operations",
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
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

