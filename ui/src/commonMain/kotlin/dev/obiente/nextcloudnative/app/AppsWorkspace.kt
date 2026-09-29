package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions

@Composable
internal fun NativeAppsWorkspace(
    serverInfo: NextcloudServerInfo?,
    error: String?,
    lastOpenedAppId: String?,
    pinnedAppIds: List<String> = defaultAppWorkspacePinnedIds(),
    pinnedAppsError: String? = null,
    onTogglePinnedApp: (String) -> String? = { null },
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onOpenApp: (NextcloudAppEntry) -> Unit,
) {
    val desktop = LocalNextcloudWorkspaceCapabilities.current.isDesktop
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategoryName by rememberSaveable { mutableStateOf(AppWorkspaceCategory.All.name) }
    var pinError by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCategory = AppWorkspaceCategory.entries.firstOrNull { it.name == selectedCategoryName }
        ?: AppWorkspaceCategory.All
    val presentation = remember(serverInfo?.apps, lastOpenedAppId, pinnedAppIds, query, selectedCategory) {
        buildAppWorkspacePresentation(
            apps = serverInfo?.apps.orEmpty(),
            lastOpenedAppId = lastOpenedAppId,
            pinnedAppIds = pinnedAppIds,
            query = query,
            category = selectedCategory,
        )
    }
    var selectedAppId by remember(serverInfo?.apps) { mutableStateOf<String?>(null) }
    val selectedEntry = presentation.entries.firstOrNull { it.app.id == selectedAppId }
    val canPinMore = pinnedAppIds.size < MAX_APP_WORKSPACE_PINS
    val togglePinnedApp: (String) -> Unit = { appId ->
        pinError = onTogglePinnedApp(appId)
    }
    selectedEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { selectedAppId = null },
            title = { Text(entry.app.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (entry.description.isNotBlank()) Text(entry.description)
                    Text(entry.category.title, color = MaterialTheme.colorScheme.primary)
                    Text(if (entry.nativeWorkspace) "Dedicated native workspace" else "Available actions are checked when opened")
                    if (entry.pinned) Text("Pinned to shortcuts")
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedAppId = null; onOpenApp(entry.app) }) { Text("Open app") }
            },
            dismissButton = { TextButton(onClick = { selectedAppId = null }) { Text("Close") } },
        )
    }

    if (desktop) {
        DesktopAppsWorkspace(
            serverInfo = serverInfo,
            error = error,
            query = query,
            category = selectedCategory,
            presentation = presentation,
            onQueryChanged = { query = it },
            onCategorySelected = { selectedCategoryName = it.name },
            onSelected = { selectedAppId = it.app.id },
            onRetry = onRetry,
            onSettings = onSettings,
            onSearch = onSearch,
            onOpenApp = onOpenApp,
            pinError = pinError ?: pinnedAppsError,
            canPinMore = canPinMore,
            onTogglePinnedApp = togglePinnedApp,
        )
    } else {
        CompactAppsWorkspace(
            serverInfo = serverInfo,
            error = error,
            query = query,
            category = selectedCategory,
            onCategorySelected = { selectedCategoryName = it.name },
            presentation = presentation,
            onQueryChanged = { query = it },
            onRetry = onRetry,
            onSettings = onSettings,
            onSearch = onSearch,
            onOpenApp = onOpenApp,
            onSelected = { selectedAppId = it.app.id },
            pinError = pinError ?: pinnedAppsError,
            canPinMore = canPinMore,
            onTogglePinnedApp = togglePinnedApp,
        )
    }
}

