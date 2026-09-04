package dev.obiente.nextcloudnative.app

/**
 * Read-only inventory support for the Sync & Offline center.
 *
 * This is deliberately separate from [NextcloudPlatformServices.supportsFileOfflineStorage]:
 * a platform can pin individual files while not yet exposing its durable queue inventory here.
 */
enum class FileOfflineCenterSupport {
    Available,
    InventoryUnavailable,
    Unsupported,
}

enum class FileOfflineFolderAvailability {
    Unsupported,
    /** Recursively downloads server folder contents; never implies upload or bidirectional sync. */
    RecursiveDownloadOnly,
}

data class FileOfflineStorageUsage(
    val usedBytes: Long,
    val capacityBytes: Long?,
    val estimated: Boolean,
) {
    init {
        require(usedBytes >= 0L) { "Offline storage usage cannot be negative." }
        require(capacityBytes == null || capacityBytes > 0L) {
            "Offline storage capacity must be positive."
        }
        require(capacityBytes == null || usedBytes <= capacityBytes) {
            "Offline storage usage cannot exceed its declared capacity."
        }
    }
}

data class FileOfflineCenterItem(
    val key: FileOfflineKey,
    val displayName: String,
    val sizeBytes: Long?,
    val availability: FileOfflineAvailability,
    val detail: String?,
    val canRetry: Boolean,
    val canRemove: Boolean,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= MAX_OFFLINE_CENTER_DISPLAY_NAME_LENGTH)
        require(sizeBytes == null || sizeBytes >= 0L)
        require(detail == null || detail.length <= MAX_OFFLINE_CENTER_DETAIL_LENGTH)
        require(!canRetry || availability in RETRYABLE_OFFLINE_AVAILABILITY) {
            "Only failed or network-waiting offline work can be retried."
        }
    }
}

data class FileOfflineCenterSnapshot(
    val support: FileOfflineCenterSupport,
    val items: List<FileOfflineCenterItem>,
    val storageUsage: FileOfflineStorageUsage?,
    val limitations: List<String>,
    val folderAvailability: FileOfflineFolderAvailability = FileOfflineFolderAvailability.Unsupported,
) {
    init {
        require(items.size <= MAX_OFFLINE_CENTER_ITEMS) { "The offline inventory is too large." }
        require(items.map(FileOfflineCenterItem::key).distinct().size == items.size) {
            "The offline inventory contains duplicate files."
        }
        require(support == FileOfflineCenterSupport.Available || items.isEmpty()) {
            "Unavailable offline inventories cannot expose partial file state."
        }
        require(limitations.size <= MAX_OFFLINE_CENTER_LIMITATIONS)
        require(limitations.all {
            it.isNotBlank() && it.length <= MAX_OFFLINE_CENTER_LIMITATION_LENGTH
        })
    }
}

sealed interface FileOfflineCenterActionResult {
    data class Completed(val message: String) : FileOfflineCenterActionResult {
        init {
            require(message.isNotBlank() && message.length <= MAX_OFFLINE_CENTER_DETAIL_LENGTH)
        }
    }

    data class Rejected(val reason: String) : FileOfflineCenterActionResult {
        init {
            require(reason.isNotBlank() && reason.length <= MAX_OFFLINE_CENTER_DETAIL_LENGTH)
        }
    }

    data class Unsupported(val reason: String) : FileOfflineCenterActionResult {
        init {
            require(reason.isNotBlank() && reason.length <= MAX_OFFLINE_CENTER_DETAIL_LENGTH)
        }
    }
}

