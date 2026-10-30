package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudDestinationBadge
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedControl
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedOption
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

internal enum class ActivityDestinationView {
    Notifications,
    Activity,
}

/**
 * Which view the Activity destination shows. Notifications come first because they are what the
 * shell badge counts; a view the server does not offer is never selected.
 */
internal fun activityDestinationView(
    requested: ActivityDestinationView,
    notificationsAvailability: NotificationsAvailability,
    activityInstalled: Boolean,
): ActivityDestinationView = when {
    notificationsAvailability == NotificationsAvailability.NotEnabled -> ActivityDestinationView.Activity
    !activityInstalled -> ActivityDestinationView.Notifications
    else -> requested
}

/** The shell badge for the Activity destination, which opens on the notifications it counts. */
internal fun notificationsShellBadges(count: Int): Map<NextcloudDestination, NextcloudDestinationBadge> =
    notificationsBadgeLabel(count)?.let { text ->
        mapOf(
            NextcloudDestination.Activity to NextcloudDestinationBadge(
                text = text,
                description = if (count == 1) "1 notification" else "$count notifications",
            ),
        )
    }.orEmpty()

/** The shell's Activity destination: the Notifications inbox and the Activity history. */
@Composable
internal fun ActivityDestinationScreen(
    session: NextcloudSession,
    notifications: NotificationsInboxController,
    activityInstalled: Boolean,
    onOpenLink: (String) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    activity: @Composable () -> Unit,
) {
    var requested by rememberSaveable(session.accountId.storageKey) {
        mutableStateOf(ActivityDestinationView.Notifications)
    }
    val availability = notifications.state.availability
    val view = activityDestinationView(requested, availability, activityInstalled)
    Column(modifier = Modifier.fillMaxSize()) {
        if (availability != NotificationsAvailability.NotEnabled && activityInstalled) {
            val badge = notificationsBadgeLabel(notifications.state.badgeCount)
            NextcloudSegmentedControl(
                options = listOf(
                    NextcloudSegmentedOption(
                        ActivityDestinationView.Notifications.name,
                        if (badge == null) "Notifications" else "Notifications ($badge)",
                    ),
                    NextcloudSegmentedOption(ActivityDestinationView.Activity.name, "Activity"),
                ),
                selectedId = view.name,
                onSelected = { id -> ActivityDestinationView.entries.firstOrNull { it.name == id }?.let { requested = it } },
                accessibilityLabel = "Activity view",
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (view) {
                ActivityDestinationView.Notifications -> NotificationsInboxScreen(
                    controller = notifications,
                    serverUrl = session.serverUrl,
                    onOpenLink = onOpenLink,
                    onOpenSettings = onOpenNotificationSettings,
                )
                ActivityDestinationView.Activity -> activity()
            }
        }
    }
}
