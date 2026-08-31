package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun FilesCommandBar(
    path: String,
    query: String,
    onQueryChanged: (String) -> Unit,
    searchScope: FileSearchScope,
    onSearchScopeChanged: (FileSearchScope) -> Unit,
    refreshing: Boolean,
    searchLoading: Boolean,
    layout: FileLayout,
    onLayoutChanged: (FileLayout) -> Unit,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPath: (String) -> Unit,
    desktop: Boolean,
    paneActions: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = if (desktop) NextcloudSpacing.Large else NextcloudSpacing.Medium,
            vertical = NextcloudSpacing.Small,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Column(modifier = if (desktop) Modifier.width(100.dp) else Modifier.weight(1f)) {
                Text("Files", style = MaterialTheme.typography.titleLarge)
            }
            if (desktop) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.weight(1f).heightIn(max = 52.dp),
                    placeholder = {
                        Text(if (searchScope == FileSearchScope.AllFiles) "Search all files" else "Search this folder")
                    },
                    leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                )
                SearchScopeControl(searchScope, onSearchScopeChanged)
            }
            Button(onClick = onCreate) {
                Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (desktop) "New" else "Add")
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(NextcloudIcons.Refresh, contentDescription = "Refresh files")
            }
            LayoutControl(layout, onLayoutChanged)
            paneActions()
        }
        if (!desktop) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(if (searchScope == FileSearchScope.AllFiles) "Search all files" else "Search this folder")
                },
                leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        SearchScopeControl(searchScope, onSearchScopeChanged)
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(NextcloudRadii.Medium),
            )
        }
        if (searchScope == FileSearchScope.CurrentFolder) {
            FilesBreadcrumbs(path, onOpenPath)
        } else if (query.isNotBlank()) {
            Text(
                "Searching across your entire Nextcloud",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SearchScopeControl(scope: FileSearchScope, onChanged: (FileSearchScope) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(if (scope == FileSearchScope.AllFiles) "Everywhere" else "Folder")
            Icon(NextcloudIcons.ExpandMore, contentDescription = "Change search scope", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            FileSearchScope.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(if (candidate == FileSearchScope.AllFiles) "All files" else "This folder") },
                    onClick = { expanded = false; onChanged(candidate) },
                    trailingIcon = {
                        if (scope == candidate) Icon(NextcloudIcons.CheckCircle, null, Modifier.size(18.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun LayoutControl(layout: FileLayout, onChanged: (FileLayout) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(NextcloudIcons.ListView, contentDescription = "Layout: ${layout.name}. Change view")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            FileLayout.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text("${candidate.name} view") },
                    onClick = { expanded = false; onChanged(candidate) },
                    trailingIcon = {
                        if (layout == candidate) Icon(NextcloudIcons.CheckCircle, null, Modifier.size(18.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun FilesBreadcrumbs(path: String, onOpenPath: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        fileBreadcrumbs(path).forEachIndexed { index, breadcrumb ->
            if (index > 0) {
                Icon(
                    NextcloudIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            TextButton(onClick = { onOpenPath(breadcrumb.path) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(
                    breadcrumb.label,
                    color = if (index == fileBreadcrumbs(path).lastIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
