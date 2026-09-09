package dev.obiente.nextcloudnative.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
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
    var navigationCollapsed by remember { mutableStateOf(false) }
    var inspectorClosed by remember { mutableStateOf(false) }
    val desktopPresentation = LocalNextcloudWorkspaceCapabilities.current.isDesktop
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panes = resolveFilesWorkspacePanes(
            maxWidth.value.toInt(), desktopPresentation, navigationCollapsed,
            inspectorClosed, selectedFile != null,
        )
        val desktop = panes.desktop
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
                paneActions = {
                    FilesPaneControls(
                        panes = panes,
                        hasSelection = selectedFile != null,
                        onToggleNavigation = {
                            navigationCollapsed = panes.showNavigation
                            if (!panes.showNavigation) inspectorClosed = true
                        },
                        onToggleInspector = { inspectorClosed = panes.showInspector },
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (desktop) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (panes.showNavigation) {
                        FilesNavigationPane(
                            files = navigationFiles,
                            currentPath = path,
                            selectedFilter = filter,
                            offlineAvailability = offlineAvailability,
                            onFilterChanged = onFilterChanged,
                            onOpenPath = onOpenPath,
                        )
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
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
                    AnimatedVisibility(visible = panes.showInspector) {
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
                            onClose = { inspectorClosed = true },
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
private fun FilesNavigationPane(
    files: List<NextcloudFile>,
    currentPath: String,
    selectedFilter: FileWorkspaceFilter,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    onFilterChanged: (FileWorkspaceFilter) -> Unit,
    onOpenPath: (String) -> Unit,
) {
    val pinnedFolders = files.filter {
        it.isDirectory && offlineAvailability[it.path] == FileOfflineAvailability.Available
    }.take(5)
    val pinnedPaths = pinnedFolders.mapTo(mutableSetOf(), NextcloudFile::path)
    val favoriteFolders = files.filter { it.isDirectory && it.favorite && it.path !in pinnedPaths }.take(5)
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
                "OFFLINE FOLDERS",
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
                "Enter at least 2 characters. Results include nested files and folders matching a name.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
