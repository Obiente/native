package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

internal sealed interface DesktopActivityEntry {
    val stableId: String

    data class Single(val activity: NextcloudActivity) : DesktopActivityEntry {
        override val stableId: String = "activity:${activity.id}"
    }

    data class AutomationBundle(
        val app: String,
        val type: String,
        val activities: List<NextcloudActivity>,
    ) : DesktopActivityEntry {
        override val stableId: String = "bundle:$app:$type:${activities.first().id}"
    }
}

internal fun bundleDesktopActivities(activities: List<NextcloudActivity>): List<DesktopActivityEntry> {
    val bundleCandidates = activities.groupBy { activity ->
        if (activity.isBackgroundAutomationActivity()) {
            activity.app.lowercase() to activity.type.lowercase()
        } else {
            null
        }
    }.filterKeys { it != null }.filterValues { it.size >= 3 }
    val emitted = mutableSetOf<Pair<String, String>>()
    return buildList {
        activities.forEach { activity ->
            val key = if (activity.isBackgroundAutomationActivity()) {
                activity.app.lowercase() to activity.type.lowercase()
            } else {
                null
            }
            val bundle = key?.let(bundleCandidates::get)
            if (key != null && bundle != null) {
                if (emitted.add(key)) add(DesktopActivityEntry.AutomationBundle(activity.app, activity.type, bundle))
            } else {
                add(DesktopActivityEntry.Single(activity))
            }
        }
    }
}

internal fun NextcloudActivity.needsDesktopAttention(): Boolean {
    val text = listOfNotNull(subject, message, type).joinToString(" ").lowercase()
    return listOf("failed", "conflict", "expir", "blocked", "could not", "needs attention")
        .any(text::contains)
}

internal fun shouldAutoLoadActivityPage(
    hasMore: Boolean,
    loadingMore: Boolean,
    refreshing: Boolean,
    error: String?,
    totalItemCount: Int,
    lastVisibleItemIndex: Int,
    prefetchDistance: Int = 4,
): Boolean = hasMore &&
    !loadingMore &&
    !refreshing &&
    error == null &&
    totalItemCount > 0 &&
    lastVisibleItemIndex >= (totalItemCount - 1 - prefetchDistance).coerceAtLeast(0)

private fun NextcloudActivity.isBackgroundAutomationActivity(): Boolean {
    val text = "$app $type $subject".lowercase()
    return listOf("recognize", "systemtag", "system tag", "automation", "background")
        .any(text::contains)
}

