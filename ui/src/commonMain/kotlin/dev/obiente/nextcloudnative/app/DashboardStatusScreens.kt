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

private sealed interface DashboardSurfaceState {
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
    onBack: () -> Unit,
) {
    var state by remember(session) { mutableStateOf<DashboardSurfaceState>(DashboardSurfaceState.Loading) }
    var refreshAttempt by remember(session) { mutableStateOf(0) }

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

    Column(modifier = Modifier.fillMaxSize()) {
        DashboardHeader(
            title = "Dashboard",
            subtitle = "Your cloud at a glance",
            onBack = onBack,
            onRefresh = { refreshAttempt += 1 },
        )
        when (val current = state) {
            DashboardSurfaceState.Loading -> DashboardLoading()
            is DashboardSurfaceState.Failed -> DashboardFailure(
                message = current.message,
                onRetry = { refreshAttempt += 1 },
            )
            is DashboardSurfaceState.Available -> {
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
                    items(current.snapshot.widgets, key = NativeDashboardWidget::id) { widget ->
                        DashboardWidgetCard(
                            widget = widget,
                            items = current.snapshot.itemsByWidget[widget.id].orEmpty(),
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
    }
}

@Composable
private fun DashboardHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Medium,
            vertical = NextcloudSpacing.Small,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(NextcloudIcons.Back, contentDescription = "Back")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(NextcloudIcons.Refresh, contentDescription = "Refresh dashboard")
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

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

@Composable
private fun DashboardWidgetCard(
    widget: NativeDashboardWidget,
    items: List<NativeDashboardItem>,
    onOpenLink: (String) -> Unit,
) {
    var expanded by remember(widget.id) { mutableStateOf(false) }
    val visibleItems = if (expanded) items else items.take(DASHBOARD_COLLAPSED_ITEM_COUNT)
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
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
                if (items.size > DASHBOARD_COLLAPSED_ITEM_COUNT) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) {
                                "Show less"
                            } else {
                                "Show ${items.size - DASHBOARD_COLLAPSED_ITEM_COUNT} more"
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

private const val DASHBOARD_COLLAPSED_ITEM_COUNT = 8
