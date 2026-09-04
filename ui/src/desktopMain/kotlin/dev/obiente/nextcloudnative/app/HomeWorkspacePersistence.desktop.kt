package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.util.prefs.Preferences

@Composable
internal actual fun rememberHomeWorkspaceLayoutStorage(): HomeWorkspaceLayoutStorage = remember {
    val preferences = Preferences.userRoot().node("dev/obiente/nextcloudnative/home-workspace")
    object : HomeWorkspaceLayoutStorage {
        override fun read(persistenceKey: String): String? =
            preferences.get(persistenceKey, null)

        override fun write(persistenceKey: String, encodedSnapshot: String) {
            synchronized(DESKTOP_HOME_WORKSPACE_STORAGE_LOCK) {
                preferences.put(persistenceKey, encodedSnapshot)
                preferences.flush()
            }
        }

        override fun writeIfAbsent(persistenceKey: String, encodedSnapshot: String): Boolean =
            synchronized(DESKTOP_HOME_WORKSPACE_STORAGE_LOCK) {
                if (preferences.get(persistenceKey, null) != null) {
                    false
                } else {
                    preferences.put(persistenceKey, encodedSnapshot)
                    preferences.flush()
                    true
                }
            }
    }
}

@Composable
internal actual fun rememberHomeFormFactor(): HomeFormFactor = HomeFormFactor.Desktop

private val DESKTOP_HOME_WORKSPACE_STORAGE_LOCK = Any()
