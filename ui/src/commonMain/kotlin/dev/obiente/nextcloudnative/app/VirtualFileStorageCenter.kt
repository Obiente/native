package dev.obiente.nextcloudnative.app

enum class VirtualFileStorageSupport {
    Available,
    CacheOnly,
    Unsupported,
}

enum class VirtualFilePlatformIntegration {
    AndroidDocumentsProvider,
    LinuxFilesystemMount,
    InAppOnDemandCache,
    WindowsCloudFiles,
    AppleFileProvider,
}

enum class VirtualFileProviderState {
    Active,
    Inactive,
    Starting,
    NeedsAttention,
    NotApplicable,
}

data class VirtualFileStorageSnapshot(
    val support: VirtualFileStorageSupport,
    val integration: VirtualFilePlatformIntegration?,
    val policy: VirtualFileCachePolicy,
    val cachedBytes: Long,
    val reclaimableBytes: Long,
    val pinnedBytes: Long,
    val hydratedFileCount: Int,
    val pinnedFileCount: Int,
    val availableFreeBytes: Long?,
    val storageCapacityBytes: Long?,
    val limitations: List<String> = emptyList(),
    val providerState: VirtualFileProviderState = VirtualFileProviderState.NotApplicable,
    val providerActive: Boolean = false,
    val providerLocation: String? = null,
    val providerLocationConfiguration: VirtualFileProviderLocation? = null,
    val providerLocationCanChange: Boolean = false,
    val providerRecoveryNotice: String? = null,
    val folderRetentionRules: List<VirtualFolderRetentionRule> = emptyList(),
    val folderHydrationStatuses: List<VirtualFolderHydrationStatus> = emptyList(),
    val pendingWritebackCount: Int = 0,
    val cacheTiers: VirtualFileCacheTierConfiguration? = null,
    val primaryCache: VirtualFileCacheTierSnapshot? = null,
    val overflowCache: VirtualFileCacheTierSnapshot? = null,
) {
    init {
        require(cachedBytes >= 0L)
        require(reclaimableBytes in 0L..cachedBytes)
        require(pinnedBytes >= 0L)
        require(hydratedFileCount >= 0)
        require(pinnedFileCount >= 0)
        require(availableFreeBytes == null || availableFreeBytes >= 0L)
        require(storageCapacityBytes == null || storageCapacityBytes > 0L)
        require(
            availableFreeBytes == null || storageCapacityBytes == null ||
                availableFreeBytes <= storageCapacityBytes,
        )
        require(support != VirtualFileStorageSupport.Unsupported || integration == null)
        require(support != VirtualFileStorageSupport.Unsupported || cachedBytes == 0L)
        require(limitations.size <= MAX_VIRTUAL_FILE_LIMITATIONS)
        require(limitations.all { it.isNotBlank() && it.length <= MAX_VIRTUAL_FILE_LIMITATION_LENGTH })
        require(
            !providerActive ||
                providerState == VirtualFileProviderState.Active ||
                providerState == VirtualFileProviderState.NeedsAttention,
        )
        require(providerState != VirtualFileProviderState.Active || providerActive)
        require(providerLocation == null || providerLocation.isNotBlank())
        require(
            providerRecoveryNotice == null ||
                providerRecoveryNotice.isNotBlank() &&
                providerRecoveryNotice.length <= MAX_VIRTUAL_FILE_ACTION_MESSAGE_LENGTH
        )
        require(providerLocationConfiguration == null || support == VirtualFileStorageSupport.Available)
        require(!providerLocationCanChange || providerLocationConfiguration != null)
        VirtualFolderRetentionState(folderRetentionRules)
        require(folderHydrationStatuses.map(VirtualFolderHydrationStatus::relativePath).distinct().size == folderHydrationStatuses.size)
        require(
            folderHydrationStatuses.all { status ->
                folderRetentionRules.any { rule ->
                    rule.relativePath == status.relativePath && rule.retention == VirtualFolderRetention.KeepOnDevice
                }
            },
        )
        require(pendingWritebackCount >= 0)
        require(cacheTiers == null || primaryCache != null)
        require(overflowCache == null || cacheTiers?.overflowPath != null)
    }
}

/** Physical cache locations behind the stable virtual-files namespace. */
data class VirtualFileCacheTierConfiguration(
    val primaryPath: String,
    val overflowPath: String? = null,
) {
    init {
        require(primaryPath.isValidVirtualFileCachePath())
        require(overflowPath == null || overflowPath.isValidVirtualFileCachePath())
        require(overflowPath == null || overflowPath != primaryPath) {
            "The primary and overflow cache locations must be different."
        }
    }
}