@Composable
internal fun ActivityDesktopWorkspace(
    timeline: ActivityTimelineState,
    feed: ActivityFeedPresentation,
    query: String,
    selectedSemantic: NextcloudActivitySemantic?,
    selectedApp: String?,
    selectedType: String?,
    serverFilters: List<NextcloudActivityFilterOption>,
    selectedServerFilterId: String,
    onQueryChanged: (String) -> Unit,
    onSemanticSelected: (NextcloudActivitySemantic?) -> Unit,
    onAppSelected: (String?) -> Unit,
    onTypeSelected: (String?) -> Unit,
    onServerFilterSelected: (String) -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    actionFor: (NextcloudActivity) -> ActivityOpenAction?,
    onOpenAction: (ActivityOpenAction) -> Unit,
    loadPreview: suspend (NextcloudActivityPreview) -> ByteArray?,
    previewCacheScope: String = "synthetic",
    onOpenSettings: (ActivitySettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val attention = remember(timeline.activities) {
        timeline.activities.filter(NextcloudActivity::needsDesktopAttention).take(3)
    }
    Column(modifier = modifier.fillMaxSize()) {
        ActivityDesktopHeader(
            refreshing = timeline.refreshing,
            onRefresh = onRefresh,
            onOpenSettings = onOpenSettings,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val showCompanion = maxWidth >= 1_140.dp
            Row(
                modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    ActivityDesktopToolbar(
                        query = query,
                        selectedSemantic = selectedSemantic,
                        selectedApp = selectedApp,
                        selectedType = selectedType,
                        serverFilters = serverFilters,
                        selectedServerFilterId = selectedServerFilterId,
                        feed = feed,
                        onQueryChanged = onQueryChanged,
                        onSemanticSelected = onSemanticSelected,
                        onAppSelected = onAppSelected,
                        onTypeSelected = onTypeSelected,
                        onServerFilterSelected = onServerFilterSelected,
                        onClearFilters = onClearFilters,
                    )
                    if (attention.isNotEmpty()) {
                        ActivityAttentionStrip(
                            activities = attention,
                            actionFor = actionFor,
                            onOpenAction = onOpenAction,
                        )
                    }
                    ActivityDesktopTimeline(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        timeline = timeline,
                        feed = feed,
                        onClearFilters = onClearFilters,
                        onLoadMore = onLoadMore,
                        onRetry = onRefresh,
                        actionFor = actionFor,
                        onOpenAction = onOpenAction,
                        loadPreview = loadPreview,
                        previewCacheScope = previewCacheScope,
                    )
                }
                if (showCompanion) {
                    ActivityCompanionPane(
                        feed = feed,
                        selectedApp = selectedApp,
                        onAppSelected = onAppSelected,
                        modifier = Modifier.width(292.dp).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ActivityMobileWorkspace(
    timeline: ActivityTimelineState,
    feed: ActivityFeedPresentation,
    query: String,
    selectedSemantic: NextcloudActivitySemantic?,
    selectedApp: String?,
    selectedType: String?,
    serverFilters: List<NextcloudActivityFilterOption>,
    selectedServerFilterId: String,
    onQueryChanged: (String) -> Unit,
    onSemanticSelected: (NextcloudActivitySemantic?) -> Unit,
    onAppSelected: (String?) -> Unit,
    onTypeSelected: (String?) -> Unit,
    onServerFilterSelected: (String) -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    actionFor: (NextcloudActivity) -> ActivityOpenAction?,
    onOpenAction: (ActivityOpenAction) -> Unit,
    loadPreview: suspend (NextcloudActivityPreview) -> ByteArray?,
    previewCacheScope: String = "synthetic",
    onOpenSettings: (ActivitySettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val attention = remember(timeline.activities) {
        timeline.activities.filter(NextcloudActivity::needsDesktopAttention).take(3)
    }
    val filtersActive = selectedServerFilterId != "all" ||
        query.isNotBlank() || selectedSemantic != null || selectedApp != null || selectedType != null
    val listState = rememberLazyListState()
    val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    val autoLoadMore = shouldAutoLoadActivityPage(
        hasMore = timeline.hasMore,
        loadingMore = timeline.loadingMore,
        refreshing = timeline.refreshing,
        error = timeline.error,
        totalItemCount = listState.layoutInfo.totalItemsCount,
        lastVisibleItemIndex = lastVisibleItemIndex,
    )
    LaunchedEffect(autoLoadMore, timeline.activities.size) {
        if (autoLoadMore && !filtersActive) onLoadMore()
    }
    Column(modifier = modifier.fillMaxSize()) {
        ActivityMobileHeader(
            eventCount = feed.matchedCount,
            refreshing = timeline.refreshing,
            onRefresh = onRefresh,
            onOpenSettings = onOpenSettings,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = NextcloudSpacing.Medium,
                top = NextcloudSpacing.Medium,
                end = NextcloudSpacing.Medium,
                bottom = NextcloudSpacing.XXLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            item("search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(NextcloudIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    label = { Text("Search loaded activity") },
                )
            }
            item("filters") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    ActivityServerFilterMenu(
                        filters = serverFilters,
                        selectedId = selectedServerFilterId,
                        onSelected = onServerFilterSelected,
                    )
                    ActivitySemanticMenu(selectedSemantic, feed.semanticCounts, onSemanticSelected)
                    ActivityFacetMenu(
                        label = selectedApp?.let(::activityReadableSource) ?: "All apps",
                        allLabel = "All apps",
                        facets = feed.appFacets,
                        selected = selectedApp,
                        onSelected = onAppSelected,
                    )
                    ActivityFacetMenu(
                        label = selectedType?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "All events",
                        allLabel = "All events",
                        facets = feed.typeFacets,
                        selected = selectedType,
                        onSelected = onTypeSelected,
                    )
                    if (filtersActive) {
                        TextButton(onClick = onClearFilters) { Text("Clear") }
                    }
                }
            }
            if (attention.isNotEmpty()) {
                item("attention-heading") {
                    Text("Needs attention", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                item("attention") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        contentPadding = PaddingValues(end = NextcloudSpacing.Medium),
                    ) {
                        items(attention, key = NextcloudActivity::id) { activity ->
                            ActivityMobileAttentionCard(
                                activity = activity,
                                action = actionFor(activity),
                                onOpenAction = onOpenAction,
                            )
                        }
                    }
                }
            }
            timeline.error?.let { message ->
                item("error") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRefresh) { Text("Try again") }
                    }
                }
            }
            if (feed.groups.isEmpty()) {
                item("empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.XLarge),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        Text(
                            if (filtersActive) "No activity matches these filters."
                            else "There is no recent activity.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (filtersActive) {
                            TextButton(onClick = onClearFilters) { Text("Clear filters") }
                        }
                    }
                }
            }
            feed.groups.forEach { group ->
                item("day:${group.dateKey}") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${group.activities.size} events",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(
                    items = bundleDesktopActivities(group.activities),
                    key = DesktopActivityEntry::stableId,
                ) { entry ->
                    when (entry) {
                        is DesktopActivityEntry.Single -> ActivityMobileRow(
                            activity = entry.activity,
                            action = actionFor(entry.activity),
                            onOpenAction = onOpenAction,
                            loadPreview = loadPreview,
                            previewCacheScope = previewCacheScope,
                        )
                        is DesktopActivityEntry.AutomationBundle -> ActivityMobileAutomationBundle(
                            entry, actionFor, onOpenAction, loadPreview, previewCacheScope,
                        )
                    }
                }
            }
            if (timeline.hasMore || timeline.loadingMore) {
                item("more") {
                    OutlinedButton(
                        enabled = !timeline.loadingMore && !timeline.refreshing,
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (timeline.loadingMore) "Loading..." else "Load older activity")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityMobileHeader(
    eventCount: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: (ActivitySettingsDestination) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Large,
            vertical = NextcloudSpacing.Medium,
        ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Activity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "$eventCount events across your cloud",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = !refreshing, onClick = onRefresh) {
                Icon(
                    NextcloudIcons.Refresh,
                    contentDescription = if (refreshing) "Refreshing activity" else "Refresh activity",
                )
            }
            ActivitySettingsMenu(onOpenSettings)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ActivityMobileAttentionCard(
    activity: NextcloudActivity,
    action: ActivityOpenAction?,
    onOpenAction: (ActivityOpenAction) -> Unit,
) {
    val text = listOfNotNull(activity.subject, activity.message).joinToString(" ").lowercase()
    val accent = if ("failed" in text || "could not" in text) {
        MaterialTheme.colorScheme.error
    } else {
        NextcloudTheme.colors.warning
    }
    Surface(
        modifier = Modifier.width(300.dp),
        color = accent.copy(alpha = 0.13f),
        shape = RoundedCornerShape(NextcloudRadii.Medium),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.58f)),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Icon(NextcloudIcons.app(activity.app), contentDescription = null, tint = accent)
                Text(
                    activity.subject,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            activity.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            action?.let { planned ->
                TextButton(onClick = { onOpenAction(planned) }, modifier = Modifier.align(Alignment.End)) {
                    Text(planned.label)
                }
            }
        }
    }
}

@Composable
private fun ActivityMobileRow(
    activity: NextcloudActivity,
    action: ActivityOpenAction?,
    onOpenAction: (ActivityOpenAction) -> Unit,
    loadPreview: suspend (NextcloudActivityPreview) -> ByteArray?,
    previewCacheScope: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.Top,
        ) {
            ActivityPreviewOrIcon(activity, loadPreview, previewCacheScope, 40)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(activity.subject, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                activity.message?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        listOf(activityReadableSource(activity.app), activity.dateTime.desktopActivityTime())
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    action?.let { planned ->
                        TextButton(
                            onClick = { onOpenAction(planned) },
                            contentPadding = PaddingValues(horizontal = NextcloudSpacing.Small),
                        ) { Text(planned.label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityMobileAutomationBundle(
    entry: DesktopActivityEntry.AutomationBundle,
    actionFor: (NextcloudActivity) -> ActivityOpenAction?,
    onOpenAction: (ActivityOpenAction) -> Unit,
    loadPreview: suspend (NextcloudActivityPreview) -> ByteArray?,
    previewCacheScope: String,
) {
    var expanded by remember(entry.stableId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth()
            .semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "Collapse grouped activity" else "Expand grouped activity",
            ) { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                    Icon(
                        NextcloudIcons.app(entry.app),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(NextcloudSpacing.Small).size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${activityReadableSource(entry.app)} grouped ${entry.activities.size} background events",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (expanded) "Hide details" else "Review grouped activity",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    NextcloudIcons.ExpandMore,
                    contentDescription = if (expanded) "Collapse grouped activity" else "Expand grouped activity",
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = NextcloudSpacing.Small, start = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                ) {
                    entry.activities.forEach { activity ->
                        ActivityMobileRow(
                            activity = activity,
                            action = actionFor(activity),
                            onOpenAction = onOpenAction,
                            loadPreview = loadPreview,
                            previewCacheScope = previewCacheScope,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityDesktopHeader(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: (ActivitySettingsDestination) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = NextcloudSpacing.Large),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Activity", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Your recent activity across files, shares, and apps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = !refreshing, onClick = onRefresh) {
                Icon(NextcloudIcons.Refresh, contentDescription = if (refreshing) "Refreshing activity" else "Refresh activity")
            }
            ActivitySettingsMenu(onOpenSettings)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ActivitySettingsMenu(onOpenSettings: (ActivitySettingsDestination) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(NextcloudIcons.Settings, contentDescription = "Activity settings")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Notification settings")
                        Text(
                            "Opens your Nextcloud settings",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onOpenSettings(ActivitySettingsDestination.Notifications)
                },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("RSS feed settings")
                        Text(
                            "Opens Activity settings in your browser",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onOpenSettings(ActivitySettingsDestination.RssFeed)
                },
            )
        }
    }
}

@Composable
private fun ActivityDesktopToolbar(
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Small),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f).height(52.dp),
                singleLine = true,
                leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Search loaded activity") },
            )
            ActivityServerFilterMenu(
                filters = serverFilters,
                selectedId = selectedServerFilterId,
                onSelected = onServerFilterSelected,
            )
            ActivitySemanticMenu(selectedSemantic, feed.semanticCounts, onSemanticSelected)
            ActivityFacetMenu(
                label = selectedApp?.let(::activityReadableSource) ?: "All apps",
                allLabel = "All apps",
                facets = feed.appFacets,
                selected = selectedApp,
                onSelected = onAppSelected,
            )
            ActivityFacetMenu(
                label = selectedType?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "All events",
                allLabel = "All events",
                facets = feed.typeFacets,
                selected = selectedType,
                onSelected = onTypeSelected,
            )
            if (query.isNotBlank() || selectedSemantic != null || selectedApp != null || selectedType != null) {
                TextButton(onClick = onClearFilters) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun ActivityServerFilterMenu(
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
private fun ActivitySemanticMenu(
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
private fun ActivityFacetMenu(
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

@Composable
private fun ActivityAttentionStrip(
    activities: List<NextcloudActivity>,
    actionFor: (NextcloudActivity) -> ActivityOpenAction?,
    onOpenAction: (ActivityOpenAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Needs attention", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            activities.forEach { activity ->
                val attentionText = listOfNotNull(activity.subject, activity.message).joinToString(" ").lowercase()
                val failed = "failed" in attentionText || "could not" in attentionText
                val expiring = "expir" in attentionText
                val accent = when {
                    failed -> MaterialTheme.colorScheme.error
                    expiring -> NextcloudTheme.colors.warning
                    else -> NextcloudTheme.colors.warning
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    color = accent.copy(alpha = if (failed) 0.16f else 0.12f),
                    shape = RoundedCornerShape(NextcloudRadii.Small),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.62f)),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                NextcloudIcons.app(activity.app),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = accent,
                            )
                            Text(
                                activity.subject,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            activity.message ?: activityReadableSource(activity.app),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                activityReadableSource(activity.app),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            actionFor(activity)?.let { action ->
                                TextButton(
                                    onClick = { onOpenAction(action) },
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                ) {
                                    Text(action.label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityDesktopTimeline(
    modifier: Modifier,
    timeline: ActivityTimelineState,
    feed: ActivityFeedPresentation,
    onClearFilters: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    actionFor: (NextcloudActivity) -> ActivityOpenAction?,
    onOpenAction: (ActivityOpenAction) -> Unit,
    loadPreview: suspend (NextcloudActivityPreview) -> ByteArray?,
    previewCacheScope: String,
) {
    val listState = rememberLazyListState()
    val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    val autoLoadMore = shouldAutoLoadActivityPage(
        hasMore = timeline.hasMore,
        loadingMore = timeline.loadingMore,
        refreshing = timeline.refreshing,
        error = timeline.error,
        totalItemCount = listState.layoutInfo.totalItemsCount,
        lastVisibleItemIndex = lastVisibleItemIndex,
    )
    LaunchedEffect(autoLoadMore, timeline.activities.size) {
        if (autoLoadMore) onLoadMore()
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(NextcloudRadii.Small),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NextcloudSpacing.Medium),
        ) {
            if (timeline.error != null) {
                item("error") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            timeline.error,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                }
            }
            if (feed.groups.isEmpty()) {
                item("empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.XLarge),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No activity matches these filters.", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onClearFilters) { Text("Clear filters") }
                    }
                }
            }
            feed.groups.forEach { group ->
                item("day:${group.dateKey}") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${group.activities.size} events",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                items(
                    items = bundleDesktopActivities(group.activities),
                    key = DesktopActivityEntry::stableId,
                ) { entry ->
                    when (entry) {
                        is DesktopActivityEntry.Single -> ActivityDesktopRow(
                            activity = entry.activity,
                            action = actionFor(entry.activity),
                            onOpenAction = onOpenAction,
                            loadPreview = loadPreview,
                            previewCacheScope = previewCacheScope,
                        )
                        is DesktopActivityEntry.AutomationBundle -> ActivityAutomationBundleRow(
                            entry, actionFor, onOpenAction, loadPreview, previewCacheScope,
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
            if (timeline.hasMore || timeline.loadingMore) {
                item("more") {
                    TextButton(
                        enabled = !timeline.loadingMore && !timeline.refreshing,
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (timeline.loadingMore) "Loading..." else "Load older activity")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityDesktopRow(
    activity: NextcloudActivity,
    action: ActivityOpenAction?,
    onOpenAction: (ActivityOpenAction) -> Unit,
    loadPreview: suspend (NextcloudActivityPreview) -> ByteArray?,
    previewCacheScope: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            activity.dateTime.desktopActivityTime(),
            modifier = Modifier.width(52.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActivityPreviewOrIcon(activity, loadPreview, previewCacheScope, 37)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(activity.subject, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            activity.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
            shape = RoundedCornerShape(999.dp),
        ) {
            Text(
                activityReadableSource(activity.app),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        action?.let { planned ->
            TextButton(onClick = { onOpenAction(planned) }) { Text(planned.label) }
        }
    }
}

@Composable
private fun ActivityAutomationBundleRow(
    entry: DesktopActivityEntry.AutomationBundle,
    actionFor: (NextcloudActivity) -> ActivityOpenAction?,
    onOpenAction: (ActivityOpenAction) -> Unit,
    loadPreview: suspend (NextcloudActivityPreview) -> ByteArray?,
    previewCacheScope: String,
) {
    var expanded by remember(entry.stableId) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Collapse grouped activity" else "Expand grouped activity",
                ) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.activities.first().dateTime.desktopActivityTime(),
                modifier = Modifier.width(52.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                Icon(
                    NextcloudIcons.app(entry.app),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(9.dp).size(19.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${activityReadableSource(entry.app)} processed ${entry.activities.size} background events",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Grouped to keep automated activity readable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(if (expanded) "Collapse" else "Review", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 76.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                entry.activities.forEach { activity ->
                    ActivityDesktopRow(
                        activity, actionFor(activity), onOpenAction, loadPreview, previewCacheScope,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityPreviewOrIcon(
    activity: NextcloudActivity,
    loadPreview: suspend (NextcloudActivityPreview) -> ByteArray?,
    previewCacheScope: String,
    size: Int,
) {
    val preview = activity.preview
    var image by remember(previewCacheScope, preview?.fileId) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(previewCacheScope, preview?.fileId) {
        image = null
        image = preview?.let { candidate ->
            runCatching {
                loadPreview(candidate)?.let { encoded ->
                    decodePlatformImage(encoded, EncodedImageOrientationPolicy.PixelsAlreadyUpright)
                }
            }.getOrNull()
        }
    }
    val ready = image
    if (ready != null) {
        Image(
            bitmap = ready,
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
            Icon(
                NextcloudIcons.app(activity.app),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(NextcloudSpacing.Small).size((size - 20).coerceAtLeast(18).dp),
            )
        }
    }
}

@Composable
private fun ActivityCompanionPane(
    feed: ActivityFeedPresentation,
    selectedApp: String?,
    onAppSelected: (String?) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ActivityCompanionSection("Activity summary") {
            val cards = listOf(
                "People" to (feed.semanticCounts[NextcloudActivitySemantic.Message] ?: 0),
                "Files" to (feed.semanticCounts[NextcloudActivitySemantic.File] ?: 0),
                "Media" to (feed.semanticCounts[NextcloudActivitySemantic.Media] ?: 0),
                "Other" to (feed.semanticCounts[NextcloudActivitySemantic.General] ?: 0),
            )
            cards.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, count) -> ActivitySummaryMetric(label, count, Modifier.weight(1f)) }
                }
            }
        }
        ActivityCompanionSection("Saved scopes") {
            ActivityScopeRow("My activity", feed.matchedCount, selectedApp == null) { onAppSelected(null) }
            feed.appFacets.take(5).forEach { facet ->
                ActivityScopeRow(facet.label, facet.count, selectedApp == facet.key) { onAppSelected(facet.key) }
            }
        }
        ActivityCompanionSection("Source health") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = NextcloudTheme.colors.success) {}
                Column {
                    Text("Activity source connected", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${feed.matchedCount} events in the current view",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityCompanionSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Small),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ActivitySummaryMetric(label: String, count: Int, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(count.toString(), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ActivityScopeRow(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun NextcloudActivitySemantic.desktopTitle(): String = when (this) {
    NextcloudActivitySemantic.Message -> "People"
    NextcloudActivitySemantic.Media -> "Media"
    NextcloudActivitySemantic.File -> "Files"
    NextcloudActivitySemantic.General -> "Other"
}

private fun activityReadableSource(value: String): String = value
    .replace('_', ' ')
    .split(' ')
    .joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) }

private fun String?.desktopActivityTime(): String = this
    ?.substringAfter('T', "")
    ?.take(5)
    ?.takeIf(String::isNotBlank)
    ?: ""
