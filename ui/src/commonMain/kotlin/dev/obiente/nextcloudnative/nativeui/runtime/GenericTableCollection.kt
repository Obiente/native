package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec

internal fun nativeCollectionTableProjection(
    schema: NativeAppSchema,
    view: ViewSpec,
    resource: ResourceSpec,
    records: List<NativeRecord>,
    datasetContext: NativeDatasetContext,
): NativeTableProjection {
    val composite = view.compositeDataGrid
    return nativeTableProjection(
        resource,
        records,
        composite?.let { schema.resource(it.columnResourceId) },
        composite?.let { datasetContext.relatedRecords[it.columnResourceId].orEmpty() }.orEmpty(),
        composite,
    )
}

@Composable
internal fun GenericTableCollection(
    schema: NativeAppSchema,
    view: ViewSpec,
    resource: ResourceSpec,
    records: List<NativeRecord>,
    datasetContext: NativeDatasetContext,
    actionExecutor: NativeActionExecutor,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    onInlineActionSucceeded: ((ActionSpec) -> Unit)?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
    searchQuery: String,
) {
    val composite = view.compositeDataGrid
    val columnResource = composite?.let { schema.resource(it.columnResourceId) }
    val columnRecords = composite?.let { datasetContext.relatedRecords[it.columnResourceId].orEmpty() }.orEmpty()
    val projection = remember(resource, records, columnResource, columnRecords, composite) {
        nativeTableProjection(resource, records, columnResource, columnRecords, composite)
    }
    val facets = remember(projection) { inferNativeDatasetFacets(projection.resource, projection.records) }
    val browseStateKey = remember(schema, view, projection.resource, datasetContext) {
        nativeDatasetBrowseStateKey(schema, view, projection.resource, datasetContext)
    }
    var facetSelections by remember(browseStateKey) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var sortMode by remember(browseStateKey) { mutableStateOf(NativeDatasetSortMode.Server) }
    var filtersExpanded by remember(browseStateKey) { mutableStateOf(false) }
    val filteredRecords = remember(projection, facetSelections, searchQuery, sortMode) {
        browseNativeDatasetRecords(
            resource = projection.resource,
            records = projection.records,
            selections = facetSelections,
            searchQuery = searchQuery,
            sortMode = sortMode,
        )
    }

    fun toggleFacet(fieldId: String, value: String) {
        val nextValues = facetSelections[fieldId].orEmpty().toMutableSet().apply {
            if (!add(value)) remove(value)
        }
        facetSelections = facetSelections.toMutableMap().apply {
            if (nextValues.isEmpty()) remove(fieldId) else put(fieldId, nextValues)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactRecordList = shouldUseCompactTableRecordList(maxWidth.value)
        Column(modifier = Modifier.fillMaxSize()) {
            NativeTableBrowseControls(
                facets = facets,
                selections = facetSelections,
                filtersExpanded = filtersExpanded,
                onFiltersExpandedChange = { filtersExpanded = it },
                onToggleFacet = ::toggleFacet,
                onClearFilters = { facetSelections = emptyMap() },
                sortMode = sortMode,
                onSortModeChanged = { sortMode = it },
            )
            if (filteredRecords.isEmpty()) {
                LaunchedEffect(
                    facetSelections, searchQuery,
                    projection.records.size,
                    onLoadMore,
                    loadingMore,
                    loadMoreError,
                ) {
                    if (onLoadMore != null && !loadingMore && loadMoreError == null) {
                        onLoadMore()
                    }
                }
                GenericCenteredState {
                    Text("No matching records", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Clear or adjust the current search and filters to see more records.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    NativeCollectionPagingStatus(
                        loadingMore = loadingMore,
                        loadMoreError = loadMoreError,
                        onRetry = onLoadMore,
                    )
                }
            } else if (compactRecordList) {
                GenericEditableTableRecordList(
                    schema = schema,
                    sourceResource = resource,
                    projection = projection,
                    records = filteredRecords,
                    onSelectRecord = onSelectRecord,
                    actionExecutor = actionExecutor,
                    onInlineActionSucceeded = onInlineActionSucceeded,
                    onLoadMore = onLoadMore,
                    loadingMore = loadingMore,
                    loadMoreError = loadMoreError,
                    modifier = Modifier.weight(1f),
                )
            } else {
                GenericRecordTable(
                    schema,
                    view,
                    resource,
                    filteredRecords,
                    datasetContext,
                    actionExecutor,
                    onSelectRecord,
                    onInlineActionSucceeded,
                    onLoadMore,
                    loadingMore,
                    loadMoreError,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NativeTableBrowseControls(
    facets: List<NativeDatasetFacet>,
    selections: Map<String, Set<String>>,
    filtersExpanded: Boolean,
    onFiltersExpandedChange: (Boolean) -> Unit,
    onToggleFacet: (fieldId: String, value: String) -> Unit,
    onClearFilters: () -> Unit,
    sortMode: NativeDatasetSortMode,
    onSortModeChanged: (NativeDatasetSortMode) -> Unit,
) {
    val activeFilterCount = selections.values.sumOf(Set<String>::size)
    var sortExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Large,
            vertical = NextcloudSpacing.Small,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(
                    enabled = facets.isNotEmpty(),
                    onClick = { onFiltersExpandedChange(!filtersExpanded) },
                ) {
                    Icon(NextcloudIcons.Filter, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        if (activeFilterCount == 0) "Filter" else "Filter ($activeFilterCount)",
                        modifier = Modifier.padding(start = NextcloudSpacing.XSmall),
                    )
                }
                DropdownMenu(
                    expanded = filtersExpanded,
                    onDismissRequest = { onFiltersExpandedChange(false) },
                ) {
                    facets.forEachIndexed { index, facet ->
                        Text(
                            facet.field.label,
                            modifier = Modifier.padding(
                                start = NextcloudSpacing.Large,
                                top = if (index == 0) NextcloudSpacing.Small else NextcloudSpacing.Medium,
                                end = NextcloudSpacing.Large,
                                bottom = NextcloudSpacing.XSmall,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        facet.options.forEach { option ->
                            val selected = option.value in selections[facet.field.id].orEmpty()
                            DropdownMenuItem(
                                text = { Text("${option.label} (${option.count})") },
                                trailingIcon = if (selected) {
                                    {
                                        Icon(
                                            NextcloudIcons.CheckCircle,
                                            contentDescription = "Selected",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = { onToggleFacet(facet.field.id, option.value) },
                            )
                        }
                    }
                    if (activeFilterCount > 0) {
                        DropdownMenuItem(
                            text = { Text("Clear filters") },
                            onClick = {
                                onClearFilters()
                                onFiltersExpandedChange(false)
                            },
                        )
                    }
                }
            }
            Box {
                OutlinedButton(onClick = { sortExpanded = true }) {
                    Text(sortMode.label)
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    NativeDatasetSortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                onSortModeChanged(mode)
                                sortExpanded = false
                            },
                            trailingIcon = if (mode == sortMode) {
                                { Icon(NextcloudIcons.CheckCircle, contentDescription = "Selected") }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}
