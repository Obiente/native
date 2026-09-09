package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.obiente.nextcloudnative.app.FileSyncCenterSnapshot
import dev.obiente.nextcloudnative.app.FileSyncCenterSupport
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncConflictSideSummary
import dev.obiente.nextcloudnative.app.FileSyncConflictSummary
import dev.obiente.nextcloudnative.app.FileSyncDecisionChoice
import dev.obiente.nextcloudnative.app.FileSyncDecisionReason
import dev.obiente.nextcloudnative.app.FileSyncNetworkState
import dev.obiente.nextcloudnative.app.FileSyncPairRunState
import dev.obiente.nextcloudnative.app.FileSyncPairSummary
import dev.obiente.nextcloudnative.app.FileSyncWorkspace
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay

/** Network-free visual QA for the production responsive folder-sync workspace. */
fun main() = application {
    val outputPath = requireNotNull(System.getenv("NEXTCLOUD_NATIVE_FILESYNC_QA_OUTPUT"))
    val width = requireNotNull(System.getenv("NEXTCLOUD_NATIVE_FILESYNC_QA_WIDTH")).toInt()
    val height = requireNotNull(System.getenv("NEXTCLOUD_NATIVE_FILESYNC_QA_HEIGHT")).toInt()
    val conflictChoices = setOf(
        FileSyncDecisionChoice.UseLocal,
        FileSyncDecisionChoice.UseRemote,
        FileSyncDecisionChoice.KeepBoth,
        FileSyncDecisionChoice.Skip,
    )
    val conflicts = listOf(
        FileSyncConflictSummary(
            workId = 1,
            relativePath = "Projects/Launch/brief.md",
            reason = FileSyncDecisionReason.SimultaneousEdit,
            choices = conflictChoices,
            local = FileSyncConflictSideSummary(SyncEntryKind.File, 18_432, 1_787_526_300_000L),
            remote = FileSyncConflictSideSummary(SyncEntryKind.File, 19_104, 1_787_526_500_000L),
        ),
        FileSyncConflictSummary(
            workId = 2,
            relativePath = "Projects/Launch/budget.ods",
            reason = FileSyncDecisionReason.FirstSyncCollision,
            choices = conflictChoices,
            local = FileSyncConflictSideSummary(SyncEntryKind.File, 84_128, 1_787_520_000_000L),
            remote = FileSyncConflictSideSummary(SyncEntryKind.File, 82_944, 1_787_522_000_000L),
        ),
    )
    val snapshot = FileSyncCenterSnapshot(
        support = FileSyncCenterSupport.Available,
        pairs = listOf(
            FileSyncPairSummary(
                id = "documents",
                localDisplayName = "Documents",
                localRootPath = "/storage/Documents",
                remoteRootPath = "Documents",
                configuration = FileSyncConfiguration(deviceLabel = "Alex laptop"),
                readyCount = 0,
                runningCount = 0,
                conflicts = conflicts,
                conflictCount = 7,
                failedCount = 0,
                skippedCount = 0,
                completedCount = 428,
                lastScanEpochMillis = 1_787_526_720_000L,
                scheduleDescription = "Every 15 minutes",
                networkState = FileSyncNetworkState.Available,
            ),
            FileSyncPairSummary(
                id = "photos",
                localDisplayName = "Camera originals",
                localRootPath = "/storage/Pictures/Camera",
                remoteRootPath = "Photos/Camera",
                configuration = FileSyncConfiguration(deviceLabel = "Alex laptop"),
                readyCount = 12,
                runningCount = 1,
                conflicts = emptyList(),
                failedCount = 0,
                skippedCount = 3,
                completedCount = 2_841,
                lastScanEpochMillis = 1_787_526_700_000L,
                scheduleDescription = "Watching for changes",
                runState = FileSyncPairRunState.Active,
                networkState = FileSyncNetworkState.Available,
            ),
        ),
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "nati.ve folder sync QA",
        state = rememberWindowState(width = width.dp, height = height.dp),
    ) {
        NextcloudNativeTheme(darkTheme = false) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                FileSyncWorkspace(
                    snapshot = snapshot,
                    loading = true,
                    busyPairId = "photos",
                    busyPairIds = setOf("photos"),
                    onAdd = {},
                    onRun = {},
                    onRemove = {},
                    onResolve = { _, _, _ -> },
                    onResolveBatch = { _, _, _ -> },
                    initialSelectedPairId = "documents",
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    fillAvailableHeight = true,
                )
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