fun defaultFileOfflineCenterSnapshot(
    supportsIndividualOfflineFiles: Boolean,
    supportsRecursiveFolderAvailability: Boolean = false,
): FileOfflineCenterSnapshot = if (supportsIndividualOfflineFiles) {
    FileOfflineCenterSnapshot(
        support = FileOfflineCenterSupport.InventoryUnavailable,
        items = emptyList(),
        storageUsage = null,
        limitations = buildOfflineCenterLimitations(
            recursiveFolders = supportsRecursiveFolderAvailability,
            inventoryUnavailable = true,
        ),
        folderAvailability = supportsRecursiveFolderAvailability.toOfflineFolderAvailability(),
    )
} else if (supportsRecursiveFolderAvailability) {
    FileOfflineCenterSnapshot(
        support = FileOfflineCenterSupport.InventoryUnavailable,
        items = emptyList(),
        storageUsage = null,
        limitations = listOf(
            "Selected folders can be kept available offline through the retained-folder controls.",
            "Recursive folder availability downloads server changes; it does not upload local edits or mirror local deletions.",
            OFFLINE_CENTER_NO_BIDIRECTIONAL_SYNC_LIMITATION,
        ),
        folderAvailability = FileOfflineFolderAvailability.RecursiveDownloadOnly,
    )
} else {
    FileOfflineCenterSnapshot(
        support = FileOfflineCenterSupport.Unsupported,
        items = emptyList(),
        storageUsage = null,
        limitations = listOf(
            "Durable offline files are not available on this platform build.",
            OFFLINE_CENTER_NO_BIDIRECTIONAL_SYNC_LIMITATION,
        ),
    )
}

/**
 * Converts a durable queue into a bounded, account-scoped center snapshot.
 *
 * This remains presentation planning only. It does not start work, resolve conflicts, upload
 * local changes, or imply bidirectional folder synchronization.
 */
fun fileOfflineCenterSnapshot(
    state: FileOfflineQueueState,
    accountId: String,
    allowRetry: Boolean,
    allowRemove: Boolean,
    storageCapacityBytes: Long? = null,
    supportsRecursiveFolderAvailability: Boolean = false,
): FileOfflineCenterSnapshot {
    require(accountId.isNotBlank()) { "The offline account ID is required." }
    val accountRecords = state.records
        .asSequence()
        .filter { it.descriptor.key.accountId == accountId }
        .take(MAX_OFFLINE_CENTER_ITEMS + 1)
        .toList()
    require(accountRecords.size <= MAX_OFFLINE_CENTER_ITEMS) { "The offline inventory is too large." }

    val items = accountRecords.map { record ->
        val job = state.job(record.descriptor.key)
        val availability = state.availability(record.descriptor.key)
        FileOfflineCenterItem(
            key = record.descriptor.key,
            displayName = record.descriptor.displayName,
            sizeBytes = record.descriptor.size,
            availability = availability,
            detail = job?.failureMessage ?: record.attentionReason?.readableOfflineConflict(),
            canRetry = allowRetry &&
                availability in RETRYABLE_OFFLINE_AVAILABILITY &&
                job?.operation == FileOfflineJobOperation.Download,
            canRemove = allowRemove && availability != FileOfflineAvailability.Removing,
        )
    }.sortedWith(
        compareBy<FileOfflineCenterItem> { it.availability.offlineCenterPriority() }
            .thenBy { it.displayName.lowercase() }
            .thenBy { it.key.relativePath },
    )

    val storedRecords = accountRecords.filter { it.localRevision != null }
    val knownBytes = storedRecords.fold(0L) { total, record ->
        val size = record.descriptor.size ?: 0L
        if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size
    }
    val boundedUsedBytes = storageCapacityBytes?.let { knownBytes.coerceAtMost(it) } ?: knownBytes
    return FileOfflineCenterSnapshot(
        support = FileOfflineCenterSupport.Available,
        items = items,
        storageUsage = FileOfflineStorageUsage(
            usedBytes = boundedUsedBytes,
            capacityBytes = storageCapacityBytes,
            // Queue descriptors contain remote metadata, not a filesystem allocation reading.
            estimated = true,
        ),
        limitations = buildOfflineCenterLimitations(
            recursiveFolders = supportsRecursiveFolderAvailability,
            inventoryUnavailable = false,
        ),
        folderAvailability = supportsRecursiveFolderAvailability.toOfflineFolderAvailability(),
    )
}

