package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Retains the account's notifications outside the Activity destination, so the shell badge stays
 * current while other workspaces are open and writes keep running when the inbox is closed. Call
 * it from the account shell. The list is not saved for process recreation; the account cache
 * paints the last one again and the next read replaces it. Actions whose result is unknown are
 * recorded on the device and stay off after a restart.
 */
@Composable
internal fun rememberNotificationsInboxController(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
): NotificationsInboxController {
    val currentServices by rememberUpdatedState(services)
    val currentSession by rememberUpdatedState(session)
    val parent = rememberCoroutineScope()
    val scope = remember(session.accountId) {
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val controller = remember(scope) {
        NotificationsInboxController(
            scope = scope,
            accountId = session.accountId,
            serverUrl = session.serverUrl,
            execute = { request -> currentServices.executeNextcloudApi(currentSession, request) },
            cache = sharedNotificationsInboxMemoryCache,
            actionAttempts = notificationActionAttemptStore(services, session),
        )
    }
    LaunchedEffect(controller) { controller.start() }
    return controller
}

/**
 * Native Notifications inbox. Wide layouts keep the list and the selected notification side by
 * side; compact layouts show one of them and use system back to return to the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationsInboxScreen(
    controller: NotificationsInboxController,
    serverUrl: String,
    onOpenLink: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Opening the inbox reads the list again; the shell's first read paints the badge earlier.
    LaunchedEffect(controller) { controller.refresh() }
    val state = controller.state
    val listState = rememberLazyListState()
    val desktop = LocalNextcloudWorkspaceCapabilities.current.isDesktop
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= NOTIFICATIONS_TWO_PANE_MIN_WIDTH
        val selected = state.selected
        PlatformBackHandler(enabled = selected != null && !twoPane, onBack = controller::clearSelection)
        Column(modifier = Modifier.fillMaxSize()) {
            NotificationsHeader(
                state = state,
                showBack = selected != null && !twoPane,
                onBack = controller::clearSelection,
                onRefresh = controller::refresh,
                onDismissAll = controller::requestDismissAll,
                onOpenSettings = onOpenSettings,
            )
            NotificationsNotices(state, controller)
            val list: @Composable () -> Unit = {
                NotificationsListPane(state, listState, twoPane, serverUrl, controller, onOpenLink, pullToRefresh = !desktop)
            }
            when {
                twoPane -> Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(0.42f).fillMaxHeight()) { list() }
                    VerticalDivider()
                    Box(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                        if (selected != null) {
                            NotificationDetail(selected, state, serverUrl, controller, onOpenLink)
                        } else {
                            NotificationDetailPlaceholder(state)
                        }
                    }
                }
                selected != null -> NotificationDetail(selected, state, serverUrl, controller, onOpenLink)
                else -> list()
            }
        }
    }
    state.confirmation?.let { confirmation ->
        NotificationsConfirmationDialog(confirmation, state, controller::confirm, controller::cancelConfirmation)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsListPane(
    state: NotificationsInboxState,
    listState: LazyListState,
    twoPane: Boolean,
    serverUrl: String,
    controller: NotificationsInboxController,
    onOpenLink: (String) -> Unit,
    pullToRefresh: Boolean,
) {
    val content: @Composable () -> Unit = {
        NotificationsList(state, listState, twoPane, serverUrl, controller, onOpenLink)
    }
    if (pullToRefresh) {
        PullToRefreshBox(
            isRefreshing = state.refreshing && state.notifications != null,
            onRefresh = controller::refresh,
            modifier = Modifier.fillMaxSize(),
        ) { content() }
    } else {
        content()
    }
}

@Composable
private fun NotificationsList(
    state: NotificationsInboxState,
    listState: LazyListState,
    twoPane: Boolean,
    serverUrl: String,
    controller: NotificationsInboxController,
    onOpenLink: (String) -> Unit,
) {
    val notifications = state.notifications
    when {
        state.availability == NotificationsAvailability.NotEnabled -> NotificationsMessagePane(
            title = "Notifications are off for this account",
            message = "The Notifications app is not enabled on this server. An administrator can enable it.",
        )
        state.availability == NotificationsAvailability.Unsupported -> NotificationsMessagePane(
            title = "This Notifications version is not supported",
            message = "The server's Notifications app does not offer the v2 list this app reads. Update it on the server.",
        )
        notifications == null && state.failure != null -> NotificationsMessagePane(
            title = state.failure.title,
            message = state.failure.message,
            actionLabel = "Try again",
            onAction = controller::refresh,
        )
        notifications == null -> LoadingMessage("Loading notifications...")
        notifications.isEmpty() -> NotificationsMessagePane(
            title = "You're all caught up",
            message = if (state.noNotifierApps) {
                "No app on this server sends you notifications yet."
            } else {
                "New notifications from your Nextcloud apps appear here."
            },
        )
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(notifications, key = NextcloudNotification::id) { notification ->
                NotificationRow(
                    notification = notification,
                    state = state,
                    selected = twoPane && notification.id == state.selectedId,
                    openLabel = notificationOpenLabel(serverUrl, notification.link),
                    controller = controller,
                    onOpenLink = onOpenLink,
                )
                HorizontalDivider()
            }
            if (state.truncated) {
                item(key = "notifications-truncated") {
                    Text(
                        "Showing the newest $MAX_NOTIFICATION_ENTRIES notifications. Dismiss some to see older ones.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationsHeader(
    state: NotificationsInboxState,
    showBack: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDismissAll: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) { Icon(NextcloudIcons.Back, contentDescription = "Back to notifications") }
        }
        Column(modifier = Modifier.weight(1f).padding(start = NextcloudSpacing.Small)) {
            Text("Notifications", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            notificationsCountLabel(state)?.let { label ->
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.capabilities?.canDismissAll == true && !state.notifications.isNullOrEmpty()) {
            TextButton(onClick = onDismissAll, enabled = state.canDismissAll) {
                Text(if (state.dismissingAll) "Dismissing..." else "Dismiss all")
            }
        }
        if (state.refreshing) {
            CircularProgressIndicator(modifier = Modifier.padding(NextcloudSpacing.Medium).size(20.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) { Icon(NextcloudIcons.Refresh, contentDescription = "Refresh notifications") }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(NextcloudIcons.Settings, contentDescription = "Notification settings")
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun NotificationsNotices(state: NotificationsInboxState, controller: NotificationsInboxController) {
    state.notice?.let { notice ->
        NotificationsNotice(notice, actionLabel = "Dismiss", onAction = controller::dismissNotice)
    }
    if (state.unconfirmedIds.isNotEmpty() || state.allUnconfirmed) {
        NotificationsNotice(
            "Changes are paused until the list is refreshed, because an earlier result is unknown.",
            actionLabel = "Refresh",
            onAction = controller::refresh,
        )
    }
    state.failure?.takeIf { state.notifications != null }?.let { failure ->
        NotificationsNotice(
            "${failure.title}. Showing the notifications loaded earlier.",
            actionLabel = "Try again",
            onAction = controller::refresh,
        )
    }
}

@Composable
private fun NotificationsConfirmationDialog(
    confirmation: NotificationsConfirmation,
    state: NotificationsInboxState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val (title, body, confirmLabel) = when (confirmation) {
        NotificationsConfirmation.DismissAll -> Triple(
            "Dismiss all notifications?",
            "This removes ${notificationsCountLabel(state)?.lowercase() ?: "every notification"} from your account " +
                "on every device. It can't be undone.",
            "Dismiss all",
        )
        is NotificationsConfirmation.Action -> Triple(
            "${confirmation.action.label}?",
            "This asks the server to \"${confirmation.action.label}\" for: " +
                (state.notification(confirmation.notificationId)?.subject ?: "this notification") +
                ". It may not be reversible.",
            confirmation.action.label,
        )
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(body) },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(confirmLabel) }
        },
    )
}

private val NOTIFICATIONS_TWO_PANE_MIN_WIDTH = 840.dp
