package dev.obiente.nextcloudnative.app

/**
 * One same-path file pair whose content needs exact verification before planning may treat it as
 * synchronized. Platform adapters must bind reads to [localRevision] and [remoteEtag].
 */
data class FileSyncContentVerificationCandidate(
    val relativePath: String,
    val localRevision: String,
    val remoteEtag: String,
    val expectedSizeBytes: Long?,
) {
    init {
        requireValidSyncPath(relativePath)
        require(localRevision.isNotBlank())
        require(remoteEtag.isNotBlank())
        require(expectedSizeBytes == null || expectedSizeBytes >= 0L)
    }
}

/** Exact content identity established by generation-guarded local and remote reads. */
data class VerifiedFileSyncContent(
    val candidate: FileSyncContentVerificationCandidate,
    val contentHash: String,
) {
    init {
        require(normalizeSyncSha256(contentHash) == contentHash)
    }
}

/** Generation-bound result of an exact local/remote content comparison. */
data class FileSyncContentVerificationResult(
    val candidate: FileSyncContentVerificationCandidate,
    val matchingContentHash: String?,
) {
    init {
        require(matchingContentHash == null || normalizeSyncSha256(matchingContentHash) == matchingContentHash)
    }

    fun verifiedContent(): VerifiedFileSyncContent? = matchingContentHash?.let { hash ->
        VerifiedFileSyncContent(candidate, hash)
    }
}

data class FileSyncContentIdentitySnapshot(
    val localEntries: List<LocalSyncEntry>,
    val remoteEntries: List<RemoteSyncEntry>,
)

/**
 * Selects paired files which could otherwise cause a first-sync or changed-generation conflict.
 * Different known sizes are conclusive non-equality evidence and never require content reads.
 */
fun fileSyncContentVerificationCandidates(
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
    baselines: List<FileSyncBaseline>,
    knownMismatches: List<FileSyncContentVerificationCandidate> = emptyList(),
): List<FileSyncContentVerificationCandidate> {
    requireUniqueSyncContentPaths(localEntries.map(LocalSyncEntry::relativePath), "local")
    requireUniqueSyncContentPaths(remoteEntries.map(RemoteSyncEntry::relativePath), "remote")
    requireUniqueSyncContentPaths(baselines.map(FileSyncBaseline::relativePath), "baseline")
    requireUniqueSyncContentPaths(knownMismatches.map(FileSyncContentVerificationCandidate::relativePath), "mismatch")
    val remoteByPath = remoteEntries.associateBy(RemoteSyncEntry::relativePath)
    val baselineByPath = baselines.associateBy(FileSyncBaseline::relativePath)
    val mismatchByPath = knownMismatches.associateBy(FileSyncContentVerificationCandidate::relativePath)
    return localEntries.asSequence()
        .filter { it.kind == SyncEntryKind.File }
        .mapNotNull { local ->
            val remote = remoteByPath[local.relativePath]
                ?.takeIf { it.kind == SyncEntryKind.File }
                ?: return@mapNotNull null
            if (local.size != null && remote.size != null && local.size != remote.size) {
                return@mapNotNull null
            }
            val expectedSize = local.size?.takeIf { it == remote.size }
                ?: return@mapNotNull null
            val baseline = baselineByPath[local.relativePath]
            if (
                baseline != null &&
                baseline.kind == SyncEntryKind.File &&
                baseline.localRevision == local.revision &&
                baseline.remoteEtag == remote.etag
            ) {
                return@mapNotNull null
            }
            if (
                mismatchByPath[local.relativePath] == FileSyncContentVerificationCandidate(
                    relativePath = local.relativePath,
                    localRevision = local.revision,
                    remoteEtag = remote.etag,
                    expectedSizeBytes = expectedSize,
                )
            ) {
                return@mapNotNull null
            }
            FileSyncContentVerificationCandidate(
                relativePath = local.relativePath,
                localRevision = local.revision,
                remoteEtag = remote.etag,
                expectedSizeBytes = expectedSize,
            )
        }
        .sortedBy(FileSyncContentVerificationCandidate::relativePath)
        .toList()
}

/** Exact mismatch evidence retained by conflict work while both observed generations stay unchanged. */
fun FileSyncPair.knownFileSyncContentMismatches(): List<FileSyncContentVerificationCandidate> =
    workItems.mapNotNull { work ->
        if (!work.contentMismatchVerified) return@mapNotNull null
        val local = requireNotNull(work.observedLocal)
        val remote = requireNotNull(work.observedRemote)
        FileSyncContentVerificationCandidate(
            relativePath = work.relativePath,
            localRevision = local.revision,
            remoteEtag = remote.etag,
            expectedSizeBytes = local.size,
        )
    }

internal fun requireValidFileSyncContentMismatchEvidence(work: FileSyncWorkItem) {
    require(
        !work.contentMismatchVerified || (
            work.observedLocal?.kind == SyncEntryKind.File &&
                work.observedRemote?.kind == SyncEntryKind.File &&
                work.observedLocal.size != null &&
                work.observedLocal.size == work.observedRemote.size
            )
    ) { "Verified mismatch evidence requires equal-size observed files." }
}

