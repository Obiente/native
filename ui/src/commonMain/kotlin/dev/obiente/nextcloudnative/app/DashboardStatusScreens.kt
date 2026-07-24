package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

internal sealed interface DashboardSurfaceState {
    data object Loading : DashboardSurfaceState
    data class Available(
        val snapshot: NativeDashboardSnapshot,
        val status: NativeUserStatus?,
    ) : DashboardSurfaceState
    data class Failed(val message: String) : DashboardSurfaceState
}

@Composable
internal fun NativeDashboardScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    installedApps: List<NextcloudAppEntry>,
    onOpenApp: (NextcloudAppEntry) -> Unit,
    onOpenStatus: (() -> Unit)?,
    onBack: (() -> Unit)?,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    var refreshAttempt by remember(session) { mutableStateOf(0) }
    var customizeWorkspace by remember(session) { mutableStateOf(false) }
    var workspacePersistenceError by remember(session) { mutableStateOf<String?>(null) }
    val state = rememberNativeDashboardState(
        services = services,
        session = session,
        refreshAttempt = refreshAttempt,
    )
    val formFactor = rememberHomeFormFactor()
    val workspaceStorage = rememberHomeWorkspaceLayoutStorage()
    val workspaceRepository = remember(workspaceStorage) {
        HomeWorkspaceLayoutRepository(workspaceStorage)
    }
    val workspaceScope = remember(session.serverUrl, session.loginName, formFactor) {
        HomeWorkspaceScope(
            accountScopeDigest = previewCacheDigest(session),
            formFactor = formFactor,
        )
    }
    var workspaceLayout by remember(workspaceScope) {
        mutableStateOf(workspaceRepository.load(workspaceScope))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DashboardHeader(
            title = "Home",
            subtitle = "Your cloud at a glance",
            onBack = onBack,
            onRefresh = { refreshAttempt += 1 },
            onCustomize = { customizeWorkspace = true },
            onSearch = onSearch,
            onSettings = onSettings,
        )
        when (val current = state) {
            DashboardSurfaceState.Loading -> DashboardLoading()
            is DashboardSurfaceState.Failed -> DashboardFailure(
                message = current.message,
                onRetry = { refreshAttempt += 1 },
            )
            is DashboardSurfaceState.Available -> {
                val bindings = remember(current.snapshot.widgets) {
                    homeDashboardWidgetBindings(current.snapshot.widgets)
                }
                val availableSectionIds = remember(bindings) {
                    buildList {
                        add(HomeSectionIds.QuickActions)
                        bindings.forEach { binding ->
                            if (size < MAX_HOME_WORKSPACE_SECTIONS) add(binding.sectionId)
                        }
                    }
                }
                val effectiveLayout = remember(workspaceLayout, availableSectionIds) {
                    workspaceLayout.reconcileAvailableSections(availableSectionIds)
                }
                LaunchedEffect(effectiveLayout) {
                    if (effectiveLayout != workspaceLayout) {
                        workspaceLayout = effectiveLayout
                        workspaceRepository.save(effectiveLayout)
                    }
                }
                val bindingsBySection = remember(bindings) {
                    bindings.associateBy(HomeDashboardWidgetBinding::sectionId)
                }
                current.status?.let { status ->
                    CurrentStatusStrip(status = status, onClick = onOpenStatus)
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(330.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(NextcloudSpacing.XLarge),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                ) {
                    items(
                        effectiveLayout.visibleSections,
                        key = { section -> section.id.value },
                    ) { section ->
                        when (section.id) {
                            HomeSectionIds.QuickActions -> DashboardQuickActionsCard(
                                installedApps = installedApps,
                                onOpenApp = onOpenApp,
                            )

                            else -> bindingsBySection[section.id]?.let { binding ->
                                DashboardWidgetCard(
                                    widget = binding.widget,
                                    items = current.snapshot.itemsByWidget[binding.widget.id].orEmpty(),
                                    size = section.size,
                                    onOpenLink = { link ->
                                        val appId = dashboardAppIdForLink(session, link)
                                        val nativeApp = installedApps.firstOrNull { it.id == appId }
                                        if (nativeApp != null) {
                                            onOpenApp(nativeApp)
                                        } else {
                                            services.openExternalUrl(dashboardBrowserUrl(session, link))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                if (customizeWorkspace) {
                    HomeWorkspaceCustomizerDialog(
                        layout = effectiveLayout,
                        sectionLabels = buildMap {
                            put(HomeSectionIds.QuickActions, "Quick actions")
                            bindings.forEach { put(it.sectionId, it.widget.title) }
                        },
                        persistenceError = workspacePersistenceError,
                        onDismiss = {
                            customizeWorkspace = false
                            workspacePersistenceError = null
                        },
                        onSave = { updated ->
                            workspaceLayout = updated
                            if (workspaceRepository.save(updated)) {
                                customizeWorkspace = false
                                workspacePersistenceError = null
                            } else {
                                workspacePersistenceError =
                                    "Your changes are active, but could not be saved on this device."
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun rememberNativeDashboardState(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    refreshAttempt: Int,
): DashboardSurfaceState {
    var state by remember(session) {
        mutableStateOf<DashboardSurfaceState>(DashboardSurfaceState.Loading)
    }
    LaunchedEffect(session, refreshAttempt) {
        val now = currentDashboardEpochSeconds()
        sharedDashboardStatusMemoryCache.get(session, now)?.let { cached ->
            state = DashboardSurfaceState.Available(cached.dashboard, cached.status)
        }
        runCatching {
            coroutineScope {
                val widgetsDeferred = async {
                    parseDashboardWidgets(
                        services.executeNextcloudApi(session, dashboardWidgetsRequest()),
                    )
                }
                val statusDeferred = async {
                    runCatching {
                        parseCurrentUserStatus(
                            services.executeNextcloudApi(session, currentUserStatusRequest()),
                        )
                    }.getOrNull()
                }
                val widgets = widgetsDeferred.await()
                val items = parseDashboardItems(
                    services.executeNextcloudApi(session, dashboardItemsRequest()),
                    widgets,
                )
                NativeDashboardSnapshot(widgets, items) to statusDeferred.await()
            }
        }.onSuccess { (snapshot, status) ->
            sharedDashboardStatusMemoryCache.store(
                session = session,
                dashboard = snapshot,
                status = status,
                nowEpochSeconds = currentDashboardEpochSeconds(),
            )
            state = DashboardSurfaceState.Available(snapshot, status)
        }.onFailure { failure ->
            if (state !is DashboardSurfaceState.Available) {
                state = DashboardSurfaceState.Failed(
                    failure.message ?: "The dashboard could not be loaded.",
                )
            }
        }
    }
    return state
}

@Composable
private fun DashboardHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onCustomize: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Medium,
            vertical = NextcloudSpacing.Small,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(NextcloudIcons.Back, contentDescription = "Back")
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onCustomize != null) {
            IconButton(onClick = onCustomize) {
                Icon(NextcloudIcons.Edit, contentDescription = "Customize home")
            }
        }
        if (onSearch != null) {
            IconButton(onClick = onSearch) {
                Icon(NextcloudIcons.Search, contentDescription = "Search Nextcloud")
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(NextcloudIcons.Refresh, contentDescription = dashboardRefreshDescription(title))
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(NextcloudIcons.Settings, contentDescription = "Settings")
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

internal fun dashboardRefreshDescription(title: String): String = "Refresh $title"

@Composable
private fun CurrentStatusStrip(
    status: NativeUserStatus,
    onClick: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = NextcloudSpacing.XLarge,
                top = NextcloudSpacing.Large,
                end = NextcloudSpacing.XLarge,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            StatusPresenceDot(status.presence)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    status.message?.let { message ->
                        listOfNotNull(status.icon, message).joinToString(" ")
                    } ?: status.presence.displayLabel(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Status · ${status.presence.displayLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClick != null) {
                Icon(NextcloudIcons.Edit, contentDescription = "Edit status")
            }
        }
    }
}

internal data class HomeDashboardWidgetBinding(
    val sectionId: HomeSectionId,
    val widget: NativeDashboardWidget,
)

internal fun homeDashboardWidgetBindings(
    widgets: List<NativeDashboardWidget>,
): List<HomeDashboardWidgetBinding> {
    val occupied = mutableSetOf<HomeSectionId>()
    return widgets.map { widget ->
        val sectionId = widget.availableHomeSectionId(occupied)
        occupied += sectionId
        HomeDashboardWidgetBinding(sectionId = sectionId, widget = widget)
    }
}

@Composable
private fun DashboardQuickActionsCard(
    installedApps: List<NextcloudAppEntry>,
    onOpenApp: (NextcloudAppEntry) -> Unit,
) {
    val quickApps = remember(installedApps) {
        installedApps
            .filter { it.id in DASHBOARD_QUICK_ACTION_APP_IDS }
            .sortedBy { DASHBOARD_QUICK_ACTION_APP_IDS.indexOf(it.id) }
            .take(MAX_DASHBOARD_QUICK_ACTIONS)
    }
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large)) {
            Text(
                "Quick actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (quickApps.isEmpty()) {
                Text(
                    "Your shortcuts will appear as native apps become available.",
                    modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    items(quickApps, key = NextcloudAppEntry::id) { app ->
                        FilterChip(
                            selected = false,
                            onClick = { onOpenApp(app) },
                            label = { Text(app.name, maxLines = 1) },
                            leadingIcon = {
                                Icon(
                                    NextcloudIcons.app(app.id),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeWorkspaceCustomizerDialog(
    layout: HomeWorkspaceLayout,
    sectionLabels: Map<HomeSectionId, String>,
    persistenceError: String?,
    onDismiss: () -> Unit,
    onSave: (HomeWorkspaceLayout) -> Unit,
) {
    var draft by remember(layout) { mutableStateOf(layout) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customize home") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                persistenceError?.let { message ->
                    item {
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
                items(draft.sections, key = { section -> section.id.value }) { section ->
                    val index = draft.sections.indexOfFirst { it.id == section.id }
                    Surface(
                        color = NextcloudTheme.colors.appTile,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    sectionLabels[section.id] ?: "Dashboard section",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    enabled = index > 0,
                                    onClick = { draft = draft.move(section.id, index - 1) },
                                ) {
                                    Text("Up")
                                }
                                TextButton(
                                    enabled = index < draft.sections.lastIndex,
                                    onClick = { draft = draft.move(section.id, index + 1) },
                                ) {
                                    Text("Down")
                                }
                            }
                            FilterChip(
                                selected = section.visible,
                                onClick = {
                                    draft = if (section.visible) {
                                        draft.hide(section.id)
                                    } else {
                                        draft.show(section.id)
                                    }
                                },
                                label = { Text(if (section.visible) "Shown" else "Hidden") },
                            )
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            ) {
                                items(HomeSectionSize.entries) { size ->
                                    FilterChip(
                                        selected = section.size == size,
                                        onClick = { draft = draft.resize(section.id, size) },
                                        label = { Text(size.name) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        draft = draft.restoreDefaults()
                            .reconcileAvailableSections(layout.sections.map(HomeWorkspaceSection::id))
                    },
                ) {
                    Text("Restore defaults")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft) }) { Text("Save") }
        },
    )
}

@Composable
private fun DashboardWidgetCard(
    widget: NativeDashboardWidget,
    items: List<NativeDashboardItem>,
    size: HomeSectionSize,
    onOpenLink: (String) -> Unit,
) {
    var expanded by remember(widget.id) { mutableStateOf(false) }
    val collapsedItemCount = dashboardCollapsedItemCount(size)
    val visibleItems = if (expanded) items else items.take(collapsedItemCount)
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(
            min = when (size) {
                HomeSectionSize.Compact -> 112.dp
                HomeSectionSize.Comfortable -> 150.dp
                HomeSectionSize.Dense -> 180.dp
            },
        ),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Surface(
                    shape = CircleShape,
                    color = NextcloudTheme.colors.appIconContainer,
                ) {
                    Icon(
                        imageVector = NextcloudIcons.app(widget.id),
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp).size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    widget.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (items.isEmpty()) {
                Text(
                    "Nothing new",
                    modifier = Modifier.padding(top = NextcloudSpacing.Large),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                visibleItems.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = NextcloudSpacing.Small),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    DashboardItemRow(item = item, onOpenLink = onOpenLink)
                }
                if (items.size > collapsedItemCount) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) {
                                "Show less"
                            } else {
                                "Show ${items.size - collapsedItemCount} more"
                            },
                        )
                    }
                }
            }
            if (widget.actions.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    items(widget.actions) { action ->
                        TextButton(onClick = { onOpenLink(action.link) }) {
                            Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

internal fun dashboardCollapsedItemCount(size: HomeSectionSize): Int = when (size) {
    HomeSectionSize.Compact -> 2
    HomeSectionSize.Comfortable -> 4
    HomeSectionSize.Dense -> 8
}

private fun NativeDashboardWidget.preferredHomeSectionId(): HomeSectionId = when (id.lowercase()) {
    "activity" -> HomeSectionIds.Activity
    "calendar", "upcoming" -> HomeSectionIds.Upcoming
    "recommendations", "recent-files", "recent_files", "recentfiles" -> HomeSectionIds.RecentFiles
    "photos", "memories", "photo-backup", "photo_backup" -> HomeSectionIds.PhotoBackup
    "favorites", "favourites" -> HomeSectionIds.Favorites
    "quota", "storage", "storage-quota", "storage_quota" -> HomeSectionIds.Storage
    else -> dynamicHomeSectionId()
}

private fun NativeDashboardWidget.availableHomeSectionId(
    occupied: Set<HomeSectionId>,
): HomeSectionId {
    val preferred = preferredHomeSectionId()
    if (preferred !in occupied) return preferred
    repeat(MAX_HOME_WORKSPACE_SECTIONS) { disambiguation ->
        val candidate = dynamicHomeSectionId(disambiguation)
        if (candidate !in occupied) return candidate
    }
    error("The dashboard has no available bounded section ID.")
}

private fun NativeDashboardWidget.dynamicHomeSectionId(
    disambiguation: Int = 0,
): HomeSectionId {
    val readable = id.lowercase()
        .map { character ->
            if (
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '-' ||
                character == '_' ||
                character == '.'
            ) {
                character
            } else {
                '-'
            }
        }
        .joinToString("")
        .trim('-')
        .take(MAX_DASHBOARD_SECTION_READABLE_ID_LENGTH)
        .ifEmpty { "widget" }
    val hashSource = if (disambiguation == 0) id else "$id#$disambiguation"
    val hash = hashSource.encodeToByteArray().fold(FNV_OFFSET_BASIS) { current, byte ->
        (current xor byte.toUByte().toUInt()) * FNV_PRIME
    }
    return HomeSectionId("dashboard:$readable:${hash.toString(16).padStart(8, '0')}")
}

@Composable
private fun DashboardItemRow(
    item: NativeDashboardItem,
    onOpenLink: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(item.link?.let { link -> Modifier.clickable { onOpenLink(link) } } ?: Modifier)
            .padding(vertical = NextcloudSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Icon(
            NextcloudIcons.app(item.widgetId),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.let { subtitle ->
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.link != null) {
            Icon(
                NextcloudIcons.ChevronRight,
                contentDescription = "Open ${item.title}",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DashboardLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            "Loading dashboard…",
            modifier = Modifier.padding(top = NextcloudSpacing.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DashboardFailure(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            NextcloudIcons.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(36.dp),
        )
        Text(
            message,
            modifier = Modifier.padding(vertical = NextcloudSpacing.Medium),
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

private sealed interface UserStatusSurfaceState {
    data object Loading : UserStatusSurfaceState
    data class Available(
        val capabilities: NativeUserStatusCapabilities,
        val status: NativeUserStatus,
        val predefined: List<NativePredefinedStatus>,
    ) : UserStatusSurfaceState
    data class Failed(val message: String) : UserStatusSurfaceState
}

private enum class StatusExpiryChoice(val label: String, val seconds: Long?) {
    Never("No expiry", null),
    OneHour("1 hour", 60L * 60L),
    FourHours("4 hours", 4L * 60L * 60L),
    OneDay("24 hours", 24L * 60L * 60L),
}

@Composable
internal fun NativeUserStatusScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onBack: () -> Unit,
) {
    var state by remember(session) { mutableStateOf<UserStatusSurfaceState>(UserStatusSurfaceState.Loading) }
    var refreshAttempt by remember(session) { mutableStateOf(0) }
    var customMessage by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var customIcon by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var expiryChoiceName by rememberSaveable(session.serverUrl, session.loginName) {
        mutableStateOf(StatusExpiryChoice.Never.name)
    }
    var draftInitialized by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf(false) }
    val expiryChoice = StatusExpiryChoice.entries.firstOrNull { it.name == expiryChoiceName }
        ?: StatusExpiryChoice.Never
    var pendingEdit by remember(session) { mutableStateOf<NativeUserStatusEdit?>(null) }
    var mutationInProgress by remember(session) { mutableStateOf(false) }
    var mutationError by remember(session) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session, refreshAttempt) {
        state = UserStatusSurfaceState.Loading
        runCatching {
            val capabilities = parseUserStatusCapabilities(
                services.executeNextcloudApi(session, userStatusCapabilitiesRequest()),
            )
            require(capabilities.enabled) { "User Status is not enabled on this server." }
            coroutineScope {
                val currentDeferred = async {
                    parseCurrentUserStatus(
                        services.executeNextcloudApi(session, currentUserStatusRequest()),
                    )
                }
                val predefinedDeferred = async {
                    parsePredefinedStatuses(
                        services.executeNextcloudApi(session, predefinedStatusesRequest()),
                    )
                }
                UserStatusSurfaceState.Available(
                    capabilities = capabilities,
                    status = currentDeferred.await(),
                    predefined = predefinedDeferred.await(),
                )
            }
        }.onSuccess { loaded ->
            state = loaded
            if (!draftInitialized) {
                customMessage = loaded.status.message.orEmpty()
                customIcon = loaded.status.icon.orEmpty().takeIf {
                    loaded.capabilities.supportsEmoji
                }.orEmpty()
                draftInitialized = true
            }
        }.onFailure { failure ->
            state = UserStatusSurfaceState.Failed(
                failure.message ?: "Your status could not be loaded.",
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DashboardHeader(
            title = "User Status",
            subtitle = "Choose what other people see",
            onBack = onBack,
            onRefresh = { refreshAttempt += 1 },
        )
        when (val current = state) {
            UserStatusSurfaceState.Loading -> DashboardLoading()
            is UserStatusSurfaceState.Failed -> DashboardFailure(
                message = current.message,
                onRetry = { refreshAttempt += 1 },
            )
            is UserStatusSurfaceState.Available -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NextcloudSpacing.XLarge),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XLarge),
            ) {
                item {
                    CurrentUserStatusCard(current.status)
                }
                item {
                    SectionLabel("Presence")
                    LazyRow(
                        contentPadding = PaddingValues(top = NextcloudSpacing.Small),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        items(availableUserPresences(current.capabilities)) { presence ->
                            FilterChip(
                                selected = current.status.presence == presence,
                                onClick = {
                                    pendingEdit = NativeUserStatusEdit.Presence(presence)
                                    mutationError = null
                                },
                                label = { Text(presence.displayLabel()) },
                                leadingIcon = { StatusPresenceDot(presence) },
                            )
                        }
                    }
                }
                if (current.predefined.isNotEmpty()) {
                    item {
                        SectionLabel("Quick statuses")
                        Column(
                            modifier = Modifier.padding(top = NextcloudSpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            current.predefined.forEach { predefined ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pendingEdit = NativeUserStatusEdit.PredefinedMessage(
                                                messageId = predefined.id,
                                                clearAtEpochSeconds = expiryChoice.expiryEpochSeconds(),
                                            )
                                            mutationError = null
                                        },
                                    color = NextcloudTheme.colors.appTile,
                                    shape = RoundedCornerShape(NextcloudRadii.Card),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                    ) {
                                        Text(predefined.icon ?: "•", style = MaterialTheme.typography.titleMedium)
                                        Text(predefined.message, modifier = Modifier.weight(1f))
                                        Icon(
                                            NextcloudIcons.ChevronRight,
                                            contentDescription = "Use ${predefined.message}",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    SectionLabel("Custom status")
                    OutlinedTextField(
                        value = customMessage,
                        onValueChange = { value ->
                            if (value.length <= 512 && value.none(Char::isISOControl)) {
                                customMessage = value
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Small),
                        label = { Text("Message") },
                        supportingText = { Text("${customMessage.length}/512") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    if (current.capabilities.supportsEmoji) {
                        OutlinedTextField(
                            value = customIcon,
                            onValueChange = { value ->
                                if (value.length <= 32 && value.none(Char::isISOControl)) {
                                    customIcon = value
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Small),
                            label = { Text("Emoji or icon") },
                            singleLine = true,
                        )
                    }
                }
                item {
                    SectionLabel("Clear automatically")
                    LazyRow(
                        contentPadding = PaddingValues(top = NextcloudSpacing.Small),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        items(StatusExpiryChoice.entries) { choice ->
                            FilterChip(
                                selected = expiryChoice == choice,
                                onClick = { expiryChoiceName = choice.name },
                                label = { Text(choice.label) },
                            )
                        }
                    }
                }
                item {
                    mutationError?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.padding(bottom = NextcloudSpacing.Small),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        Button(
                            enabled = customMessage.isNotBlank() && !mutationInProgress,
                            onClick = {
                                pendingEdit = NativeUserStatusEdit.CustomMessage(
                                    message = customMessage.trim(),
                                    icon = customIcon.trim().takeIf(String::isNotEmpty),
                                    clearAtEpochSeconds = expiryChoice.expiryEpochSeconds(),
                                )
                                mutationError = null
                            },
                        ) {
                            Text("Save status")
                        }
                        if (current.status.message != null) {
                            OutlinedButton(
                                enabled = !mutationInProgress,
                                onClick = {
                                    pendingEdit = NativeUserStatusEdit.ClearMessage
                                    mutationError = null
                                },
                            ) {
                                Text("Clear message")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingEdit?.let { edit ->
        val capabilities = (state as? UserStatusSurfaceState.Available)?.capabilities
        val requestPlan = capabilities?.let {
            runCatching {
                planUserStatusEdit(
                    edit = edit,
                    capabilities = it,
                    nowEpochSeconds = currentDashboardEpochSeconds(),
                )
            }
        }
        val planningFailure = requestPlan?.exceptionOrNull()
        if (planningFailure != null) {
            AlertDialog(
                onDismissRequest = { pendingEdit = null },
                title = { Text("Status change unavailable") },
                text = {
                    Text(planningFailure.message ?: "This status change is not supported.")
                },
                confirmButton = {
                    TextButton(onClick = { pendingEdit = null }) { Text("Close") }
                },
            )
        }
        val request = requestPlan?.getOrNull()
        if (request != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!mutationInProgress) pendingEdit = null
                },
                title = { Text("Confirm status change") },
                text = {
                    Text(
                        "${edit.confirmationLabel().replaceFirstChar { it.uppercase() }}. " +
                            "This will update your status for people on this server.",
                    )
                },
                dismissButton = {
                    TextButton(
                        enabled = !mutationInProgress,
                        onClick = { pendingEdit = null },
                    ) {
                        Text("Cancel")
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !mutationInProgress,
                        onClick = {
                            mutationInProgress = true
                            scope.launch {
                                runCatching {
                                    services.executeNextcloudApi(session, request).also { response ->
                                        require(response.status in 200..299) {
                                            "The server rejected this status change (HTTP ${response.status})."
                                        }
                                    }
                                }.onSuccess {
                                    sharedDashboardStatusMemoryCache.invalidate(session)
                                    pendingEdit = null
                                    mutationInProgress = false
                                    mutationError = null
                                    draftInitialized = false
                                    refreshAttempt += 1
                                }.onFailure { failure ->
                                    mutationInProgress = false
                                    mutationError = failure.message ?: "The status could not be updated."
                                    pendingEdit = null
                                }
                            }
                        },
                    ) {
                        if (mutationInProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (mutationInProgress) "Updating…" else "Update status")
                    }
                },
            )
        }
    }
}

@Composable
private fun CurrentUserStatusCard(status: NativeUserStatus) {
    Surface(
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            StatusPresenceDot(status.presence)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    status.message?.let { message ->
                        listOfNotNull(status.icon, message).joinToString(" ")
                    } ?: "No status message",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    status.presence.displayLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPresenceDot(presence: NativeUserPresence) {
    val color = when (presence) {
        NativeUserPresence.Online -> NextcloudTheme.colors.success
        NativeUserPresence.Away -> MaterialTheme.colorScheme.tertiary
        NativeUserPresence.DoNotDisturb -> MaterialTheme.colorScheme.error
        NativeUserPresence.Invisible,
        NativeUserPresence.Offline,
        -> MaterialTheme.colorScheme.outline
        NativeUserPresence.Busy -> MaterialTheme.colorScheme.error
    }
    Surface(modifier = Modifier.size(12.dp), shape = CircleShape, color = color) {}
}

@Composable
private fun SectionLabel(label: String) {
    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private fun StatusExpiryChoice.expiryEpochSeconds(): Long? =
    seconds?.let { currentDashboardEpochSeconds() + it }

private fun currentDashboardEpochSeconds(): Long = Clock.System.now().epochSeconds

private val DASHBOARD_QUICK_ACTION_APP_IDS = listOf(
    "files",
    "photos",
    "memories",
    "notes",
    "calendar",
    "spreed",
    "talk",
)
private const val MAX_DASHBOARD_QUICK_ACTIONS = 6
private const val MAX_DASHBOARD_SECTION_READABLE_ID_LENGTH = 48
private const val FNV_OFFSET_BASIS: UInt = 2_166_136_261u
private const val FNV_PRIME: UInt = 16_777_619u
