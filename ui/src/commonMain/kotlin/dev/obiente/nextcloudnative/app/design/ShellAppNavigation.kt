package dev.obiente.nextcloudnative.app.design

import dev.obiente.nextcloudnative.app.canonicalAppWorkspaceId
import androidx.compose.ui.graphics.vector.ImageVector

internal data class NextcloudShellNavigationItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val switchesApps: Boolean,
)

internal fun nextcloudShellNavigationItem(
    item: NextcloudNavigationItem,
    selected: NextcloudDestination,
    activeApp: NextcloudDesktopSidebarApp?,
): NextcloudShellNavigationItem {
    val appSlot = item.destination == NextcloudDestination.Apps
    val activeSlot = appSlot && (activeApp != null || selected == NextcloudDestination.FolderSync)
    return NextcloudShellNavigationItem(
        label = if (appSlot) activeApp?.label ?: if (activeSlot) "Sync" else item.label else item.label,
        icon = when {
            appSlot && activeApp != null -> NextcloudIcons.app(activeApp.id)
            activeSlot -> NextcloudIcons.Folder
            else -> NextcloudIcons.destination(item.destination)
        },
        selected = activeSlot || (activeApp == null && selected == item.destination),
        switchesApps = activeSlot,
    )
}

internal data class NextcloudShellAppItems(
    val pinned: List<NextcloudDesktopSidebarApp>,
    val recent: NextcloudDesktopSidebarApp?,
    val active: NextcloudDesktopSidebarApp?,
    val others: List<NextcloudDesktopSidebarApp>,
) {
    val ordered: List<NextcloudDesktopSidebarApp>
        get() = (pinned + listOfNotNull(recent, active) + others)
            .distinctBy { canonicalAppWorkspaceId(it.id) }
}

/** Same verified app labels and alias handling for the phone switcher and desktop sidebar. */
internal fun nextcloudShellAppItems(
    identity: NextcloudDesktopIdentity?,
    activeAppId: String?,
): NextcloudShellAppItems {
    val pinned = identity?.shortcuts.orEmpty().distinctBy { canonicalAppWorkspaceId(it.id) }
    val recent = identity?.recentApp?.takeUnless { app -> pinned.any { desktopAppIdsMatch(it.id, app.id) } }
    val available = (identity?.availableApps.orEmpty() + pinned + listOfNotNull(recent))
        .distinctBy { canonicalAppWorkspaceId(it.id) }
    val active = activeAppId?.let { id ->
        available.firstOrNull { desktopAppIdsMatch(it.id, id) }
            ?: NextcloudDesktopSidebarApp(id, "Current app")
    }
    val shortcuts = (pinned + listOfNotNull(recent, active)).map { canonicalAppWorkspaceId(it.id) }.toSet()
    return NextcloudShellAppItems(
        pinned = pinned,
        recent = recent,
        active = active,
        others = available.filterNot { canonicalAppWorkspaceId(it.id) in shortcuts }
            .sortedBy { it.label.lowercase() },
    )
}

internal fun filterNextcloudShellApps(
    apps: List<NextcloudDesktopSidebarApp>,
    query: String,
): List<NextcloudDesktopSidebarApp> = apps.filter {
    query.isBlank() || it.label.contains(query.trim(), ignoreCase = true)
}
