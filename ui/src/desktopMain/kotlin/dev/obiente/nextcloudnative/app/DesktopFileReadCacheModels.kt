package dev.obiente.nextcloudnative.app

internal data class DesktopCachedFileContent(
    val bytes: ByteArray,
    val mimeType: String?,
    val etag: String,
)

internal data class DesktopCachedFileListing(
    val files: List<NextcloudFile>,
    val fetchedAtEpochMillis: Long,
)

internal data class DesktopCachedVirtualListing(
    val nodes: List<LinuxVirtualFileNode>,
    val fetchedAtEpochMillis: Long,
    val freshAtEpochMillis: Long = fetchedAtEpochMillis,
)

internal data class DesktopVirtualFileCacheSummary(
    val policy: VirtualFileCachePolicy,
    val cachedBytes: Long,
    val reclaimableBytes: Long,
    val entryCount: Int,
    val availableFreeBytes: Long,
)
