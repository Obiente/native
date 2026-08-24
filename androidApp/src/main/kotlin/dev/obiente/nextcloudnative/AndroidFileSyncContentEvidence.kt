package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncContentVerificationResult
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.MAX_FILE_SYNC_IDENTITY_FILE_BYTES
import dev.obiente.nextcloudnative.app.MAX_FILE_SYNC_IDENTITY_TOTAL_BYTES
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind

internal class AndroidFileSyncContentReadBudget(
    private val maximumFileBytes: Long = MAX_FILE_SYNC_IDENTITY_FILE_BYTES,
    maximumTotalBytes: Long = MAX_FILE_SYNC_IDENTITY_TOTAL_BYTES,
) {
    var remainingBytes: Long = maximumTotalBytes
        private set

    init {
        require(maximumFileBytes >= 0L && maximumTotalBytes >= 0L)
    }

    fun reserve(expectedBytes: Long?): Boolean {
        if (expectedBytes == null) return false
        require(expectedBytes >= 0L)
        if (expectedBytes > maximumFileBytes || expectedBytes > remainingBytes) return false
        remainingBytes -= expectedBytes
        return true
    }
}

internal fun verifyAndroidRemoteDeletionContent(
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
    baselines: List<FileSyncBaseline>,
    local: AndroidFileSyncLocalTree,
    budget: AndroidFileSyncContentReadBudget,
): List<LocalSyncEntry> {
    val remotePaths = remoteEntries.mapTo(mutableSetOf(), RemoteSyncEntry::relativePath)
    val baselineByPath = baselines.associateBy(FileSyncBaseline::relativePath)
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
        if (!budget.reserve(expectedBytes)) {
            return@map entry.copy(contentIdentityUnverified = true)
        }
        val verifiedBytes = requireNotNull(expectedBytes)
        val localHash = requireNotNull(
            local.contentHash(
                path = entry.relativePath,
                expectedLocalRevision = entry.revision,
                expectedBytes = verifiedBytes,
                maximumBytes = maxOf(1L, verifiedBytes),
            ),
        ) { "The local file could not be verified before applying a remote deletion." }
        entry.copy(contentHash = localHash)
    }
}

internal fun validateAndroidCachedMismatchContent(
    cached: List<FileSyncContentVerificationResult>,
    local: AndroidFileSyncLocalTree,
    budget: AndroidFileSyncContentReadBudget,
): List<FileSyncContentVerificationResult> = cached.mapNotNull { result ->
    val candidate = result.candidate
    val expectedBytes = requireNotNull(candidate.expectedSizeBytes)
    if (!budget.reserve(expectedBytes)) return@mapNotNull null
    val currentHash = local.contentHash(
        path = candidate.relativePath,
        expectedLocalRevision = candidate.localRevision,
        expectedBytes = expectedBytes,
        maximumBytes = maxOf(1L, expectedBytes),
    )
    result.takeIf { currentHash == result.localContentHash }
}
