package dev.obiente.nextcloudnative

/**
 * Recovers the crash window after a replacement upload may have become the visible destination.
 * The protected directory is retired only after the exact published file generation is verified.
 */
internal fun AndroidFileSyncRemoteTree.reconcilePublishedReplacement(
    relativePath: String,
    uploadId: String,
    expectedSizeBytes: Long?,
    expectedContentHash: String?,
    expectedBackupEtag: String?,
): Boolean? {
    if (expectedBackupEtag == null) return null
    val destination = resolvePhysical(relativePath) ?: return null
    if (destination.isDirectory) return null
    if (expectedSizeBytes == null || expectedContentHash == null) return false
    if (destination.entry.size != expectedSizeBytes) return false
    if (
        !verifyContentHash(
            relativePath,
            destination.entry.etag,
            expectedContentHash,
            expectedSizeBytes,
            expectedSizeBytes.coerceAtLeast(1L),
        )
    ) {
        return false
    }
    val after = requireNotNull(resolvePhysical(relativePath)) {
        "The published server file disappeared during recovery."
    }
    require(!after.isDirectory && after.entry.etag == destination.entry.etag) {
        "The published server file changed during recovery."
    }
    completeReplacementBackup(relativePath, uploadId, expectedBackupEtag)
    return true
}
