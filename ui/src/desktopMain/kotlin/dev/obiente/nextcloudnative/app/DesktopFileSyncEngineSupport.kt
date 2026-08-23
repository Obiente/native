package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

internal fun DesktopFileSyncPersistedState.scopedToDesktopWork(
    pair: FileSyncPair,
    work: FileSyncWorkItem,
): DesktopFileSyncPersistedState = copy(
    coordinator = FileSyncCoordinatorState(
        listOf(pair.copy(baselines = emptyList(), workItems = listOf(work))),
    ),
)

internal fun requireDesktopFileSyncBaselineCapacity(
    operation: FileSyncOperation,
    baselinePaths: Set<String>,
    maximumEntries: Int = MAX_FILE_SYNC_ENTRIES,
) {
    require(maximumEntries > 0 && baselinePaths.size <= maximumEntries)
    val affectedPaths: Set<String>
    val synchronizedPaths: Set<String>
    when (operation) {
        is FileSyncOperation.Upload,
        is FileSyncOperation.Download,
        -> {
            affectedPaths = setOf(operation.relativePath)
            synchronizedPaths = affectedPaths
        }
        is FileSyncOperation.KeepBoth -> {
            affectedPaths = setOf(
                operation.relativePath,
                operation.localConflictPath,
                operation.remoteConflictPath,
            )
            synchronizedPaths = affectedPaths
        }
        is FileSyncOperation.DeleteLocal,
        is FileSyncOperation.DeleteRemote,
        -> {
            affectedPaths = setOf(operation.relativePath)
            synchronizedPaths = emptySet()
        }
        is FileSyncOperation.NeedsDecision,
        is FileSyncOperation.Skipped,
        -> error("Non-executable sync work has no baseline result.")
    }
    val replacedCount = affectedPaths.count { it in baselinePaths }
    require(baselinePaths.size - replacedCount + synchronizedPaths.size <= maximumEntries) {
        "The synchronized result would exceed the folder baseline limit."
    }
}

internal fun desktopFileSyncRemoteMutationPath(remoteRootPath: String, relativePath: String): String {
    val relative = relativePath.trim('/')
    requireValidSyncPath(relative)
    val root = remoteRootPath.trim('/')
    if (root.isNotEmpty()) requireValidSyncPath(root)
    return listOf(root, relative).filter(String::isNotBlank).joinToString("/")
}

internal fun reclaimDesktopFileSyncStages(stagingRoot: File): Int {
    if (!stagingRoot.isDirectory) return 0
    return stagingRoot.listFiles().orEmpty().count { candidate ->
        if (!Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return@count false
        val name = candidate.name
        val prefix = DESKTOP_FILE_SYNC_STAGE_PREFIXES.firstOrNull { ownedPrefix ->
            name.startsWith("nextcloud-native-$ownedPrefix-")
        } ?: return@count false
        val token = name.removePrefix("nextcloud-native-$prefix-").removeSuffix(".tmp")
        if (!name.endsWith(".tmp") || runCatching { UUID.fromString(token) }.isFailure) return@count false
        candidate.delete()
    }
}

internal val DESKTOP_FILE_SYNC_STAGE_PREFIXES = setOf(
    "upload",
    "verify-upload",
    "download",
    "keep-local",
    "keep-remote",
    "verify-local-conflict",
    "verify-remote-conflict",
    "verify-local-original",
)

internal fun requiredDesktopDownloadFreeBytes(
    downloadBytes: Long,
    reserveBytes: Long,
    sameStore: Boolean,
): Long {
    require(downloadBytes >= 0L && reserveBytes >= 0L)
    val contentBytes = if (sameStore) {
        if (downloadBytes > Long.MAX_VALUE / 2L) Long.MAX_VALUE else downloadBytes * 2L
    } else {
        downloadBytes
    }
    return if (reserveBytes > Long.MAX_VALUE - contentBytes) Long.MAX_VALUE else contentBytes + reserveBytes
}

internal fun desktopSyncRootsOverlap(first: String, second: String): Boolean {
    val firstPath = File(first).toPath().toAbsolutePath().normalize()
    val secondPath = File(second).toPath().toAbsolutePath().normalize()
    return firstPath == secondPath || firstPath.startsWith(secondPath) || secondPath.startsWith(firstPath)
}

internal fun desktopSyncRemoteRootsOverlap(first: String, second: String): Boolean {
    val left = first.trim('/')
    val right = second.trim('/')
    return left.isEmpty() || right.isEmpty() ||
        left == right || left.startsWith("$right/") || right.startsWith("$left/")
}

internal fun desktopSyncMappingsOverlap(
    existingAccountId: String,
    requestedAccountId: String,
    existingLocalRoot: String,
    requestedLocalRoot: String,
    existingRemoteRoot: String,
    requestedRemoteRoot: String,
): Boolean = desktopSyncRootsOverlap(existingLocalRoot, requestedLocalRoot) ||
    (
        existingAccountId == requestedAccountId &&
            desktopSyncRemoteRootsOverlap(existingRemoteRoot, requestedRemoteRoot)
        )

internal fun desktopFileSyncStagingDirectory(): File {
    val cacheRoot = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)?.let(::File)
        ?: File(System.getProperty("user.home"), ".cache")
    return File(cacheRoot, "nextcloud-native/file-sync-staging")
}