internal fun verifiedFileSyncContentMismatchPaths(
    mismatches: List<FileSyncContentVerificationCandidate>,
    localByPath: Map<String, LocalSyncEntry>,
    remoteByPath: Map<String, RemoteSyncEntry>,
): Set<String> {
    requireUniqueSyncContentPaths(
        mismatches.map(FileSyncContentVerificationCandidate::relativePath),
        "verified mismatch",
    )
    return mismatches.mapTo(mutableSetOf()) { mismatch ->
        val local = requireNotNull(localByPath[mismatch.relativePath]) {
            "A verified mismatch is missing its local file."
        }
        val remote = requireNotNull(remoteByPath[mismatch.relativePath]) {
            "A verified mismatch is missing its remote file."
        }
        require(
            local.kind == SyncEntryKind.File &&
                remote.kind == SyncEntryKind.File &&
                local.revision == mismatch.localRevision &&
                remote.etag == mismatch.remoteEtag &&
                local.size == mismatch.expectedSizeBytes &&
                remote.size == mismatch.expectedSizeBytes,
        ) { "Verified mismatch evidence no longer matches the scanned generations." }
        mismatch.relativePath
    }
}

/**
 * Bounds automatic identity reads so reconnecting a large tree cannot silently consume an
 * unlimited amount of network traffic. Files outside this budget remain conservative conflicts.
 */
fun List<FileSyncContentVerificationCandidate>.withinFileSyncContentVerificationBudget(
    maximumFileBytes: Long = MAX_FILE_SYNC_IDENTITY_FILE_BYTES,
    maximumTotalBytes: Long = MAX_FILE_SYNC_IDENTITY_TOTAL_BYTES,
    maximumCandidates: Int = MAX_FILE_SYNC_WORK_ITEMS,
): List<FileSyncContentVerificationCandidate> {
    require(maximumFileBytes >= 0L && maximumTotalBytes >= 0L && maximumCandidates >= 0)
    var remaining = maximumTotalBytes
    var selected = 0
    return mapNotNull { candidate ->
        if (selected >= maximumCandidates) return@mapNotNull null
        val size = candidate.expectedSizeBytes
            ?.takeIf { it <= maximumFileBytes && it <= remaining }
            ?: return@mapNotNull null
        remaining -= size
        selected += 1
        candidate
    }
}

/**
 * Publishes only exact, version-bound equality evidence to shared planning. Unverified checksum
 * hints are removed from paired files so a client-supplied DAV property cannot suppress a real
 * conflict by itself.
 */
fun applyVerifiedFileSyncContent(
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
    verifiedContent: List<VerifiedFileSyncContent>,
): FileSyncContentIdentitySnapshot {
    requireUniqueSyncContentPaths(localEntries.map(LocalSyncEntry::relativePath), "local")
    requireUniqueSyncContentPaths(remoteEntries.map(RemoteSyncEntry::relativePath), "remote")
    requireUniqueSyncContentPaths(
        verifiedContent.map { it.candidate.relativePath },
        "verified content",
    )
    val localByPath = localEntries.associateBy(LocalSyncEntry::relativePath)
    val remoteByPath = remoteEntries.associateBy(RemoteSyncEntry::relativePath)
    val pairedFilePaths = localEntries.asSequence()
        .filter { local ->
            local.kind == SyncEntryKind.File &&
                remoteByPath[local.relativePath]?.kind == SyncEntryKind.File
        }
        .mapTo(mutableSetOf(), LocalSyncEntry::relativePath)
    val verifiedByPath = verifiedContent.associateBy { verified ->
        val candidate = verified.candidate
        val local = requireNotNull(localByPath[candidate.relativePath]) {
            "Verified sync content is missing its local file."
        }
        val remote = requireNotNull(remoteByPath[candidate.relativePath]) {
            "Verified sync content is missing its remote file."
        }
        require(local.kind == SyncEntryKind.File && remote.kind == SyncEntryKind.File)
        require(local.revision == candidate.localRevision && remote.etag == candidate.remoteEtag) {
            "Verified sync content no longer matches the scanned generations."
        }
        require(
            candidate.expectedSizeBytes == null ||
                local.size == null ||
                local.size == candidate.expectedSizeBytes,
        ) { "Verified local content no longer matches the scanned size." }
        require(
            candidate.expectedSizeBytes == null ||
                remote.size == null ||
                remote.size == candidate.expectedSizeBytes,
        ) { "Verified remote content no longer matches the scanned size." }
        candidate.relativePath
    }
    return FileSyncContentIdentitySnapshot(
        localEntries = localEntries.map { entry ->
            when {
                entry.relativePath in verifiedByPath -> entry.copy(
                    contentHash = requireNotNull(verifiedByPath[entry.relativePath]).contentHash,
                )
                entry.relativePath in pairedFilePaths -> entry.copy(contentHash = null)
                else -> entry
            }
        },
        remoteEntries = remoteEntries.map { entry ->
            when {
                entry.relativePath in verifiedByPath -> entry.copy(
                    contentHash = requireNotNull(verifiedByPath[entry.relativePath]).contentHash,
                )
                entry.relativePath in pairedFilePaths -> entry.copy(contentHash = null)
                else -> entry
            }
        },
    )
}

private fun requireUniqueSyncContentPaths(paths: List<String>, label: String) {
    require(paths.size == paths.distinct().size) { "The $label sync snapshot contains duplicate paths." }
}

const val MAX_FILE_SYNC_IDENTITY_FILE_BYTES = 64L * 1024L * 1024L
const val MAX_FILE_SYNC_IDENTITY_TOTAL_BYTES = 256L * 1024L * 1024L