data class VirtualFileCacheTierSnapshot(
    val path: String,
    val cachedBytes: Long,
    val reclaimableBytes: Long,
    val pinnedBytes: Long,
    val managedAutomaticBytes: Long = cachedBytes - pinnedBytes,
    val availableFreeBytes: Long?,
    val available: Boolean,
) {
    init {
        require(path.isValidVirtualFileCachePath())
        require(cachedBytes >= 0L)
        require(reclaimableBytes in 0L..cachedBytes)
        require(pinnedBytes in 0L..cachedBytes)
        require(managedAutomaticBytes >= 0L)
        require(availableFreeBytes == null || availableFreeBytes >= 0L)
        require(available || availableFreeBytes == null)
    }
}

fun String.isValidVirtualFileCachePath(): Boolean =
    isNotBlank() && length <= MAX_VIRTUAL_FILE_LOCATION_LENGTH && none(Char::isISOControl)

/** User-controlled location for the visible virtual filesystem namespace. */
data class VirtualFileProviderLocation(
    val parentPath: String,
    val folderName: String,
) {
    init {
        require(
            parentPath.isNotBlank() &&
                parentPath.length <= MAX_VIRTUAL_FILE_LOCATION_LENGTH &&
                parentPath.none(Char::isISOControl),
        )
        require(folderName.isValidVirtualFileProviderFolderName()) {
            "The virtual file folder name is invalid."
        }
    }
}

fun String.isValidVirtualFileProviderFolderName(): Boolean {
    if (isBlank() || length > MAX_VIRTUAL_FILE_FOLDER_NAME_LENGTH || this != trim()) return false
    if (this == "." || this == ".." || any(Char::isISOControl)) return false
    if (equals(INTERNAL_VIRTUAL_FILE_CACHE_FOLDER_NAME, ignoreCase = true)) return false
    if (any { it == '/' || it == '\\' || it in "<>:\"|?*" }) return false
    if (endsWith('.') || endsWith(' ')) return false
    val stem = substringBefore('.').uppercase()
    return stem !in WINDOWS_RESERVED_FOLDER_NAMES
}

sealed interface VirtualFileStorageActionResult {
    data class Completed(
        val message: String,
        val freedBytes: Long = 0L,
    ) : VirtualFileStorageActionResult {
        init {
            require(message.isNotBlank() && message.length <= MAX_VIRTUAL_FILE_ACTION_MESSAGE_LENGTH)
            require(freedBytes >= 0L)
        }
    }

    data class Rejected(val reason: String) : VirtualFileStorageActionResult {
        init {
            require(reason.isNotBlank() && reason.length <= MAX_VIRTUAL_FILE_ACTION_MESSAGE_LENGTH)
        }
    }

    data class Unsupported(val reason: String) : VirtualFileStorageActionResult {
        init {
            require(reason.isNotBlank() && reason.length <= MAX_VIRTUAL_FILE_ACTION_MESSAGE_LENGTH)
        }
    }
}

fun defaultVirtualFileStorageSnapshot(): VirtualFileStorageSnapshot = VirtualFileStorageSnapshot(
    support = VirtualFileStorageSupport.Unsupported,
    integration = null,
    policy = VirtualFileCachePolicy(automaticCleanup = false),
    cachedBytes = 0L,
    reclaimableBytes = 0L,
    pinnedBytes = 0L,
    hydratedFileCount = 0,
    pinnedFileCount = 0,
    availableFreeBytes = null,
    storageCapacityBytes = null,
    limitations = listOf("Virtual file hydration is not available on this platform build."),
    providerState = VirtualFileProviderState.NotApplicable,
)

fun formatVirtualFileBytes(bytes: Long): String {
    require(bytes >= 0L)
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    return if (unit == 0) {
        "$bytes ${units[unit]}"
    } else {
        val rounded = (value * 10.0).toLong() / 10.0
        "$rounded ${units[unit]}"
    }
}

private const val MAX_VIRTUAL_FILE_LIMITATIONS = 8
private const val MAX_VIRTUAL_FILE_LIMITATION_LENGTH = 512
internal const val MAX_VIRTUAL_FILE_ACTION_MESSAGE_LENGTH = 512
private const val MAX_VIRTUAL_FILE_LOCATION_LENGTH = 8_192
private const val MAX_VIRTUAL_FILE_FOLDER_NAME_LENGTH = 128
internal const val INTERNAL_VIRTUAL_FILE_CACHE_FOLDER_NAME = ".nextcloud-native-cache"
private val WINDOWS_RESERVED_FOLDER_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    (1..9).forEach { number ->
        add("COM$number")
        add("LPT$number")
    }
}
