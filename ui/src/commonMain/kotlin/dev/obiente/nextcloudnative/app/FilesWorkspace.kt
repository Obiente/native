package dev.obiente.nextcloudnative.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

internal enum class FileLayout { List, Grid, Compact }

@Composable
internal fun NativeFilesWorkspace(
    path: String,
    files: List<NextcloudFile>,
    libraryFiles: List<NextcloudFile>,
    navigationFiles: List<NextcloudFile>,
    totalFilesInFolder: Int,
    listingSource: NextcloudFileListingSource?,
    refreshing: Boolean,
    searchLoading: Boolean,
    searchError: String?,
    query: String,
    onQueryChanged: (String) -> Unit,
    searchScope: FileSearchScope,
    onSearchScopeChanged: (FileSearchScope) -> Unit,
    filter: FileWorkspaceFilter,
    onFilterChanged: (FileWorkspaceFilter) -> Unit,
    sortMode: FileSortMode,
    onSortModeChanged: (FileSortMode) -> Unit,
    sortDirection: FileSortDirection,
    onSortDirectionChanged: (FileSortDirection) -> Unit,
    layout: FileLayout,
    onLayoutChanged: (FileLayout) -> Unit,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    selectedFile: NextcloudFile?,
    onSelectedFileChanged: (NextcloudFile?) -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val desktop = maxWidth >= 980.dp
        val showInspector = desktop && maxWidth >= 1_080.dp
        Column(modifier = Modifier.fillMaxSize()) {
            FilesCommandBar(
                path = path,
                query = query,
                onQueryChanged = onQueryChanged,
                searchScope = searchScope,
                onSearchScopeChanged = onSearchScopeChanged,
                refreshing = refreshing,
                searchLoading = searchLoading,
                layout = layout,
                onLayoutChanged = onLayoutChanged,
                onCreate = onCreate,
                onRefresh = onRefresh,
                onOpenPath = onOpenPath,
                desktop = desktop,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (desktop) {
                Row(modifier = Modifier.fillMaxSize()) {
                    FilesNavigationPane(
                        files = navigationFiles,
                        currentPath = path,
                        selectedFilter = filter,
                        offlineAvailability = offlineAvailability,
                        onFilterChanged = onFilterChanged,
                        onOpenPath = onOpenPath,
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FilesBrowserPane(
                        modifier = Modifier.weight(1f),
                        files = files,
                        libraryFiles = libraryFiles,
                        totalFilesInFolder = totalFilesInFolder,
                        listingSource = listingSource,
                        query = query,
                        searchScope = searchScope,
                        searchLoading = searchLoading,
                        searchError = searchError,
                        filter = filter,
                        onFilterChanged = onFilterChanged,
                        sortMode = sortMode,
                        onSortModeChanged = onSortModeChanged,
                        sortDirection = sortDirection,
                        onSortDirectionChanged = onSortDirectionChanged,
                        layout = layout,
                        offlineAvailability = offlineAvailability,
                        offlineStorageSupported = offlineStorageSupported,
                        fileSharing = fileSharing,
                        externalHandoffCapability = externalHandoffCapability,
                        services = services,
                        session = session,
                        userId = userId,
                        selectedFile = selectedFile,
                        onSelectedFileChanged = onSelectedFileChanged,
                        onOpenPath = onOpenPath,
                        onOpenFile = onOpenFile,
                        onAction = onAction,
                        desktop = true,
                    )
                    AnimatedVisibility(visible = showInspector) {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FilesInspector(
                            file = selectedFile,
                            offlineAvailability = selectedFile?.let { offlineAvailability[it.path] },
                            offlineStorageSupported = offlineStorageSupported,
                            fileSharing = fileSharing,
                            externalHandoffCapability = externalHandoffCapability,
                            services = services,
                            session = session,
                            userId = userId,
                            onOpen = { file ->
                                if (file.isDirectory) onOpenPath(file.path) else onOpenFile(file)
                            },
                            onAction = onAction,
                        )
                    }
                }
            } else {
                FilesBrowserPane(
                    modifier = Modifier.fillMaxSize(),
                    files = files,
                    libraryFiles = libraryFiles,
                    totalFilesInFolder = totalFilesInFolder,
                    listingSource = listingSource,
                    query = query,
                    searchScope = searchScope,
                    searchLoading = searchLoading,
                    searchError = searchError,
                    filter = filter,
                    onFilterChanged = onFilterChanged,
                    sortMode = sortMode,
                    onSortModeChanged = onSortModeChanged,
                    sortDirection = sortDirection,
                    onSortDirectionChanged = onSortDirectionChanged,
                    layout = layout,
                    offlineAvailability = offlineAvailability,
                    offlineStorageSupported = offlineStorageSupported,
                    fileSharing = fileSharing,
                    externalHandoffCapability = externalHandoffCapability,
                    services = services,
                    session = session,
                    userId = userId,
                    selectedFile = selectedFile,
                    onSelectedFileChanged = onSelectedFileChanged,
                    onOpenPath = onOpenPath,
                    onOpenFile = onOpenFile,
                    onAction = onAction,
                    desktop = false,
                )
            }
        }
    }
}

@Composable
private fun FilesCommandBar(
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
            Column(modifier = if (desktop) Modifier.widthIn(min = 210.dp) else Modifier.weight(1f)) {
                Text("Files", style = MaterialTheme.typography.titleLarge)
                if (desktop) {
                    Text(
                        "Browse, organize, share, and keep work available offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
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
            if (desktop) LayoutControl(layout, onLayoutChanged)
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
                    if (searchLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                },
                singleLine = true,
                shape = RoundedCornerShape(NextcloudRadii.Medium),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchScopeControl(searchScope, onSearchScopeChanged)
                LayoutControl(layout, onLayoutChanged)
            }
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
private fun SearchScopeControl(
    scope: FileSearchScope,
    onChanged: (FileSearchScope) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(
            selected = scope == FileSearchScope.CurrentFolder,
            onClick = { onChanged(FileSearchScope.CurrentFolder) },
            label = { Text("Folder") },
        )
        FilterChip(
            selected = scope == FileSearchScope.AllFiles,
            onClick = { onChanged(FileSearchScope.AllFiles) },
            label = { Text("Everywhere") },
        )
    }
}

@Composable
private fun LayoutControl(layout: FileLayout, onChanged: (FileLayout) -> Unit) {
    Row {
        FileLayout.entries.forEach { candidate ->
            val icon = when (candidate) {
                FileLayout.List -> NextcloudIcons.ListView
                FileLayout.Grid -> NextcloudIcons.Apps
                FileLayout.Compact -> NextcloudIcons.Menu
            }
            IconButton(onClick = { onChanged(candidate) }) {
                Surface(
                    color = if (layout == candidate) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    shape = RoundedCornerShape(NextcloudRadii.Small),
                ) {
                    Icon(
                        icon,
                        contentDescription = "${candidate.name} view",
                        modifier = Modifier.padding(7.dp).size(18.dp),
                    )
                }
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

@Composable
private fun FilesNavigationPane(
    files: List<NextcloudFile>,
    currentPath: String,
    selectedFilter: FileWorkspaceFilter,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    onFilterChanged: (FileWorkspaceFilter) -> Unit,
    onOpenPath: (String) -> Unit,
) {
    val favoriteFolders = files.filter { it.isDirectory && it.favorite }.take(5)
    val pinnedFolders = files.filter {
        it.isDirectory && offlineAvailability[it.path] == FileOfflineAvailability.Available
    }.take(5)
    Column(
        modifier = Modifier.width(218.dp).fillMaxHeight().padding(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "LIBRARY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = NextcloudSpacing.Small, vertical = NextcloudSpacing.Small),
        )
        FilesNavItem(
            label = "All files",
            icon = NextcloudIcons.FolderOpen,
            selected = selectedFilter == FileWorkspaceFilter.All && currentPath.isEmpty(),
            count = files.size,
            onClick = {
                onFilterChanged(FileWorkspaceFilter.All)
                onOpenPath("")
            },
        )
        FilesNavItem(
            label = "Favorites",
            icon = NextcloudIcons.Favorite,
            selected = selectedFilter == FileWorkspaceFilter.Favorites,
            count = files.count(NextcloudFile::favorite),
            onClick = { onFilterChanged(FileWorkspaceFilter.Favorites) },
        )
        FilesNavItem(
            label = "Available offline",
            icon = NextcloudIcons.CheckCircle,
            selected = selectedFilter == FileWorkspaceFilter.Offline,
            count = offlineAvailability.values.count { it == FileOfflineAvailability.Available },
            onClick = { onFilterChanged(FileWorkspaceFilter.Offline) },
        )
        if (pinnedFolders.isNotEmpty()) {
            Spacer(Modifier.height(NextcloudSpacing.Medium))
            Text(
                "PINNED FOLDERS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Small),
            )
            pinnedFolders.forEach { file ->
                FilesNavItem(file.name, NextcloudIcons.Folder, currentPath == file.path, null) {
                    onFilterChanged(FileWorkspaceFilter.All)
                    onOpenPath(file.path)
                }
            }
        }
        if (favoriteFolders.isNotEmpty()) {
            Spacer(Modifier.height(NextcloudSpacing.Medium))
            Text(
                "FAVORITE FOLDERS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Small),
            )
            favoriteFolders.forEach { file ->
                FilesNavItem(file.name, NextcloudIcons.FavoriteBorder, currentPath == file.path, null) {
                    onFilterChanged(FileWorkspaceFilter.All)
                    onOpenPath(file.path)
                }
            }
        }
    }
}

@Composable
private fun FilesNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    count: Int?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NextcloudSpacing.Small, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            count?.takeIf { it > 0 }?.let {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
                    Text("$it", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun FilesBrowserPane(
    modifier: Modifier,
    files: List<NextcloudFile>,
    libraryFiles: List<NextcloudFile>,
    totalFilesInFolder: Int,
    listingSource: NextcloudFileListingSource?,
    query: String,
    searchScope: FileSearchScope,
    searchLoading: Boolean,
    searchError: String?,
    filter: FileWorkspaceFilter,
    onFilterChanged: (FileWorkspaceFilter) -> Unit,
    sortMode: FileSortMode,
    onSortModeChanged: (FileSortMode) -> Unit,
    sortDirection: FileSortDirection,
    onSortDirectionChanged: (FileSortDirection) -> Unit,
    layout: FileLayout,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    selectedFile: NextcloudFile?,
    onSelectedFileChanged: (NextcloudFile?) -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
    desktop: Boolean,
) {
    Column(modifier = modifier) {
        FilesFilterAndSortBar(
            files = libraryFiles,
            visibleCount = files.size,
            totalFilesInFolder = totalFilesInFolder,
            listingSource = listingSource,
            query = query,
            searchScope = searchScope,
            filter = filter,
            onFilterChanged = onFilterChanged,
            sortMode = sortMode,
            onSortModeChanged = onSortModeChanged,
            sortDirection = sortDirection,
            onSortDirectionChanged = onSortDirectionChanged,
            offlineAvailability = offlineAvailability,
        )
        searchError?.let {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large, vertical = 4.dp),
            ) {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(NextcloudSpacing.Medium),
                )
            }
        }
        when {
            searchScope == FileSearchScope.AllFiles && query.trim().length < 2 -> {
                FilesSearchPrompt()
            }
            searchLoading && files.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            files.isEmpty() -> {
                EmptyMessage(
                    if (query.isNotBlank()) "No files match \"${query.trim()}\"." else "No files match this view.",
                )
            }
            layout == FileLayout.Grid -> NativeFileWorkspaceGrid(
                files = files,
                offlineAvailability = offlineAvailability,
                offlineStorageSupported = offlineStorageSupported,
                fileSharing = fileSharing,
                externalHandoffCapability = externalHandoffCapability,
                services = services,
                session = session,
                userId = userId,
                selectedFile = selectedFile,
                onSelectedFileChanged = onSelectedFileChanged,
                onOpenPath = onOpenPath,
                onOpenFile = onOpenFile,
                onAction = onAction,
                desktop = desktop,
            )
            else -> NativeFileWorkspaceList(
                files = files,
                compact = layout == FileLayout.Compact,
                offlineAvailability = offlineAvailability,
                offlineStorageSupported = offlineStorageSupported,
                fileSharing = fileSharing,
                externalHandoffCapability = externalHandoffCapability,
                selectedFile = selectedFile,
                onSelectedFileChanged = onSelectedFileChanged,
                onOpenPath = onOpenPath,
                onOpenFile = onOpenFile,
                onAction = onAction,
                desktop = desktop,
            )
        }
    }
}

@Composable
private fun FilesFilterAndSortBar(
    files: List<NextcloudFile>,
    visibleCount: Int,
    totalFilesInFolder: Int,
    listingSource: NextcloudFileListingSource?,
    query: String,
    searchScope: FileSearchScope,
    filter: FileWorkspaceFilter,
    onFilterChanged: (FileWorkspaceFilter) -> Unit,
    sortMode: FileSortMode,
    onSortModeChanged: (FileSortMode) -> Unit,
    sortDirection: FileSortDirection,
    onSortDirectionChanged: (FileSortDirection) -> Unit,
    offlineAvailability: Map<String, FileOfflineAvailability>,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            FileWorkspaceFilter.entries.forEach { candidate ->
                val count = fileWorkspaceFilterCount(
                    files,
                    candidate,
                    offlineAvailability.filterValues { it == FileOfflineAvailability.Available }.keys,
                )
                FilterChip(
                    selected = filter == candidate,
                    onClick = { onFilterChanged(candidate) },
                    label = { Text("${candidate.readableLabel()}${if (count > 0 && candidate != FileWorkspaceFilter.All) " $count" else ""}") },
                )
            }
        }
        Box {
            OutlinedButton(onClick = { sortExpanded = true }) {
                Icon(NextcloudIcons.Filter, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(sortMode.readableLabel())
            }
            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                FileSortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.readableLabel()) },
                        onClick = {
                            onSortModeChanged(mode)
                            sortExpanded = false
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (sortDirection == FileSortDirection.Ascending) "Descending" else "Ascending") },
                    onClick = {
                        onSortDirectionChanged(
                            if (sortDirection == FileSortDirection.Ascending) FileSortDirection.Descending
                            else FileSortDirection.Ascending,
                        )
                        sortExpanded = false
                    },
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            when {
                searchScope == FileSearchScope.AllFiles && query.isNotBlank() -> "$visibleCount results across Nextcloud"
                listingSource == NextcloudFileListingSource.Cache -> "Cached · $visibleCount of $totalFilesInFolder items"
                else -> "$visibleCount of $totalFilesInFolder items"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilesSearchPrompt() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            modifier = Modifier.padding(NextcloudSpacing.XXLarge),
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                Icon(NextcloudIcons.Search, contentDescription = null, modifier = Modifier.padding(18.dp).size(32.dp))
            }
            Text("Search all files", style = MaterialTheme.typography.titleMedium)
            Text(
                "Enter at least 2 characters. Results include nested folders, file names, paths, types, and owners.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NativeFileWorkspaceList(
    files: List<NextcloudFile>,
    compact: Boolean,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    selectedFile: NextcloudFile?,
    onSelectedFileChanged: (NextcloudFile?) -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
    desktop: Boolean,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
    ) {
        if (desktop && !compact) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Name", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Text("Modified", modifier = Modifier.width(150.dp), style = MaterialTheme.typography.labelMedium)
                    Text("Size", modifier = Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(48.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        items(files, key = NextcloudFile::path) { file ->
            var menuExpanded by remember(file.path) { mutableStateOf(false) }
            val selected = selectedFile?.path == file.path
            val availability = offlineAvailability[file.path] ?: FileOfflineAvailability.OnlineOnly
            Surface(
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f) else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClickLabel = if (desktop) "Select ${file.name}" else primaryFileActionLabel(file),
                    onLongClickLabel = "Show actions for ${file.name}",
                    onClick = {
                        if (desktop && selected) {
                            if (file.isDirectory) onOpenPath(file.path) else onOpenFile(file)
                        } else if (desktop) onSelectedFileChanged(file)
                        else if (file.isDirectory) onOpenPath(file.path) else onOpenFile(file)
                    },
                    onLongClick = { menuExpanded = true },
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = NextcloudSpacing.Medium,
                        vertical = if (compact) 7.dp else 11.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    Surface(color = NextcloudTheme.colors.appIconContainer, shape = RoundedCornerShape(9.dp)) {
                        Icon(
                            if (file.isDirectory) NextcloudIcons.Folder else workspaceFileIcon(file),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(if (compact) 7.dp else 9.dp).size(if (compact) 18.dp else 22.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                file.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (file.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (file.favorite) {
                                Icon(
                                    NextcloudIcons.Favorite,
                                    contentDescription = "Favorite",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 6.dp).size(15.dp),
                                )
                            }
                        }
                        if (!compact) {
                            Text(
                                availability.readableStatus()
                                    ?: file.ownerDisplayName?.let { "Owned by $it" }
                                    ?: file.mimeType?.substringBefore(';')
                                    ?: if (file.isDirectory) "Folder" else "File",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (desktop && !compact) {
                        Text(
                            file.lastModified.readableFileDate(),
                            modifier = Modifier.width(150.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            if (file.isDirectory) "—" else formatWorkspaceBytes(file.size),
                            modifier = Modifier.width(90.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(NextcloudIcons.More, contentDescription = "Actions for ${file.name}")
                        }
                        FileActionMenu(
                            file = file,
                            offlineAvailability = availability,
                            offlineStorageSupported = offlineStorageSupported,
                            fileSharing = fileSharing,
                            externalHandoffCapability = externalHandoffCapability,
                            expanded = menuExpanded,
                            onDismiss = { menuExpanded = false },
                            onAction = { onAction(file, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeFileWorkspaceGrid(
    files: List<NextcloudFile>,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    selectedFile: NextcloudFile?,
    onSelectedFileChanged: (NextcloudFile?) -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
    desktop: Boolean,
) {
    LazyVerticalGrid(
        modifier = Modifier.fillMaxSize(),
        columns = GridCells.Adaptive(if (desktop) 160.dp else 132.dp),
        contentPadding = PaddingValues(NextcloudSpacing.Large),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        gridItems(files, key = NextcloudFile::path) { file ->
            NativeFileWorkspaceTile(
                file = file,
                selected = selectedFile?.path == file.path,
                availability = offlineAvailability[file.path] ?: FileOfflineAvailability.OnlineOnly,
                offlineStorageSupported = offlineStorageSupported,
                fileSharing = fileSharing,
                externalHandoffCapability = externalHandoffCapability,
                services = services,
                session = session,
                userId = userId,
                onClick = {
                    if (desktop) onSelectedFileChanged(file)
                    else if (file.isDirectory) onOpenPath(file.path) else onOpenFile(file)
                },
                onDoubleClick = if (desktop) {
                    { if (file.isDirectory) onOpenPath(file.path) else onOpenFile(file) }
                } else null,
                onAction = { onAction(file, it) },
            )
        }
    }
}

@Composable
private fun NativeFileWorkspaceTile(
    file: NextcloudFile,
    selected: Boolean,
    availability: FileOfflineAvailability,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)?,
    onAction: (FileMenuAction) -> Unit,
) {
    var menuExpanded by remember(file.path) { mutableStateOf(false) }
    var preview by remember(file.fileId, file.etag, file.hasPreview) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(session, userId, file.fileId, file.etag, file.hasPreview) {
        file.fileId ?: return@LaunchedEffect
        if (file.isDirectory || !file.isPhotoMedia()) return@LaunchedEffect
        preview = services.loadMediaThumbnailDecoded(
            session = session,
            userId = userId,
            file = file,
            width = 420,
            height = 300,
        ) { payload ->
            decodePlatformImage(payload.bytes, payload.kind.orientationPolicy())
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClickLabel = "Select ${file.name}",
            onLongClickLabel = "Show actions for ${file.name}",
            onClick = { if (selected && onDoubleClick != null) onDoubleClick() else onClick() },
            onLongClick = { menuExpanded = true },
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else NextcloudTheme.colors.appTile,
        ),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1.35f).background(NextcloudTheme.colors.appIconContainer),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Icon(
                if (file.isDirectory) NextcloudIcons.Folder else workspaceFileIcon(file),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp),
            )
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(NextcloudSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (file.favorite) {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shape = CircleShape) {
                        Icon(
                            NextcloudIcons.Favorite,
                            contentDescription = "Favorite",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(8.dp).size(17.dp),
                        )
                    }
                }
                Box {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shape = CircleShape) {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(34.dp)) {
                            Icon(NextcloudIcons.More, contentDescription = "Actions for ${file.name}")
                        }
                    }
                    FileActionMenu(
                        file = file,
                        offlineAvailability = availability,
                        offlineStorageSupported = offlineStorageSupported,
                        fileSharing = fileSharing,
                        externalHandoffCapability = externalHandoffCapability,
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onAction = onAction,
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(NextcloudSpacing.Medium)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Text(
                availability.readableStatus() ?: if (file.isDirectory) "Folder" else formatWorkspaceBytes(file.size),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilesInspector(
    file: NextcloudFile?,
    offlineAvailability: FileOfflineAvailability?,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    onOpen: (NextcloudFile) -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
) {
    Column(
        modifier = Modifier.width(304.dp).fillMaxHeight().padding(NextcloudSpacing.Large).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        if (file == null) {
            Text("Details", style = MaterialTheme.typography.titleMedium)
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = RoundedCornerShape(NextcloudRadii.Card)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.XLarge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    Icon(NextcloudIcons.Info, contentDescription = null, modifier = Modifier.size(30.dp))
                    Text("Select an item", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "See a preview, location, owner, offline state, sharing, and version actions here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }
        var menuExpanded by remember(file.path) { mutableStateOf(false) }
        var preview by remember(file.fileId, file.etag, file.hasPreview) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(session, userId, file.fileId, file.etag, file.hasPreview) {
            file.fileId ?: return@LaunchedEffect
            if (file.isDirectory || !file.isPhotoMedia()) return@LaunchedEffect
            preview = services.loadMediaThumbnailDecoded(
                session = session,
                userId = userId,
                file = file,
                width = 620,
                height = 420,
            ) { payload ->
                decodePlatformImage(payload.bytes, payload.kind.orientationPolicy())
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(NextcloudIcons.More, contentDescription = "Actions for ${file.name}")
                }
                FileActionMenu(
                    file = file,
                    offlineAvailability = offlineAvailability ?: FileOfflineAvailability.OnlineOnly,
                    offlineStorageSupported = offlineStorageSupported,
                    fileSharing = fileSharing,
                    externalHandoffCapability = externalHandoffCapability,
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    onAction = { onAction(file, it) },
                )
            }
        }
        Surface(
            color = NextcloudTheme.colors.appIconContainer,
            shape = RoundedCornerShape(NextcloudRadii.Card),
            modifier = Modifier.fillMaxWidth().aspectRatio(1.55f).clip(RoundedCornerShape(NextcloudRadii.Card)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                preview?.let {
                    Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } ?: Icon(
                    if (file.isDirectory) NextcloudIcons.Folder else workspaceFileIcon(file),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(46.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (file.favorite) Icon(NextcloudIcons.Favorite, "Favorite", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                file.mimeType?.substringBefore(';') ?: if (file.isDirectory) "Folder" else "File",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            FilledTonalButton(onClick = { onOpen(file) }, modifier = Modifier.weight(1f)) {
                Text(if (file.isDirectory) "Open" else "Preview")
            }
            IconButton(onClick = {
                onAction(file, if (file.favorite) FileMenuAction.RemoveFavorite else FileMenuAction.AddFavorite)
            }) {
                Icon(
                    if (file.favorite) NextcloudIcons.Favorite else NextcloudIcons.FavoriteBorder,
                    contentDescription = if (file.favorite) "Remove favorite" else "Add favorite",
                )
            }
            if (fileSharing.apiEnabled) {
                IconButton(onClick = { onAction(file, FileMenuAction.Share) }) {
                    Icon(NextcloudIcons.People, contentDescription = "Share ${file.name}")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        InspectorProperty("Location", "/${file.path}")
        InspectorProperty("Modified", file.lastModified.readableFileDate())
        if (!file.isDirectory) InspectorProperty("Size", formatWorkspaceBytes(file.size))
        file.ownerDisplayName?.let { InspectorProperty("Owner", it) }
        offlineAvailability?.readableStatus()?.let { InspectorProperty("Offline", it) }
        if (file.unreadComments > 0) InspectorProperty("Comments", "${file.unreadComments} unread")
        if (!file.isDirectory && file.fileId != null) {
            TextButton(onClick = { onAction(file, FileMenuAction.VersionHistory) }) {
                Text("View version history")
            }
        }
    }
}

@Composable
private fun InspectorProperty(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(74.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun FileWorkspaceFilter.readableLabel(): String = when (this) {
    FileWorkspaceFilter.All -> "All"
    FileWorkspaceFilter.Favorites -> "Favorites"
    FileWorkspaceFilter.Folders -> "Folders"
    FileWorkspaceFilter.Documents -> "Documents"
    FileWorkspaceFilter.Media -> "Media"
    FileWorkspaceFilter.Offline -> "Offline"
}

private fun FileSortMode.readableLabel(): String = when (this) {
    FileSortMode.Name -> "Name"
    FileSortMode.Modified -> "Modified"
    FileSortMode.Size -> "Size"
}

private fun workspaceFileIcon(file: NextcloudFile): ImageVector = when {
    file.mimeType?.startsWith("image/") == true -> NextcloudIcons.Image
    file.mimeType?.startsWith("video/") == true -> NextcloudIcons.Video
    else -> NextcloudIcons.File
}

private fun formatWorkspaceBytes(bytes: Long?): String = when {
    bytes == null -> "Unknown size"
    bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

private fun String?.readableFileDate(): String = this
    ?.removeSuffix(" GMT")
    ?.replace('T', ' ')
    ?.substringBefore('+')
    ?.removeSuffix("Z")
    ?.takeIf(String::isNotBlank)
    ?: "—"
