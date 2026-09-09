package dev.obiente.nextcloudnative.app.design

import dev.obiente.nextcloudnative.app.canonicalAppWorkspaceId

internal fun desktopAppIdsMatch(appId: String, activeAppId: String?): Boolean =
    activeAppId != null && canonicalAppWorkspaceId(appId) == canonicalAppWorkspaceId(activeAppId)

enum class NextcloudDesktopShortcutKey {
    One,
    Two,
    Three,
    Four,
    Comma,
}

/** Pure shortcut policy so platform key-event plumbing cannot silently change navigation. */
fun destinationForNextcloudDesktopShortcut(
    key: NextcloudDesktopShortcutKey,
    primaryModifierPressed: Boolean,
): NextcloudDestination? {
    if (!primaryModifierPressed) return null
    return when (key) {
        NextcloudDesktopShortcutKey.One -> NextcloudDestination.Home
        NextcloudDesktopShortcutKey.Two -> NextcloudDestination.FolderSync
        NextcloudDesktopShortcutKey.Three -> NextcloudDestination.Activity
        NextcloudDesktopShortcutKey.Four -> NextcloudDestination.Apps
        NextcloudDesktopShortcutKey.Comma -> NextcloudDestination.Settings
    }
}