fun FileOfflineAvailability.offlineCenterLabel(): String = when (this) {
    FileOfflineAvailability.OnlineOnly -> "Online only"
    FileOfflineAvailability.Queued -> "Queued"
    FileOfflineAvailability.Downloading -> "Downloading"
    FileOfflineAvailability.Available -> "Available offline"
    FileOfflineAvailability.Removing -> "Removing"
    FileOfflineAvailability.WaitingForNetwork -> "Waiting for network"
    FileOfflineAvailability.Failed -> "Failed"
    FileOfflineAvailability.NeedsAttention -> "Needs attention"
}

private fun FileSyncDecisionReason.readableOfflineConflict(): String = when (this) {
    FileSyncDecisionReason.FirstSyncCollision -> "A local copy already exists and needs review."
    FileSyncDecisionReason.SimultaneousEdit -> "The server file and local copy changed independently."
    FileSyncDecisionReason.UnverifiedLocalContent ->
        "The local copy could not be authenticated for automatic replacement."
    FileSyncDecisionReason.LocalDeletion -> "The local copy was removed after it was downloaded."
    FileSyncDecisionReason.RemoteDeletion -> "The server file was removed after it was downloaded."
    FileSyncDecisionReason.TypeChanged -> "The file type changed and cannot be reconciled automatically."
}

private fun FileOfflineAvailability.offlineCenterPriority(): Int = when (this) {
    FileOfflineAvailability.NeedsAttention -> 0
    FileOfflineAvailability.Failed -> 1
    FileOfflineAvailability.WaitingForNetwork -> 2
    FileOfflineAvailability.Downloading -> 3
    FileOfflineAvailability.Queued -> 4
    FileOfflineAvailability.Removing, FileOfflineAvailability.OnlineOnly -> 5
    FileOfflineAvailability.Available -> 6
}

private fun buildOfflineCenterLimitations(
    recursiveFolders: Boolean,
    inventoryUnavailable: Boolean,
): List<String> = buildList {
    if (inventoryUnavailable) {
        add(
            if (recursiveFolders) {
                "Files and folders can be made available offline, but this platform has not exposed its queue inventory here yet."
            } else {
                "Individual files can be pinned from Files, but this platform has not exposed its queue inventory here yet."
            },
        )
    } else {
        add(
            if (recursiveFolders) {
                "Pinned folders are downloaded recursively into app-private storage for one-way offline availability."
            } else {
                "Offline availability stores app-private copies of explicitly pinned files."
            },
        )
    }
    if (recursiveFolders) {
        add("Recursive folder availability downloads server changes; it does not upload local edits or mirror local deletions.")
    } else {
        add("Recursive folder availability is not supported on this platform.")
    }
    add(OFFLINE_CENTER_NO_BIDIRECTIONAL_SYNC_LIMITATION)
    if (!inventoryUnavailable) {
        add("Conflicts are shown for review and are never resolved by silently uploading a local copy.")
    }
}

private fun Boolean.toOfflineFolderAvailability(): FileOfflineFolderAvailability =
    if (this) {
        FileOfflineFolderAvailability.RecursiveDownloadOnly
    } else {
        FileOfflineFolderAvailability.Unsupported
    }

private val RETRYABLE_OFFLINE_AVAILABILITY = setOf(
    FileOfflineAvailability.Failed,
    FileOfflineAvailability.WaitingForNetwork,
)

private const val OFFLINE_CENTER_NO_BIDIRECTIONAL_SYNC_LIMITATION =
    "Bidirectional folder or vault synchronization is not implemented yet."
private const val MAX_OFFLINE_CENTER_ITEMS = 10_000
private const val MAX_OFFLINE_CENTER_DISPLAY_NAME_LENGTH = 512
private const val MAX_OFFLINE_CENTER_DETAIL_LENGTH = 2_048
private const val MAX_OFFLINE_CENTER_LIMITATIONS = 12
private const val MAX_OFFLINE_CENTER_LIMITATION_LENGTH = 512
