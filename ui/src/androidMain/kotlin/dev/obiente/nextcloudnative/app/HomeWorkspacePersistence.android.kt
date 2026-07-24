package dev.obiente.nextcloudnative.app

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberHomeWorkspaceLayoutStorage(): HomeWorkspaceLayoutStorage {
    val applicationContext = LocalContext.current.applicationContext
    return remember(applicationContext) {
        val preferences = applicationContext.getSharedPreferences(
            HOME_WORKSPACE_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        object : HomeWorkspaceLayoutStorage {
            override fun read(persistenceKey: String): String? =
                preferences.getString(persistenceKey, null)

            override fun write(persistenceKey: String, encodedSnapshot: String) {
                check(preferences.edit().putString(persistenceKey, encodedSnapshot).commit()) {
                    "The home workspace could not be persisted."
                }
            }
        }
    }
}

@Composable
internal actual fun rememberHomeFormFactor(): HomeFormFactor {
    val configuration = LocalConfiguration.current
    return remember(configuration.smallestScreenWidthDp) {
        if (
            configuration.smallestScreenWidthDp != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED &&
            configuration.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP
        ) {
            HomeFormFactor.Tablet
        } else {
            HomeFormFactor.Phone
        }
    }
}

private const val HOME_WORKSPACE_PREFERENCES = "nextcloud_native_home_workspace"
private const val TABLET_SMALLEST_WIDTH_DP = 600
