package dev.obiente.nextcloudnative.app

/**
 * Persistent, credential-free state for one local-root/remote-root relationship.
 *
 * [localRootId] is an opaque platform grant or bookmark identifier, not an absolute path. Platform
 * executors remain responsible for turning it into filesystem access and for implementing every
 * guarded [FileSyncOperation].
 */
data class FileSyncPair(
    val id: String,
    val accountId: String,
    val localRootId: String,
    val remoteRootPath: String,
    val configuration: FileSyncConfiguration,
    val baselines: List<FileSyncBaseline> = emptyList(),
    val contentVerificationProgress: List<FileSyncContentVerificationProgress> = emptyList(),
    val workItems: List<FileSyncWorkItem> = emptyList(),
    val pendingUploadCleanups: List<FileSyncPendingUploadCleanup> = emptyList(),
    val nextWorkId: Long = 1,
    val lastScanEpochMillis: Long? = null,
    val runFailure: FileSyncRunFailure? = null,
) {
    init {
        requireValidFileSyncPair(this)
    }
}
data class FileSyncCoordinatorState(
    val pairs: List<FileSyncPair> = emptyList(),
) {
    init {
        require(pairs.size <= MAX_FILE_SYNC_PAIRS) { "The sync state contains too many pairs." }
        require(pairs.map(FileSyncPair::id).distinct().size == pairs.size) {
            "The sync state contains duplicate pair IDs."
        }
    }
}

