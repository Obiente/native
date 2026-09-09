package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.obiente.nextcloudnative.app.DesktopBackgroundSettingsCard
import dev.obiente.nextcloudnative.app.DesktopStartOnLoginSettingsCard
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay

/** Network-free visual QA for the production desktop lifecycle settings. */
fun main() = application {
    val outputPath = requireNotNull(System.getenv("NEXTCLOUD_NATIVE_BACKGROUND_SETTINGS_QA_OUTPUT")) {
        "NEXTCLOUD_NATIVE_BACKGROUND_SETTINGS_QA_OUTPUT must name the screenshot destination."
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "nati.ve desktop settings QA",
        state = rememberWindowState(width = 760.dp, height = 620.dp),
    ) {
        NextcloudNativeTheme(darkTheme = false) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.padding(32.dp)) {
                    Text("Desktop app", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Choose how nati.ve behaves outside its main window.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item {
                            DesktopBackgroundSettingsCard(enabled = true, onEnabledChanged = {})
                        }
                        item {
                            DesktopStartOnLoginSettingsCard(
                                enabled = true,
                                message = "nati.ve will start in your desktop session and recover after a crash.",
                                onEnabledChanged = {},
                            )
                        }
                    }
                }
            }
        }
        LaunchedEffect(outputPath) {
            delay(1_500L)
            val output = File(outputPath)
            output.parentFile?.mkdirs()
            ImageIO.write(Robot().createScreenCapture(window.bounds), "png", output)
            delay(250L)
            exitApplication()
        }
    }
}