@Composable
private fun DesktopAppsWorkspace(
    serverInfo: NextcloudServerInfo?,
    error: String?,
    query: String,
    category: AppWorkspaceCategory,
    presentation: AppWorkspacePresentation,
    pinError: String?,
    canPinMore: Boolean,
    onQueryChanged: (String) -> Unit,
    onCategorySelected: (AppWorkspaceCategory) -> Unit,
    onSelected: (AppWorkspaceEntry) -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onOpenApp: (NextcloudAppEntry) -> Unit,
    onTogglePinnedApp: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        WorkspaceHeader(
            title = "Apps",
            subtitle = "Open, continue, and manage everything connected to your cloud",
            onSettings = onSettings,
            onSearch = onSearch,
        )
        when {
            error != null -> AppsErrorState(error, onRetry)
            serverInfo == null -> AppsLoadingState()
            else -> {
                AppsToolbar(
                    query = query,
                    categories = presentation.visibleCategories,
                    selectedCategory = category,
                    totalCount = presentation.totalCount,
                    onQueryChanged = onQueryChanged,
                    onCategorySelected = onCategorySelected,
                )
                pinError?.let { AppsPinError(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(250.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        if (query.isBlank() && category == AppWorkspaceCategory.All &&
                            presentation.recentEntries.isNotEmpty()
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                AppsSectionHeader(
                                    title = "Continue working",
                                    detail = "Recent workspaces from this account",
                                )
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                ) {
                                    presentation.recentEntries.forEach { entry ->
                                        RecentAppCard(
                                            entry = entry,
                                            onOpen = { onOpenApp(entry.app) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                AppsSectionHeader(
                                    title = "All installed apps",
                                    detail = "${presentation.totalCount} available on ${serverInfo.themeName ?: "this server"}",
                                    modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                                )
                            }
                        }
                        if (presentation.entries.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                AppsEmptyState(query = query, category = category)
                            }
                        } else {
                            items(presentation.entries, key = { it.app.id }) { entry ->
                                AppWorkspaceCard(
                                    entry = entry,
                                    selected = false,
                                    onSelect = { onSelected(entry) },
                                    onOpen = { onOpenApp(entry.app) },
                                    onTogglePinned = { onTogglePinnedApp(entry.app.id) },
                                    canPin = canPinMore,
                                    primaryActionLabel = "Open ${entry.app.name}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAppsWorkspace(
    serverInfo: NextcloudServerInfo?,
    error: String?,
    query: String,
    category: AppWorkspaceCategory,
    onCategorySelected: (AppWorkspaceCategory) -> Unit,
    presentation: AppWorkspacePresentation,
    pinError: String?,
    canPinMore: Boolean,
    onQueryChanged: (String) -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onOpenApp: (NextcloudAppEntry) -> Unit,
    onSelected: (AppWorkspaceEntry) -> Unit,
    onTogglePinnedApp: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        WorkspaceHeader("Apps", if (serverInfo == null) "Your cloud apps" else appWorkspaceCountLabel(presentation.totalCount),
            onSettings, onSearch, showGlobalSearch = false)
        when {
            error != null -> AppsErrorState(error, onRetry)
            serverInfo == null -> AppsLoadingState()
            else -> {
                pinError?.let { AppsPinError(it) }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(320.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = onQueryChanged,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search apps" },
                            leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                            placeholder = { Text("Find an app") },
                            singleLine = true,
                            shape = RoundedCornerShape(NextcloudRadii.Card),
                        )
                    }
                    item(key = "categories", span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            items(presentation.visibleCategories, key = AppWorkspaceCategory::name) { item ->
                                FilterChip(selected = category == item, onClick = { onCategorySelected(item) },
                                    label = { Text(item.title) })
                            }
                        }
                    }
                    if (query.isBlank() && category == AppWorkspaceCategory.All &&
                        presentation.pinnedEntries.isNotEmpty()
                    ) {
                        item(key = "shortcuts", span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                                Text("Pinned", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                                    items(presentation.pinnedEntries, key = { it.app.id }) { entry ->
                                        RecentAppCard(entry, { onOpenApp(entry.app) }, Modifier.width(200.dp))
                                    }
                                }
                            }
                        }
                    }
                    item(key = "results", span = { GridItemSpan(maxLineSpan) }) {
                        Text(if (query.isBlank()) category.title else "${appWorkspaceCountLabel(presentation.entries.size)} found",
                            modifier = Modifier.padding(top = NextcloudSpacing.Small),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (presentation.entries.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            AppsEmptyState(query, category)
                        }
                    }
                    items(presentation.entries, key = { it.app.id }) { entry ->
                        CompactAppRow(entry, { onSelected(entry) }, { onOpenApp(entry.app) },
                            { onTogglePinnedApp(entry.app.id) }, canPinMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAppRow(
    entry: AppWorkspaceEntry,
    onDetails: () -> Unit,
    onOpen: () -> Unit,
    onTogglePinned: () -> Unit,
    canPin: Boolean,
) {
    var expanded by remember(entry.app.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = onOpen, onShowActions = { expanded = true },
            openLabel = "Open ${entry.app.name}", actionsLabel = "Actions for ${entry.app.name}"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(entry.app.id, Modifier.size(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.app.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.description.isNotBlank()) {
                    Text(entry.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            NextcloudCardOverflow(itemLabel = entry.app.name,
                actions = listOf(
                    NextcloudCardAction("App details", "app-details", onClick = onDetails),
                    NextcloudCardAction(if (entry.pinned) "Unpin from shortcuts" else "Pin to shortcuts",
                        if (entry.pinned) "unpin-app" else "pin-app", enabled = entry.pinned || canPin,
                        onClick = onTogglePinned)),
                expanded = expanded, onExpandedChange = { expanded = it })
        }
    }
}

@Composable
private fun WorkspaceHeader(
    title: String,
    subtitle: String,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    showGlobalSearch: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(
            horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showGlobalSearch) IconButton(onClick = onSearch) {
            Icon(NextcloudIcons.Search, contentDescription = "Search Nextcloud")
        }
        IconButton(onClick = onSettings) {
            Icon(NextcloudIcons.Settings, contentDescription = "Settings")
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun AppsToolbar(
    query: String,
    categories: List<AppWorkspaceCategory>,
    selectedCategory: AppWorkspaceCategory,
    totalCount: Int,
    onQueryChanged: (String) -> Unit,
    onCategorySelected: (AppWorkspaceCategory) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f).height(52.dp).semantics { contentDescription = "Search apps" },
                leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                placeholder = { Text("Search apps, categories, and capabilities") },
                singleLine = true,
                shape = RoundedCornerShape(NextcloudRadii.Medium),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(NextcloudRadii.Medium),
            ) {
                Text(
                    "$totalCount installed",
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Medium, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            items(categories, key = AppWorkspaceCategory::name) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { onCategorySelected(category) },
                    label = { Text(category.title) },
                )
            }
        }
    }
}

@Composable
private fun AppsSectionHeader(title: String, detail: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentAppCard(entry: AppWorkspaceEntry, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(entry.app.id, modifier = Modifier.size(38.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.app.name, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    entry.category.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(NextcloudIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AppWorkspaceCard(
    entry: AppWorkspaceEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onTogglePinned: () -> Unit,
    canPin: Boolean,
    primaryActionLabel: String,
) {
    var actionsExpanded by remember(entry.app.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.nextcloudCardInteractions(
            onOpen = onOpen,
            onShowActions = { actionsExpanded = true },
            openLabel = primaryActionLabel,
            actionsLabel = "Actions for ${entry.app.name}",
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(entry.app.id, modifier = Modifier.size(40.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.app.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.category.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NextcloudCardOverflow(
                    itemLabel = entry.app.name,
                    actions = listOf(
                        NextcloudCardAction(label = "App details", semanticId = "app-details", onClick = onSelect),
                        NextcloudCardAction(
                            label = if (entry.pinned) "Unpin from shortcuts" else "Pin to shortcuts",
                            semanticId = if (entry.pinned) "unpin-app" else "pin-app",
                            enabled = entry.pinned || canPin,
                            onClick = onTogglePinned,
                        ),
                    ),
                    expanded = actionsExpanded,
                    onExpandedChange = { actionsExpanded = it },
                )
            }
            if (entry.description.isNotBlank()) Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                if (entry.pinned) AppStatusPill("Pinned")
            }
        }
    }
}

@Composable
private fun AppsPinError(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().semantics {
            liveRegion = LiveRegionMode.Assertive
        },
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}


@Composable
private fun AppIcon(appId: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = NextcloudTheme.colors.appIconContainer, shape = RoundedCornerShape(10.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                NextcloudIcons.app(appId),
                contentDescription = null,
                tint = NextcloudTheme.colors.appIcon,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun AppStatusPill(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = CircleShape) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
private fun AppsLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Loading installed apps...", modifier = Modifier.padding(top = NextcloudSpacing.Medium))
        }
    }
}

@Composable
private fun AppsErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(NextcloudIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(NextcloudSpacing.Medium))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun AppsEmptyState(query: String, category: AppWorkspaceCategory) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.XLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(NextcloudIcons.Search, contentDescription = null)
            Text(
                if (query.isNotBlank()) "No app matches \"$query\"." else "No apps in ${category.title.lowercase()}.",
                modifier = Modifier.padding(top = NextcloudSpacing.Small),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
