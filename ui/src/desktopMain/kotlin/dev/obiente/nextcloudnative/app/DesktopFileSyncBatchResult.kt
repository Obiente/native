package dev.obiente.nextcloudnative.app

internal fun desktopFileSyncBatchResult(
    failures: Int,
    stopped: Int,
    waitingForConditions: Int,
    paused: Boolean,
): FileSyncCenterActionResult {
    require(failures >= 0 && stopped >= 0 && waitingForConditions >= 0)
    return when {
        failures > 0 -> FileSyncCenterActionResult.Rejected(
            "$failures desktop sync ${if (failures == 1) "folder needs" else "folders need"} attention.",
        )
        paused -> FileSyncCenterActionResult.Stopped("Desktop syncing paused before all folders were checked.")
        stopped > 0 -> FileSyncCenterActionResult.Stopped(
            "$stopped desktop sync ${if (stopped == 1) "folder stopped" else "folders stopped"} " +
                "before the check completed.",
        )
        waitingForConditions > 0 -> FileSyncCenterActionResult.Completed(
            "$waitingForConditions desktop sync " +
                "${if (waitingForConditions == 1) "folder is" else "folders are"} " +
                "waiting for network or power rules.",
        )
        else -> FileSyncCenterActionResult.Completed("All desktop sync folders were checked.")
    }
}
