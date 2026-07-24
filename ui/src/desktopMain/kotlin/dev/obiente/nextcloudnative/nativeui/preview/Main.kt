package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.DesktopNextcloudServices
import dev.obiente.nextcloudnative.app.NextcloudNativeApp
import dev.obiente.nextcloudnative.app.ThemePreference
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation

fun main() = application {
    val themePreference = remember { mutableStateOf(ThemePreference.System) }
    val services = remember {
        DesktopNextcloudServices { preference ->
            themePreference.value = preference
        }.also { themePreference.value = it.loadThemePreference() }
    }
    val darkTheme = when (themePreference.value) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    val background = if (darkTheme) DarkWindowBackground else LightWindowBackground

    Window(
        onCloseRequest = ::exitApplication,
        title = "Nextcloud Native",
        state = rememberWindowState(width = 1_280.dp, height = 820.dp),
    ) {
        SideEffect {
            window.background = java.awt.Color(background.toArgb(), true)
            window.minimumSize = java.awt.Dimension(960, 640)
        }
        Box(Modifier.fillMaxSize().background(background)) {
            NextcloudNativeApp(
                services = services,
                presentation = NextcloudPresentation.Desktop,
            )
        }
    }
}

private val DarkWindowBackground = Color(0xFF0D0F13)
private val LightWindowBackground = Color(0xFFF7F6FA)
