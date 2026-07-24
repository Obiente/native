package dev.obiente.nextcloudnative.app

/**
 * Platform-facing view of a durable local-folder/Nextcloud-folder synchronization pair.
 *
 * The opaque [localRootId] is a persisted SAF grant, bookmark, or equivalent platform handle.
 * It must not contain credentials and is never interpreted by common UI code.
 */
data class FileSyncLocalRoot(
    val localRootId: String,
    val displayName: String,
) {
    init {
        require(localRootId.isSafeFileSyncCenterText(2_048))
        require(displayName.isSafeFileSyncCenterText(256))
    }
}

enum class FileSyncCenterSupport {
    Available,
    Unsupported,
}

enum class MediaSyncFolderDiscoverySupport {
    Available,
    NeedsPermission,
    Unsupported,
}

enum class MediaSyncFolderKind {
    Camera,
    Screenshots,
    Images,
    Videos,
    Mixed,
}

/**
 * A platform-discovered local media folder that can be proposed without another folder browser.
 *
 * [localRootHint] is an opaque platform-owned sync root. Common code may pass it back to the
 * platform, but must never interpret it as a path.
 */
data class MediaSyncFolderSuggestion(
    val localRootHint: String,
    val displayName: String,
    val relativePath: String,
    val kind: MediaSyncFolderKind,
    val imageCount: Int,
    val videoCount: Int,
    val suggestedRemoteRootPath: String,
) {
    init {
        require(localRootHint.isSafeFileSyncCenterText(2_048))
        require(displayName.isSafeFileSyncCenterText(256))
        require(relativePath.isSafeFileSyncCenterText(1_024))
        require(imageCount >= 0 && videoCount >= 0 && imageCount + videoCount > 0)
        requireValidSyncPath(suggestedRemoteRootPath)
    }

    val localRoot: FileSyncLocalRoot
        get() = FileSyncLocalRoot(localRootHint, displayName)
}

data class MediaSyncFolderDiscovery(
    val support: MediaSyncFolderDiscoverySupport,
    val suggestions: List<MediaSyncFolderSuggestion>,
    val message: String? = null,
) {
    init {
        require(suggestions.size <= 128)
        require(suggestions.map(MediaSyncFolderSuggestion::localRootHint).distinct().size == suggestions.size)
        require(support == MediaSyncFolderDiscoverySupport.Available || suggestions.isEmpty())
        require(message == null || message.isSafeFileSyncCenterText(1_024))
    }
}

data class FileSyncPairSummary(
    val id: String,
    val localDisplayName: String,
    val remoteRootPath: String,
    val configuration: FileSyncConfiguration,
    val readyCount: Int,
    val runningCount: Int,
    val conflicts: List<FileSyncConflictSummary>,
    val failedCount: Int,
    val skippedCount: Int,
    val lastScanEpochMillis: Long?,
    val scheduleDescription: String? = null,
) {
    init {
        require(id.isSafeFileSyncCenterText(256))
        require(localDisplayName.isSafeFileSyncCenterText(256))
        if (remoteRootPath.isNotEmpty()) requireValidSyncPath(remoteRootPath)
        require(listOf(readyCount, runningCount, failedCount, skippedCount).all { it >= 0 })
        require(conflicts.size <= 20_000)
        require(conflicts.map(FileSyncConflictSummary::workId).distinct().size == conflicts.size)
        require(lastScanEpochMillis == null || lastScanEpochMillis >= 0L)
        require(scheduleDescription == null || scheduleDescription.isSafeFileSyncCenterText(256))
    }
}

data class FileSyncConflictSummary(
    val workId: Long,
    val relativePath: String,
    val reason: FileSyncDecisionReason,
    val choices: Set<FileSyncDecisionChoice>,
) {
    init {
        require(workId > 0L)
        requireValidSyncPath(relativePath)
        require(choices.isNotEmpty())
    }
}

data class FileSyncCenterSnapshot(
    val support: FileSyncCenterSupport,
    val pairs: List<FileSyncPairSummary>,
    val limitation: String? = null,
) {
    init {
        require(pairs.size <= 64)
        require(pairs.map(FileSyncPairSummary::id).distinct().size == pairs.size)
        require(support == FileSyncCenterSupport.Available || pairs.isEmpty())
        require(limitation == null || limitation.isSafeFileSyncCenterText(1_024))
    }
}

sealed interface FileSyncCenterActionResult {
    data class Completed(val message: String) : FileSyncCenterActionResult {
        init {
            require(message.isSafeFileSyncCenterText(1_024))
        }
    }

    data class Rejected(val reason: String) : FileSyncCenterActionResult {
        init {
            require(reason.isSafeFileSyncCenterText(1_024))
        }
    }

    data class Unsupported(val reason: String) : FileSyncCenterActionResult {
        init {
            require(reason.isSafeFileSyncCenterText(1_024))
        }
    }
}

fun FileSyncPair.toCenterSummary(
    localDisplayName: String,
    scheduleDescription: String? = null,
): FileSyncPairSummary =
    FileSyncPairSummary(
        id = id,
        localDisplayName = localDisplayName,
        remoteRootPath = remoteRootPath,
        configuration = configuration,
        readyCount = workItems.count { it.state == FileSyncExecutionState.Ready },
        runningCount = workItems.count { it.state == FileSyncExecutionState.Running },
        conflicts = workItems.mapNotNull { work ->
            work.decision
                ?.takeIf { work.state == FileSyncExecutionState.AwaitingDecision }
                ?.let { decision ->
                    FileSyncConflictSummary(
                        workId = work.id,
                        relativePath = work.relativePath,
                        reason = decision.reason,
                        choices = decision.choices,
                    )
                }
        },
        failedCount = workItems.count { it.state == FileSyncExecutionState.Failed },
        skippedCount = workItems.count { it.state == FileSyncExecutionState.Skipped },
        lastScanEpochMillis = lastScanEpochMillis,
        scheduleDescription = scheduleDescription,
    )

private fun String.isSafeFileSyncCenterText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)
