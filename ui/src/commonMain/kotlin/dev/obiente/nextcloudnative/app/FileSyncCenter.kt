package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

/**
 * Platform-facing view of a durable local-folder/Nextcloud-folder synchronization pair.
 *
 * The opaque [localRootId] is a persisted SAF grant, bookmark, or equivalent platform handle.
 * It must not contain credentials and is never interpreted by common UI code.
 */
@Serializable
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

@Serializable
enum class MediaSyncFolderKind {
    Camera,
    Screenshots,
    Images,
    Videos,
    Mixed,
}

enum class MediaSyncFolderAccess {
    FullLibrary,
    LimitedSelection,
}

/**
 * A platform-discovered local media folder that can be proposed without another folder browser.
 *
 * [localRootHint] is an opaque platform-owned sync root. Common code may pass it back to the
 * platform, but must never interpret it as a path.
 */
@Serializable
data class MediaSyncFolderSuggestion(
    val localRootHint: String,
    val displayName: String,
    val relativePath: String,
    val kind: MediaSyncFolderKind,
    val imageCount: Int,
    val videoCount: Int,
    val suggestedRemoteRootPath: String,
    val totalBytes: Long = 0L,
) {
    init {
        require(localRootHint.isSafeFileSyncCenterText(2_048))
        require(displayName.isSafeFileSyncCenterText(256))
        require(relativePath.isSafeFileSyncCenterText(1_024))
        require(imageCount >= 0 && videoCount >= 0 && imageCount.toLong() + videoCount.toLong() > 0L)
        require(totalBytes >= 0L)
        requireValidSyncPath(suggestedRemoteRootPath)
    }

    val localRoot: FileSyncLocalRoot
        get() = FileSyncLocalRoot(localRootHint, displayName)
}

data class MediaSyncFolderDiscovery(
    val support: MediaSyncFolderDiscoverySupport,
    val suggestions: List<MediaSyncFolderSuggestion>,
    val message: String? = null,
    val access: MediaSyncFolderAccess? =
        if (support == MediaSyncFolderDiscoverySupport.Available) {
            MediaSyncFolderAccess.FullLibrary
        } else {
            null
        },
) {
    init {
        require(suggestions.size <= 128)
        require(suggestions.map(MediaSyncFolderSuggestion::localRootHint).distinct().size == suggestions.size)
        require(support == MediaSyncFolderDiscoverySupport.Available || suggestions.isEmpty())
        require(message == null || message.isSafeFileSyncCenterText(1_024))
        require((support == MediaSyncFolderDiscoverySupport.Available) == (access != null))
    }
}

enum class MediaSyncFolderPreviewState {
    Available,
    Empty,
    Inaccessible,
    Changed,
    Removed,
}

data class MediaSyncFolderPreviewItem(
    val stableId: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val modifiedAtEpochMillis: Long?,
    val thumbnailBytes: ByteArray?,
) {
    init {
        require(stableId.isSafeFileSyncCenterText(256))
        require(displayName.isSafeFileSyncCenterText(512))
        require(mimeType == null || mimeType.isSafeFileSyncCenterText(256))
        require(sizeBytes == null || sizeBytes >= 0L)
        require(modifiedAtEpochMillis == null || modifiedAtEpochMillis >= 0L)
        require(thumbnailBytes == null || thumbnailBytes.size <= MAX_MEDIA_PREVIEW_THUMBNAIL_BYTES)
    }
}

data class MediaSyncFolderPreview(
    val localRootHint: String,
    val state: MediaSyncFolderPreviewState,
    val access: MediaSyncFolderAccess,
    val totalItems: Int,
    val totalBytes: Long,
    val items: List<MediaSyncFolderPreviewItem>,
    val message: String? = null,
) {
    init {
        require(localRootHint.isSafeFileSyncCenterText(2_048))
        require(totalItems >= 0)
        require(totalBytes >= 0L)
        require(items.size <= MAX_MEDIA_SYNC_FOLDER_PREVIEW_ITEMS)
        require(items.map(MediaSyncFolderPreviewItem::stableId).distinct().size == items.size)
        require(message == null || message.isSafeFileSyncCenterText(1_024))
        require(
            state == MediaSyncFolderPreviewState.Available ||
                state == MediaSyncFolderPreviewState.Changed ||
                items.isEmpty(),
        )
    }
}

const val MAX_MEDIA_SYNC_FOLDER_PREVIEW_ITEMS = 12
const val MAX_MEDIA_PREVIEW_THUMBNAIL_BYTES = 256 * 1_024

enum class FileSyncPairRunState {
    Active,
    Paused,
}

enum class FileSyncNetworkState {
    Unknown,
    Available,
    WaitingForNetwork,
}

