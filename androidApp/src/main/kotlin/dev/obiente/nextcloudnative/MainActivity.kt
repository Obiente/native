package dev.obiente.nextcloudnative

import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import dev.obiente.nextcloudnative.app.NextcloudNativeApp
import dev.obiente.nextcloudnative.app.ThemePreference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionTestBootstrap.importIfPresent(applicationContext)
        AndroidNotificationCoordinator(applicationContext).ensureChannels()
        val fileSyncRootPicker = AndroidFileSyncRootPicker(this)
        fileSyncRootPicker.attach(
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                fileSyncRootPicker.complete(uri)
            },
        )
        setContent {
            // Keep the composition and its loaded screen state alive across rotations while still
            // observing the new window configuration so adaptive layouts recompose immediately.
            val configuration = LocalConfiguration.current
            val themePreference = remember { mutableStateOf(ThemePreference.System) }
            val services = remember {
                AndroidNextcloudServices(
                    context = this,
                    fileSyncRootPicker = fileSyncRootPicker,
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

            NextcloudNativeApp(services)
        }
    }

    private companion object {
        val DarkWindowBackground = Color(0xFF0D0F13)
        val LightWindowBackground = Color(0xFFF7F6FA)
    }
}
