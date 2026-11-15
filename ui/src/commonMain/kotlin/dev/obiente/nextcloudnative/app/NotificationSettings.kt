package dev.obiente.nextcloudnative.app

/**
 * What the account's notification settings screen can truthfully say.
 *
 * Neither server app lets a client read its notification settings, so every server setting is
 * shown read-only:
 * - Notifications only offers `POST /ocs/v2.php/apps/notifications/api/v2/settings`, which
 *   replaces the email frequency and both sound settings together and returns no values. Its
 *   OpenAPI description has no read route, and saving one setting without the other two would
 *   overwrite them.
 *   https://github.com/nextcloud/notifications/blob/master/lib/Controller/SettingsController.php
 * - Activity saves its settings and RSS feed link only through `POST /index.php/apps/activity/settings`
 *   and `/settings/feed`, web routes that need a browser session token, and reads them only while
 *   rendering its settings page. Its OCS routes cover activities and filters only.
 *   https://github.com/nextcloud/activity/blob/master/appinfo/routes.php
 *   https://github.com/nextcloud/activity/blob/master/lib/Controller/SettingsController.php
 *   https://github.com/nextcloud/activity/blob/master/lib/Settings/Personal.php
 */
internal enum class NotificationSettingSupport {
    /** This app does it with this server. */
    Available,

    /** Neither this app nor this server offers it. */
    Unavailable,

    /** Stored on the server, but no API lets this app read or safely change it. */
    ReadOnly,
}

internal data class NotificationSettingRow(
    val key: String,
    val title: String,
    val description: String,
    val support: NotificationSettingSupport,
)

internal data class NotificationSettingsSection(
    val key: String,
    val title: String,
    val explanation: String?,
    val rows: List<NotificationSettingRow>,
)

internal fun notificationSettingSupportLabel(support: NotificationSettingSupport): String = when (support) {
    NotificationSettingSupport.Available -> "Available"
    NotificationSettingSupport.Unavailable -> "Not available"
    NotificationSettingSupport.ReadOnly -> "Read-only"
}

internal fun notificationSettingsSections(
    availability: NotificationsAvailability,
    activityInstalled: Boolean,
): List<NotificationSettingsSection> = buildList {
    add(appSection(availability))
    if (availability is NotificationsAvailability.Available || availability == NotificationsAvailability.Unsupported) {
        add(notificationsAppSection())
    }
    if (activityInstalled) add(activityAppSection())
}

private fun appSection(availability: NotificationsAvailability): NotificationSettingsSection {
    val capabilities = (availability as? NotificationsAvailability.Available)?.capabilities
    val inbox = when (availability) {
        NotificationsAvailability.Unknown -> "Checking what this server offers."
        is NotificationsAvailability.Available -> "Notifications are read from your server."
        NotificationsAvailability.NotEnabled -> "The Notifications app is not enabled on this server."
        NotificationsAvailability.Unsupported -> "This server's Notifications app does not offer the list this app reads."
    }
    return NotificationSettingsSection(
        key = "app",
        title = "In this app",
        explanation = null,
        rows = listOf(
            NotificationSettingRow("inbox", "Notifications inbox", inbox, supportOf(capabilities?.canList == true)),
            NotificationSettingRow(
                "dismiss",
                "Dismiss",
                if (capabilities?.canDismiss == true) "Removes one notification from your server." else "Not offered by this server.",
                supportOf(capabilities?.canDismiss == true),
            ),
            NotificationSettingRow(
                "dismiss-all",
                "Dismiss all",
                if (capabilities?.canDismissAll == true) "Removes every notification after you confirm." else "Not offered by this server.",
                supportOf(capabilities?.canDismissAll == true),
            ),
            NotificationSettingRow(
                "actions",
                "Notification actions",
                if (capabilities != null) {
                    "Buttons appear only when a notification offers them. Ones that delete something ask first."
                } else {
                    "Not available without the Notifications app."
                },
                supportOf(capabilities != null),
            ),
            NotificationSettingRow(
                "device",
                "Alerts on this device",
                "This app does not show system notifications or receive push yet. The count updates when " +
                    "the account opens, when you open the inbox, and when you refresh.",
                NotificationSettingSupport.Unavailable,
            ),
        ),
    )
}

private fun notificationsAppSection() = NotificationSettingsSection(
    key = "notifications",
    title = "Notifications app",
    explanation = "These are stored on your server. The Notifications app gives apps no way to read them, and " +
        "saving one replaces all three, so this app cannot show or change them without risking your choices. " +
        "Change them in your Nextcloud personal settings under Notifications.",
    rows = listOf(
        readOnlyRow("email-frequency", "Email about missed notifications", "Off, hourly, every 3 hours, daily or weekly."),
        readOnlyRow("sound", "Notification sound", "Plays a sound for new notifications in Nextcloud on the web."),
        readOnlyRow("talk-sound", "Talk notification sound", "Plays a sound for Talk notifications in Nextcloud on the web."),
    ),
)

private fun activityAppSection() = NotificationSettingsSection(
    key = "activity",
    title = "Activity app",
    explanation = "Activity saves these only through its settings page on the web and gives apps no way to read " +
        "them, so this app cannot show or change them. Change them in your Nextcloud personal settings under " +
        "Notifications.",
    rows = listOf(
        readOnlyRow("per-type", "Each kind of activity", "Whether it sends a notification, an email, or both."),
        readOnlyRow("activity-email-frequency", "Activity email frequency", "As soon as possible, hourly, daily or weekly."),
        readOnlyRow("own-actions", "Your own actions", "Whether your own changes appear in the stream and in emails."),
        readOnlyRow("summary", "Activity summary", "An email summary of recent activity."),
        readOnlyRow("rss", "RSS feed", "A private feed link for your activity, created or revoked on the web."),
    ),
)

private fun supportOf(available: Boolean) =
    if (available) NotificationSettingSupport.Available else NotificationSettingSupport.Unavailable

private fun readOnlyRow(key: String, title: String, description: String) =
    NotificationSettingRow(key, title, description, NotificationSettingSupport.ReadOnly)
