package dev.obiente.nextcloudnative

import android.os.Bundle
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import dev.obiente.nextcloudnative.app.NextcloudNativeApp
import dev.obiente.nextcloudnative.app.ThemePreference

class MainActivity : ComponentActivity() {
    private var appUpdateReviewRequest by mutableLongStateOf(0L)
    private var platformCapabilityRefreshRequest by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveNotificationIntent(intent)
        SessionTestBootstrap.importIfPresent(applicationContext)
        AndroidNotificationCoordinator(applicationContext).ensureChannels()
        AndroidAppUpdateWork.schedule(
            applicationContext,
            AndroidProjectContentClient(applicationContext, null).updatePreferences(),
        )
        val fileSyncRootPicker = AndroidFileSyncRootPicker(this)
        fileSyncRootPicker.attach(
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                fileSyncRootPicker.complete(uri)
            },
        )
        val localUploadPicker = AndroidLocalUploadPicker(this)
        localUploadPicker.attach(
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                localUploadPicker.complete(uri)
            },
        )
        val platformPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            platformCapabilityRefreshRequest += 1
        }
        setContent {
            // Keep the composition and its loaded screen state alive across rotations while still
            // observing the new window configuration so adaptive layouts recompose immediately.
            val configuration = LocalConfiguration.current
            val themePreference = remember { mutableStateOf(ThemePreference.System) }
            val services = remember {
                AndroidNextcloudServices(
                    context = this,
                    fileSyncRootPicker = fileSyncRootPicker,
                    localUploadPicker = localUploadPicker,
                    requestPlatformPermissions = { permissions ->
                        platformPermissionLauncher.launch(permissions)
                        true
                    },
                    onThemePreferenceChanged = { preference ->
                        themePreference.value = preference
                    },
                ).also { themePreference.value = it.loadThemePreference() }
            }
            val darkTheme = when (themePreference.value) {
                ThemePreference.System ->
                    configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
                ThemePreference.Light -> false
                ThemePreference.Dark -> true
            }
            val background = if (darkTheme) DarkWindowBackground else LightWindowBackground

            SideEffect {
                val transparent = android.graphics.Color.TRANSPARENT
                val systemBarStyle = if (darkTheme) {
                    SystemBarStyle.dark(transparent)
                } else {
                    SystemBarStyle.light(transparent, transparent)
                }
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle,
                    navigationBarStyle = systemBarStyle,
                )
                window.decorView.setBackgroundColor(background.toArgb())
            }

            NextcloudNativeApp(
                services = services,
                appUpdateReviewRequest = appUpdateReviewRequest,
                platformCapabilityRefreshRequest = platformCapabilityRefreshRequest,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        platformCapabilityRefreshRequest += 1
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveNotificationIntent(intent)
    }

    private fun receiveNotificationIntent(intent: Intent?) {
        if (isAppUpdateReviewIntentAction(intent?.action)) {
            appUpdateReviewRequest += 1
        }
    }

    private companion object {
        val DarkWindowBackground = Color(0xFF0D0F13)
        val LightWindowBackground = Color(0xFFF7F6FA)
    }
}

internal fun isAppUpdateReviewIntentAction(action: String?): Boolean =
    action == "dev.obiente.nextcloudnative.notification.$ACTION_REVIEW_APP_UPDATE"
