package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.obiente.nextcloudnative.app.DesktopFileSyncTrayActivity
import dev.obiente.nextcloudnative.app.DesktopFileSyncTrayActivityPhase
import dev.obiente.nextcloudnative.app.DesktopFileSyncTrayPhase
import dev.obiente.nextcloudnative.app.DesktopFileSyncTrayPopup
import dev.obiente.nextcloudnative.app.DesktopFileSyncTraySnapshot
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay

/**
 * Network-free visual QA for the custom desktop tray popup.
 *
 * The activity, account, paths, and progress are synthetic. The exact production composable is
 * rendered in a real desktop window so host-shell menu theming cannot affect the result.
 */
fun main() = application {
    val outputPath = requireNotNull(System.getenv("NEXTCLOUD_NATIVE_TRAY_QA_OUTPUT")) {
        "NEXTCLOUD_NATIVE_TRAY_QA_OUTPUT must name the screenshot destination."
    }
    val snapshot = DesktopFileSyncTraySnapshot(
        phase = DesktopFileSyncTrayPhase.Syncing,
        pairCount = 4,
        pendingCount = 17,
        conflictCount = 1,
        failedCount = 1,
        message = "Uploading DSF10428.RAF",
        accountLabel = "alex@example.invalid",
        overallProgress = 0.42f,
        lastCheckedEpochMillis = 1_787_526_720_000L,
        activities = listOf(
            DesktopFileSyncTrayActivity(
                stableId = "photos:42",
                relativePath = "2026/Summer festival/Friday/DSF10428.RAF",
                pairLabel = "Camera originals to /Photos/Events",
                phase = DesktopFileSyncTrayActivityPhase.Uploading,
                sizeBytes = 52_848_640L,
                detail = "8 of 17",
            ),
            DesktopFileSyncTrayActivity(
                stableId = "projects:18",
                relativePath = "Campaign/Launch edit/project.kdenlive",
                pairLabel = "Creative projects to /Projects",
                phase = DesktopFileSyncTrayActivityPhase.Completed,
                sizeBytes = 1_835_008L,
                detail = "Synced safely",
            ),
            DesktopFileSyncTrayActivity(
                stableId = "archive:9",
                relativePath = "Documents/2026/Invoice-021.pdf",
                pairLabel = "Documents to /Administration",
                phase = DesktopFileSyncTrayActivityPhase.Conflict,
                sizeBytes = 384_124L,
                detail = "Choose a version",
            ),
            DesktopFileSyncTrayActivity(
                stableId = "photos:43",
                relativePath = "2026/Summer festival/Friday/DSF10428.JPG",
                pairLabel = "Camera originals to /Photos/Events",
                phase = DesktopFileSyncTrayActivityPhase.Waiting,
                sizeBytes = 12_443_648L,
                detail = "RAW files first",
            ),
        ),
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Nextcloud Native tray QA",
        state = rememberWindowState(width = 430.dp, height = 560.dp),
        undecorated = true,
        transparent = true,
        resizable = false,
    ) {
        NextcloudNativeTheme(darkTheme = false) {
            DesktopFileSyncTrayPopup(
                snapshot = snapshot,
                onOpenApp = {},
                onSyncNow = {},
                onTogglePaused = {},
                onQuit = {},
            )
        }
        LaunchedEffect(outputPath) {
            delay(1_500)
            val output = File(outputPath)
            output.parentFile?.mkdirs()
            ImageIO.write(Robot().createScreenCapture(window.bounds), "png", output)
            delay(250)
            exitApplication()
        }
    }
}
