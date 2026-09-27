package dev.obiente.nextcloudnative.app

import java.nio.file.Path

internal fun pageWindowsCloudFilesRecoveryRoots(
    roots: Map<String, Path>,
    startAfterAccountId: String?,
    limit: Int = MAX_WINDOWS_CLOUD_FILES_RECOVERY_ROOTS_PER_ATTEMPT,
): Map<String, Path> {
    require(limit > 0)
    if (roots.isEmpty()) return emptyMap()
    val ordered = roots.entries.sortedBy(Map.Entry<String, Path>::key)
    val startIndex = startAfterAccountId
        ?.let { cursor -> ordered.indexOfFirst { it.key > cursor } }
        ?.takeIf { it >= 0 }
        ?: 0
    return (0 until minOf(limit, ordered.size))
        .map { offset -> ordered[(startIndex + offset) % ordered.size] }
        .associate(Map.Entry<String, Path>::toPair)
}

private const val MAX_WINDOWS_CLOUD_FILES_RECOVERY_ROOTS_PER_ATTEMPT = 16
