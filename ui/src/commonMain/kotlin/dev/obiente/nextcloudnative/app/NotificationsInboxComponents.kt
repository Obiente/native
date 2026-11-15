package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import kotlin.time.Clock

/** Label for opening a notification's link, or null when the link cannot be opened. */
internal fun notificationOpenLabel(serverUrl: String, link: String?): String? = when (
    val destination = link?.let { nextcloudLinkDestination(serverUrl, it) }
) {
    null, is NextcloudLinkDestination.Rejected -> null
    is NextcloudLinkDestination.Browser -> "Open in browser"
    else -> "Open"
}

internal fun notificationsCountLabel(state: NotificationsInboxState): String? {
    val notifications = state.notifications ?: return null
    if (state.availability is NotificationsAvailability.NotEnabled || state.availability is NotificationsAvailability.Unsupported) {
        return null
    }
    return when (notifications.size) {
        0 -> "No notifications"
        1 -> "1 notification"
        else -> "${notifications.size} notifications"
    }
}

/** Shell badge text: hidden at zero, capped so the navigation item keeps its size. */
internal fun notificationsBadgeLabel(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/** Names for server apps that send notifications without an app navigation entry to name them. */
internal fun notificationAppLabel(appId: String): String = when (appId.lowercase()) {
    "spreed", "talk" -> "Talk"
    "files_sharing", "federatedfilesharing" -> "Sharing"
    "updatenotification" -> "Updates"
    "admin_notifications" -> "Administration"
    "twofactor_backupcodes" -> "Security"
    "dav" -> "Calendar and contacts"
    else -> appId.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}

@Composable
internal fun NotificationRow(
    notification: NextcloudNotification,
    state: NotificationsInboxState,
    selected: Boolean,
    openLabel: String?,
    controller: NotificationsInboxController,
    onOpenLink: (String) -> Unit,
) {
    var menuOpen by remember(notification.id) { mutableStateOf(false) }
    val pending = notification.id in state.pendingIds
    val menuActions = listOfNotNull(
        openLabel?.let { label ->
            NextcloudCardAction(label = label, onClick = { notification.link?.let(onOpenLink) })
        },
        NextcloudCardAction(
            label = "Dismiss",
            enabled = state.canDismiss(notification),
            onClick = { controller.dismiss(notification.id) },
        ).takeIf { state.capabilities?.canDismiss == true },
    )
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .nextcloudCardInteractions(
                    onOpen = { controller.select(notification.id) },
                    onShowActions = { menuOpen = true }.takeIf { menuActions.isNotEmpty() },
                    openLabel = "Show notification",
                    actionsLabel = "Notification actions",
                )
                .semantics { if (pending) stateDescription = "Updating" }
                .padding(start = NextcloudSpacing.Large, top = NextcloudSpacing.Medium, bottom = NextcloudSpacing.Medium),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                NextcloudIcons.app(notification.app),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp, end = NextcloudSpacing.Medium).size(20.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    notification.subject,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                notification.message?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    notificationMetaLabel(notification),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                NotificationServerActions(notification, state, controller, onOpenLink, compact = true)
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = NextcloudSpacing.Small, end = NextcloudSpacing.Small).size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
            NextcloudCardOverflow(
                itemLabel = notification.subject,
                actions = menuActions,
                expanded = menuOpen,
                onExpandedChange = { menuOpen = it },
            )
        }
    }
}

/** Buttons for the actions the server advertised. Nothing is shown when it advertised none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationServerActions(
    notification: NextcloudNotification,
    state: NotificationsInboxState,
    controller: NotificationsInboxController,
    onOpenLink: (String) -> Unit,
    compact: Boolean,
) {
    if (notification.actions.isEmpty()) return
    val enabled = state.canRunActions(notification)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        modifier = Modifier.padding(top = if (compact) NextcloudSpacing.XSmall else 0.dp),
    ) {
        notification.actions.forEach { action ->
            val onClick = {
                if (action.method == NotificationActionMethod.Web) {
                    onOpenLink(action.link)
                } else {
                    controller.runAction(notification.id, action)
                }
            }
            val actionEnabled = action.method == NotificationActionMethod.Web || enabled
            when {
                action.destructive -> OutlinedButton(onClick = onClick, enabled = actionEnabled) {
                    Text(action.label, color = if (actionEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                action.primary -> FilledTonalButton(onClick = onClick, enabled = actionEnabled) { Text(action.label) }
                else -> OutlinedButton(onClick = onClick, enabled = actionEnabled) { Text(action.label) }
            }
        }
    }
}

@Composable
internal fun NotificationDetail(
    notification: NextcloudNotification,
    state: NotificationsInboxState,
    serverUrl: String,
    controller: NotificationsInboxController,
    onOpenLink: (String) -> Unit,
) {
    val openLabel = notificationOpenLabel(serverUrl, notification.link)
    val pending = notification.id in state.pendingIds
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Column(modifier = Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Icon(NextcloudIcons.app(notification.app), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(notificationMetaLabel(notification), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(notification.subject, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            notification.message?.let { message -> Text(message, style = MaterialTheme.typography.bodyLarge) }
            if (notification.id in state.ambiguousActionIds) {
                Text(
                    "An earlier action's result is unknown. Check it before doing anything else here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            NotificationServerActions(notification, state, controller, onOpenLink, compact = false)
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
                if (openLabel != null) {
                    Button(onClick = { notification.link?.let(onOpenLink) }) { Text(openLabel) }
                }
                if (state.capabilities?.canDismiss == true) {
                    TextButton(onClick = { controller.dismiss(notification.id) }, enabled = state.canDismiss(notification)) {
                        Text(if (pending) "Working..." else "Dismiss")
                    }
                }
            }
        }
    }
}

@Composable
internal fun NotificationDetailPlaceholder(state: NotificationsInboxState) {
    EmptyMessage(if (!state.notifications.isNullOrEmpty()) "Select a notification to see it here." else "")
}

@Composable
internal fun NotificationsMessagePane(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(NextcloudIcons.Activity, contentDescription = null, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = NextcloudSpacing.Medium))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = NextcloudSpacing.Small),
        )
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = NextcloudSpacing.Large)) { Text(actionLabel) }
        }
    }
}

@Composable
internal fun NotificationsNotice(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun notificationMetaLabel(notification: NextcloudNotification): String = listOfNotNull(
    notificationAppLabel(notification.app),
    notification.publishedAtEpochSeconds?.let { compactAgeLabel(it, Clock.System.now().epochSeconds) },
).joinToString(" - ")
