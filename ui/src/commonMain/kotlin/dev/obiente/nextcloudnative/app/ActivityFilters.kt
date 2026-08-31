package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

@Composable
internal fun ActivityFilterToolbar(
    query: String,
    selectedSemantic: NextcloudActivitySemantic?,
    selectedApp: String?,
    selectedType: String?,
    serverFilters: List<NextcloudActivityFilterOption>,
    selectedServerFilterId: String,
    feed: ActivityFeedPresentation,
    onQueryChanged: (String) -> Unit,
    onSemanticSelected: (NextcloudActivitySemantic?) -> Unit,
    onAppSelected: (String?) -> Unit,
    onTypeSelected: (String?) -> Unit,
    onServerFilterSelected: (String) -> Unit,
    onClearFilters: () -> Unit,
) {
    var filtersExpanded by remember { mutableStateOf(false) }
    val activeCount = listOf(
        selectedServerFilterId != "all", selectedSemantic != null,
        selectedApp != null, selectedType != null,
    ).count { it }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(NextcloudIcons.Search, null, Modifier.size(18.dp)) },
                label = { Text("Search loaded activity") },
            )
            OutlinedButton(onClick = { filtersExpanded = !filtersExpanded }) {
                Icon(NextcloudIcons.Filter, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (activeCount > 0) "Filters ($activeCount)" else "Filters")
            }
        }
        if (filtersExpanded) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ActivityServerFilterMenu(serverFilters, selectedServerFilterId, onServerFilterSelected)
                ActivitySemanticMenu(selectedSemantic, feed.semanticCounts, onSemanticSelected)
                ActivityFacetMenu(
                    selectedApp?.let(::activityReadableSource) ?: "All apps",
                    "All apps", feed.appFacets, selectedApp, onAppSelected,
                )
                ActivityFacetMenu(
                    selectedType?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "All events",
                    "All events", feed.typeFacets, selectedType, onTypeSelected,
                )
            }
        }
        if (activeCount > 0 || query.isNotBlank()) {
            TextButton(onClick = onClearFilters) { Text("Clear filters and search") }
        }
    }
}

@Composable
internal fun ActivityServerFilterMenu(
    filters: List<NextcloudActivityFilterOption>,
    selectedId: String,
    onSelected: (String) -> Unit,
) {
    val available = filters.ifEmpty {
        listOf(NextcloudActivityFilterOption("all", "All activities", 0))
    }
    val selected = available.firstOrNull { it.id == selectedId } ?: available.first()
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(NextcloudIcons.Filter, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(selected.name, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            available.forEach { filter ->
                DropdownMenuItem(
                    text = { Text(filter.name) },
                    onClick = {
                        expanded = false
                        onSelected(filter.id)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ActivitySemanticMenu(
    selected: NextcloudActivitySemantic?,
    counts: Map<NextcloudActivitySemantic, Int>,
    onSelected: (NextcloudActivitySemantic?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(NextcloudIcons.Filter, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(selected?.desktopTitle() ?: "Any content")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Any content") },
                onClick = { expanded = false; onSelected(null) },
            )
            NextcloudActivitySemantic.entries.forEach { semantic ->
                val count = counts[semantic] ?: 0
                if (count > 0) {
                    DropdownMenuItem(
                        text = { Text("${semantic.desktopTitle()} ($count)") },
                        onClick = { expanded = false; onSelected(semantic) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ActivityFacetMenu(
    label: String,
    allLabel: String,
    facets: List<ActivityFeedFacet>,
    selected: String?,
    onSelected: (String?) -> Unit,
) {
    if (facets.size <= 1 && selected == null) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label, maxLines = 1) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { expanded = false; onSelected(null) })
            facets.take(12).forEach { facet ->
                DropdownMenuItem(
                    text = { Text("${facet.label} (${facet.count})") },
                    onClick = { expanded = false; onSelected(facet.key) },
                )
            }
        }
    }
}
