package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncContentVerificationResult
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.MAX_FILE_SYNC_IDENTITY_FILE_BYTES
import dev.obiente.nextcloudnative.app.MAX_FILE_SYNC_IDENTITY_TOTAL_BYTES
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind

internal fun verifyAndroidRemoteDeletionContent(
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
    baselines: List<FileSyncBaseline>,
    local: AndroidFileSyncLocalTree,
): List<LocalSyncEntry> {
    val remotePaths = remoteEntries.mapTo(mutableSetOf(), RemoteSyncEntry::relativePath)
    val baselineByPath = baselines.associateBy(FileSyncBaseline::relativePath)
    var remainingBytes = MAX_FILE_SYNC_IDENTITY_TOTAL_BYTES
    return localEntries.map { entry ->
        val baseline = baselineByPath[entry.relativePath]
        if (
            entry.kind != SyncEntryKind.File ||
            entry.relativePath in remotePaths ||
            baseline?.kind != SyncEntryKind.File ||
            entry.revision != baseline.localRevision ||
            baseline.contentHash == null
        ) {
            return@map entry
        }
        val expectedBytes = entry.size
        if (
            expectedBytes == null ||
            expectedBytes > MAX_FILE_SYNC_IDENTITY_FILE_BYTES ||
            expectedBytes > remainingBytes
        ) {
            return@map entry.copy(contentIdentityUnverified = true)
        }
        remainingBytes -= expectedBytes
        val localHash = requireNotNull(
            local.contentHash(
                path = entry.relativePath,
                expectedLocalRevision = entry.revision,
                expectedBytes = expectedBytes,
                maximumBytes = maxOf(1L, expectedBytes),
            ),
        ) { "The local file could not be verified before applying a remote deletion." }
        entry.copy(contentHash = localHash)
    }
}

internal fun validateAndroidCachedMismatchContent(
    cached: List<FileSyncContentVerificationResult>,
    local: AndroidFileSyncLocalTree,
): List<FileSyncContentVerificationResult> = cached.mapNotNull { result ->
    val candidate = result.candidate
    val expectedBytes = requireNotNull(candidate.expectedSizeBytes)
    val currentHash = local.contentHash(
        path = candidate.relativePath,
        expectedLocalRevision = candidate.localRevision,
        expectedBytes = expectedBytes,
        maximumBytes = maxOf(1L, expectedBytes),
    )
    result.takeIf { currentHash == result.localContentHash }
}
