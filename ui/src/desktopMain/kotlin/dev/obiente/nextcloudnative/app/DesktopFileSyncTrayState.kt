package dev.obiente.nextcloudnative.app

enum class DesktopFileSyncTrayPhase {
    Idle,
    Syncing,
    Paused,
    NeedsAttention,
}

enum class DesktopFileSyncTrayActivityPhase {
    Uploading,
    Downloading,
    Preparing,
    Waiting,
    Conflict,
    Failed,
    Completed,
}

data class DesktopFileSyncTrayActivity(
    val stableId: String,
    val relativePath: String,
    val pairLabel: String,
    val phase: DesktopFileSyncTrayActivityPhase,
    val sizeBytes: Long? = null,
    val detail: String? = null,
) {
    init {
        require(stableId.isNotBlank() && stableId.length <= 320 && stableId.none(Char::isISOControl))
        require(relativePath.isNotBlank() && relativePath.length <= 4_096 && relativePath.none(Char::isISOControl))
        require(pairLabel.isNotBlank() && pairLabel.length <= 512 && pairLabel.none(Char::isISOControl))
        require(sizeBytes == null || sizeBytes >= 0L)
        require(detail == null || detail.isNotBlank() && detail.length <= 1_024 && detail.none(Char::isISOControl))
    }
}

enum class DesktopFileSyncProgressStage {
    Started,
    Completed,
    Failed,
}

internal data class DesktopFileSyncProgressEvent(
    val pairId: String,
    val workId: Long,
    val relativePath: String,
    val pairLabel: String,
    val operation: FileSyncOperation,
    val completedOperations: Int,
    val totalOperations: Int,
    val sizeBytes: Long?,
    val stage: DesktopFileSyncProgressStage,
    val failureMessage: String? = null,
    val attemptCount: Int = 1,
    val snapshot: DesktopFileSyncSnapshotDiagnostics = DesktopFileSyncSnapshotDiagnostics.Unknown,
    val failureKind: String? = null,
) {
    init {
        require(pairId.isNotBlank())
        require(workId > 0L)
        requireValidSyncPath(relativePath)
        require(pairLabel.isNotBlank())
        require(completedOperations in 0..totalOperations)
        require(totalOperations > 0)
        require(sizeBytes == null || sizeBytes >= 0L)
        require(attemptCount > 0)
        require((stage == DesktopFileSyncProgressStage.Failed) == (failureMessage != null))
        require(failureMessage == null || failureMessage.isNotBlank())
        require(failureKind == null || failureKind.isNotBlank())
    }

    val stableId: String = "$pairId:$workId"
    val progressFraction: Float = completedOperations.toFloat() / totalOperations.toFloat()
}

data class DesktopFileSyncTraySnapshot(
    val phase: DesktopFileSyncTrayPhase,
    val pairCount: Int = 0,
    val pendingCount: Int = 0,
    val conflictCount: Int = 0,
    val failedCount: Int = 0,
    val message: String? = null,
    val accountLabel: String? = null,
    val overallProgress: Float? = null,
    val activities: List<DesktopFileSyncTrayActivity> = emptyList(),
    val lastCheckedEpochMillis: Long? = null,
) {
    init {
        require(listOf(pairCount, pendingCount, conflictCount, failedCount).all { it >= 0 })
        require(message == null || message.isNotBlank())
        require(accountLabel == null || accountLabel.isNotBlank() && accountLabel.none(Char::isISOControl))
        require(overallProgress == null || overallProgress in 0f..1f)
        require(activities.size <= MAX_TRAY_ACTIVITY_ITEMS)
        require(activities.map(DesktopFileSyncTrayActivity::stableId).distinct().size == activities.size)
        require(lastCheckedEpochMillis == null || lastCheckedEpochMillis >= 0L)
    }
}

internal const val MAX_TRAY_ACTIVITY_ITEMS = 8

internal fun FileSyncOperation.toTrayActivityPhase(): DesktopFileSyncTrayActivityPhase = when (this) {
    is FileSyncOperation.Upload -> DesktopFileSyncTrayActivityPhase.Uploading
    is FileSyncOperation.Download -> DesktopFileSyncTrayActivityPhase.Downloading
    is FileSyncOperation.DeleteLocal,
    is FileSyncOperation.DeleteRemote,
    is FileSyncOperation.KeepBoth,
    -> DesktopFileSyncTrayActivityPhase.Preparing
    is FileSyncOperation.NeedsDecision -> DesktopFileSyncTrayActivityPhase.Conflict
    is FileSyncOperation.Skipped -> DesktopFileSyncTrayActivityPhase.Waiting
}

fun DesktopFileSyncTraySnapshot.tooltip(): String = when (phase) {
    DesktopFileSyncTrayPhase.Syncing -> "Nextcloud Native - syncing"
    DesktopFileSyncTrayPhase.Paused -> "Nextcloud Native - sync paused"
    DesktopFileSyncTrayPhase.NeedsAttention -> buildString {
        append("Nextcloud Native - attention needed")
        if (conflictCount > 0) {
            append("; ").append(conflictCount).append(if (conflictCount == 1) " conflict" else " conflicts")
        }
        if (failedCount > 0) append("; ").append(failedCount).append(" failed")
    }
    DesktopFileSyncTrayPhase.Idle -> when {
        pairCount == 0 -> "Nextcloud Native - no sync folders"
        pendingCount > 0 -> "Nextcloud Native - $pendingCount pending"
        else -> "Nextcloud Native - up to date"
    }
}