data class FileSyncPairSummary(
    val id: String,
    val localDisplayName: String,
    val localRootPath: String? = null,
    val remoteRootPath: String,
    val configuration: FileSyncConfiguration,
    val readyCount: Int,
    val runningCount: Int,
    val conflicts: List<FileSyncConflictSummary>,
    val conflictCount: Int = conflicts.size,
    val failedCount: Int,
    val skippedCount: Int,
    val completedCount: Int = 0,
    val lastScanEpochMillis: Long?,
    val scheduleDescription: String? = null,
    val skippedReasons: List<String> = emptyList(),
    val runState: FileSyncPairRunState = FileSyncPairRunState.Active,
    val networkState: FileSyncNetworkState = FileSyncNetworkState.Unknown,
) {
    init {
        require(id.isSafeFileSyncCenterText(256))
        require(localDisplayName.isSafeFileSyncCenterText(256))
        require(localRootPath == null || localRootPath.isSafeFileSyncCenterText(2_048))
        if (remoteRootPath.isNotEmpty()) requireValidSyncPath(remoteRootPath)
        require(
            listOf(readyCount, runningCount, conflictCount, failedCount, skippedCount, completedCount)
                .all { it >= 0 },
        )
        require(conflicts.size <= MAX_FILE_SYNC_WORK_ITEMS)
        require(conflictCount >= conflicts.size && (conflictCount == 0) == conflicts.isEmpty())
        require(conflicts.map(FileSyncConflictSummary::workId).distinct().size == conflicts.size)
        require(lastScanEpochMillis == null || lastScanEpochMillis >= 0L)
        require(scheduleDescription == null || scheduleDescription.isSafeFileSyncCenterText(256))
        require(skippedReasons.size <= 20)
        require(skippedReasons.all { reason -> reason.isSafeFileSyncCenterText(1_024) })
    }
}

data class FileSyncConflictSummary(
    val workId: Long,
    val relativePath: String,
    val reason: FileSyncDecisionReason,
    val choices: Set<FileSyncDecisionChoice>,
    val local: FileSyncConflictSideSummary? = null,
    val remote: FileSyncConflictSideSummary? = null,
) {
    init {
        require(workId > 0L)
        requireValidSyncPath(relativePath)
        require(choices.isNotEmpty())
    }
}

data class FileSyncConflictSideSummary(
    val kind: SyncEntryKind,
    val sizeBytes: Long? = null,
    val modifiedEpochMillis: Long? = null,
) {
    init {
        require(sizeBytes == null || sizeBytes >= 0L)
        require(modifiedEpochMillis == null || modifiedEpochMillis >= 0L)
    }
}

internal const val FILE_SYNC_CONFLICT_PAGE_SIZE = 5

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

    data class Rejected(
        val reason: String,
        val scope: FileSyncRejectionScope = FileSyncRejectionScope.Items,
    ) : FileSyncCenterActionResult {
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

enum class FileSyncRejectionScope {
    Preflight,
    Items,
}

fun FileSyncPair.toCenterSummary(
    localDisplayName: String,
    localRootPath: String? = null,
    scheduleDescription: String? = null,
    completedCount: Int = baselines.size,
    readyCount: Int = workItems.count { it.state == FileSyncExecutionState.Ready },
    runningCount: Int = workItems.count { it.state == FileSyncExecutionState.Running },
    conflictCount: Int = workItems.count { it.state == FileSyncExecutionState.AwaitingDecision },
    failedCount: Int = workItems.count { it.state == FileSyncExecutionState.Failed },
    skippedCount: Int = workItems.count { it.state == FileSyncExecutionState.Skipped },
    skippedReasons: List<String> = workItems.mapNotNull { work ->
        (work.operation as? FileSyncOperation.Skipped)
            ?.takeIf { work.state == FileSyncExecutionState.Skipped }
            ?.reason
    }.distinct().take(20),
    runState: FileSyncPairRunState,
    networkState: FileSyncNetworkState,
): FileSyncPairSummary =
    FileSyncPairSummary(
        id = id,
        localDisplayName = localDisplayName,
        localRootPath = localRootPath,
        remoteRootPath = remoteRootPath,
        configuration = configuration,
        readyCount = readyCount,
        runningCount = runningCount,
        conflicts = workItems.mapNotNull { work ->
            work.decision
                ?.takeIf { work.state == FileSyncExecutionState.AwaitingDecision }
                ?.let { decision ->
                    FileSyncConflictSummary(
                        workId = work.id,
                        relativePath = work.relativePath,
                        reason = decision.reason,
                        choices = decision.choices,
                        local = work.observedLocal?.let { local ->
                            FileSyncConflictSideSummary(
                                kind = local.kind,
                                sizeBytes = local.size,
                                modifiedEpochMillis = local.modifiedEpochMillis,
                            )
                        },
                        remote = work.observedRemote?.let { remote ->
                            FileSyncConflictSideSummary(
                                kind = remote.kind,
                                sizeBytes = remote.size,
                                modifiedEpochMillis = remote.modifiedEpochMillis,
                            )
                        },
                    )
                }
        },
        conflictCount = conflictCount,
        failedCount = failedCount,
        skippedCount = skippedCount,
        completedCount = completedCount,
        lastScanEpochMillis = lastScanEpochMillis,
        scheduleDescription = scheduleDescription,
        skippedReasons = skippedReasons,
        runState = runState,
        networkState = networkState,
    )

fun liveFileSyncNetworkState(
    networkAvailable: Boolean?,
    unmeteredNetwork: Boolean?,
    networkPolicy: FileSyncNetworkPolicy,
): FileSyncNetworkState = when {
    networkAvailable == false -> FileSyncNetworkState.WaitingForNetwork
    networkAvailable != true -> FileSyncNetworkState.Unknown
    networkPolicy == FileSyncNetworkPolicy.AnyConnection -> FileSyncNetworkState.Available
    unmeteredNetwork == true -> FileSyncNetworkState.Available
    unmeteredNetwork == false -> FileSyncNetworkState.WaitingForNetwork
    else -> FileSyncNetworkState.Unknown
}

private fun String.isSafeFileSyncCenterText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)
