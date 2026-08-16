package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Composable
internal fun NativeAppsWorkspace(
    serverInfo: NextcloudServerInfo?,
    error: String?,
    lastOpenedAppId: String?,
    pinnedAppIds: List<String> = defaultAppWorkspacePinnedIds(),
    onTogglePinnedApp: (String) -> Unit = {},
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onOpenApp: (NextcloudAppEntry) -> Unit,
) {
    val desktop = LocalNextcloudWorkspaceCapabilities.current.isDesktop
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategoryName by rememberSaveable { mutableStateOf(AppWorkspaceCategory.All.name) }
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
    var selectedAppId by rememberSaveable(serverInfo?.apps) {
        mutableStateOf(lastOpenedAppId?.takeIf { id -> presentation.entries.any { it.app.id == id } })
    }
    val selectedEntry = presentation.entries.firstOrNull { it.app.id == selectedAppId }
        ?: presentation.recentEntries.firstOrNull()
        ?: presentation.entries.firstOrNull()

    if (desktop) {
        DesktopAppsWorkspace(
            serverInfo = serverInfo,
            error = error,
            query = query,
            category = selectedCategory,
            presentation = presentation,
            selectedEntry = selectedEntry,
            onQueryChanged = { query = it },
            onCategorySelected = { selectedCategoryName = it.name },
            onSelected = { selectedAppId = it.app.id },
            onRetry = onRetry,
            onSettings = onSettings,
            onSearch = onSearch,
            onOpenApp = onOpenApp,
            onTogglePinnedApp = onTogglePinnedApp,
        )
    } else {
        CompactAppsWorkspace(
            serverInfo = serverInfo,
            error = error,
            query = query,
            presentation = presentation,
            onQueryChanged = { query = it },
            onRetry = onRetry,
            onSettings = onSettings,
            onSearch = onSearch,
            onOpenApp = onOpenApp,
            onTogglePinnedApp = onTogglePinnedApp,
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
    selectedEntry: AppWorkspaceEntry?,
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
                                    selected = selectedEntry?.app?.id == entry.app.id,
                                    onSelect = { onSelected(entry) },
                                    onOpen = { onOpenApp(entry.app) },
                                    onTogglePinned = { onTogglePinnedApp(entry.app.id) },
                                )
                            }
                        }
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AppWorkspaceInspector(
                        selectedEntry = selectedEntry,
                        presentation = presentation,
                        serverInfo = serverInfo,
                        onOpen = { selectedEntry?.let { onOpenApp(it.app) } },
                        modifier = Modifier.widthIn(min = 292.dp, max = 332.dp).fillMaxHeight(),
                    )
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
    presentation: AppWorkspacePresentation,
    onQueryChanged: (String) -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onOpenApp: (NextcloudAppEntry) -> Unit,
    onTogglePinnedApp: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        WorkspaceHeader("Apps", "Everything connected to your cloud", onSettings, onSearch)
        when {
            error != null -> AppsErrorState(error, onRetry)
            serverInfo == null -> AppsLoadingState()
            else -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium)
                        .semantics { contentDescription = "Search apps" },
                    leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                    placeholder = { Text("Find an app or workspace") },
                    singleLine = true,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(220.dp),
                    contentPadding = PaddingValues(NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    items(presentation.entries, key = { it.app.id }) { entry ->
                        AppWorkspaceCard(
                            entry = entry,
                            selected = false,
                            onSelect = { onOpenApp(entry.app) },
                            onOpen = { onOpenApp(entry.app) },
                            onTogglePinned = { onTogglePinnedApp(entry.app.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(
    title: String,
    subtitle: String,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = NextcloudSpacing.Large),
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
        IconButton(onClick = onSearch) {
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
                Text(entry.app.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    entry.category.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
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
) {
    Card(
        onClick = onSelect,
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.category.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Open")
                }
                IconButton(onClick = onTogglePinned) {
                    Icon(
                        if (entry.pinned) NextcloudIcons.Favorite else NextcloudIcons.FavoriteBorder,
                        contentDescription = if (entry.pinned) {
                            "Unpin ${entry.app.name}"
                        } else {
                            "Pin ${entry.app.name}"
                        },
                    )
                }
            }
            Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                AppStatusPill(if (entry.nativeWorkspace) "Native workspace" else "Adaptive workspace")
                if (entry.pinned) AppStatusPill("Pinned")
            }
        }
    }
}

@Composable
private fun AppWorkspaceInspector(
    selectedEntry: AppWorkspaceEntry?,
    presentation: AppWorkspacePresentation,
    serverInfo: NextcloudServerInfo,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Text("Workspace overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            serverInfo.themeName ?: "Nextcloud",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            AppMetric("Apps", presentation.totalCount.toString(), Modifier.weight(1f))
            AppMetric("Native", presentation.nativeWorkspaceCount.toString(), Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        selectedEntry?.let { entry ->
            AppIcon(entry.app.id, modifier = Modifier.size(54.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.app.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    entry.category.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("Open ${entry.app.name}")
            }
            SettingsLikeFact(
                "Experience",
                if (entry.nativeWorkspace) "Dedicated native workspace" else "Adaptive workspace checked when opened",
            )
            SettingsLikeFact("Account", serverInfo.displayName)
            SettingsLikeFact("Server", serverInfo.version?.let { "Nextcloud $it" } ?: "Connected")
            if (entry.pinned) SettingsLikeFact("Sidebar", "Pinned shortcut")
            if (entry.recent) SettingsLikeFact("Recent", "Continue where you left off")
        }
        Spacer(Modifier.weight(1f))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(8.dp).clip(CircleShape)
                            .background(NextcloudTheme.colors.success),
                    )
                    Text(" Connected", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    "Installed apps and workspace contracts are scoped to this account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
private fun AppMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp)) {
        Column(modifier = Modifier.padding(NextcloudSpacing.Medium)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsLikeFact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
