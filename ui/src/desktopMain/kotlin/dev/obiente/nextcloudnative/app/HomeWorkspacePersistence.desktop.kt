package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.util.prefs.Preferences

@Composable
internal actual fun rememberHomeWorkspaceLayoutStorage(): HomeWorkspaceLayoutStorage = remember {
    val preferences = Preferences.userRoot().node("dev/obiente/nextcloudnative/home-workspace")
    DesktopHomeWorkspaceLayoutStorage(preferences, desktopHomeWorkspaceLockFile())
}

@Composable
internal actual fun rememberHomeFormFactor(): HomeFormFactor = HomeFormFactor.Desktop
