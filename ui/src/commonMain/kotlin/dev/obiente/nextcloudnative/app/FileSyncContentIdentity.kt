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
    val localContentHash: String,
    val matchingContentHash: String?,
) {
    init {
        require(normalizeSyncSha256(localContentHash) == localContentHash)
        require(matchingContentHash == null || normalizeSyncSha256(matchingContentHash) == matchingContentHash)
        require(matchingContentHash == null || matchingContentHash == localContentHash)
    }

    fun verifiedContent(): VerifiedFileSyncContent? = matchingContentHash?.let { hash ->
        VerifiedFileSyncContent(candidate, hash)
    }
}

data class FileSyncContentIdentitySnapshot(
    val localEntries: List<LocalSyncEntry>,
    val remoteEntries: List<RemoteSyncEntry>,
)

/** Durable progress for an exact comparison performed in bounded byte ranges across scans. */
data class FileSyncContentVerificationProgress(
    val candidate: FileSyncContentVerificationCandidate,
    val verifiedBytes: Long,
    val aggregateHash: String,
) {
    init {
        val expectedBytes = requireNotNull(candidate.expectedSizeBytes)
        require(verifiedBytes in 0L..expectedBytes)
        require(normalizeSyncSha256(aggregateHash) == aggregateHash)
    }
}

data class FileSyncContentVerificationSlice(
    val candidate: FileSyncContentVerificationCandidate,
    val offset: Long,
    val length: Int,
    val aggregateHash: String,
) {
    init {
        val expectedBytes = requireNotNull(candidate.expectedSizeBytes)
        require(offset in 0L..expectedBytes)
        require(length >= 0 && offset <= expectedBytes - length)
        require(length > 0 || expectedBytes == 0L)
        require(normalizeSyncSha256(aggregateHash) == aggregateHash)
    }
}

fun currentFileSyncContentVerificationProgress(
    candidates: List<FileSyncContentVerificationCandidate>,
    progress: List<FileSyncContentVerificationProgress>,
): List<FileSyncContentVerificationProgress> {
    requireUniqueSyncContentPaths(candidates.map(FileSyncContentVerificationCandidate::relativePath), "candidate")
    requireUniqueSyncContentPaths(progress.map { it.candidate.relativePath }, "content verification progress")
    val candidatesByPath = candidates.associateBy(FileSyncContentVerificationCandidate::relativePath)
    return progress.filter { it.candidate == candidatesByPath[it.candidate.relativePath] }
}

internal fun requireCurrentFileSyncContentVerificationProgress(
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
    verifiedMismatches: List<FileSyncContentVerificationCandidate>,
    progress: List<FileSyncContentVerificationProgress>,
) {
    val candidates = fileSyncContentVerificationCandidates(
        localEntries,
        remoteEntries,
        emptyList(),
        verifiedMismatches,
    )
    require(currentFileSyncContentVerificationProgress(candidates, progress) == progress) {
        "Content-verification progress no longer matches the scanned generations."
    }
}

internal fun requireBoundedFileSyncContentVerificationProgress(
    progress: List<FileSyncContentVerificationProgress>,
) {
    require(progress.size <= MAX_FILE_SYNC_ENTRIES) {
        "The sync pair contains too much content-verification progress."
    }
    requireUniqueSyncContentPaths(
        progress.map { it.candidate.relativePath },
        "content verification progress",
    )
}

/** Plans a fair bounded slice for each least-advanced candidate; file size is never an exclusion. */
fun planFileSyncContentVerificationSlices(
    candidates: List<FileSyncContentVerificationCandidate>,
    progress: List<FileSyncContentVerificationProgress>,
    maximumSliceBytes: Int = FILE_SYNC_IDENTITY_SLICE_BYTES,
    maximumTotalBytes: Long = FILE_SYNC_IDENTITY_SCAN_BYTES,
    maximumSlices: Int = FILE_SYNC_IDENTITY_SCAN_SLICES,
): List<FileSyncContentVerificationSlice> {
    require(maximumSliceBytes > 0 && maximumTotalBytes >= 0L && maximumSlices >= 0)
    val currentProgress = currentFileSyncContentVerificationProgress(candidates, progress)
        .associateBy { it.candidate.relativePath }
    var remainingBytes = maximumTotalBytes
    return candidates
        .sortedWith(
            compareBy<FileSyncContentVerificationCandidate> {
                val saved = currentProgress[it.relativePath]
                when {
                    saved == null -> 0
                    saved.verifiedBytes > 0L -> 1
                    else -> 2
                }
            }.thenBy {
                currentProgress[it.relativePath]?.verifiedBytes ?: 0L
            }.thenBy(FileSyncContentVerificationCandidate::relativePath),
        )
        .asSequence()
        .mapNotNull { candidate ->
            val expectedBytes = requireNotNull(candidate.expectedSizeBytes)
            val previous = currentProgress[candidate.relativePath]
            val offset = previous?.verifiedBytes ?: 0L
            val remainingFileBytes = expectedBytes - offset
            val length = minOf(remainingFileBytes, maximumSliceBytes.toLong(), remainingBytes).toInt()
            if (expectedBytes > 0L && length == 0) return@mapNotNull null
            remainingBytes -= length
            FileSyncContentVerificationSlice(
                candidate = candidate,
                offset = offset,
                length = length,
                aggregateHash = previous?.aggregateHash ?: EMPTY_FILE_SYNC_IDENTITY_AGGREGATE,
            )
        }
        .take(maximumSlices)
        .toList()
}

fun markPendingFileSyncContentVerification(
    snapshot: FileSyncContentIdentitySnapshot,
    pendingCandidates: List<FileSyncContentVerificationCandidate>,
): FileSyncContentIdentitySnapshot {
    val pendingPaths = pendingCandidates.mapTo(mutableSetOf(), FileSyncContentVerificationCandidate::relativePath)
    return snapshot.copy(
        localEntries = snapshot.localEntries.map { entry ->
            if (entry.relativePath in pendingPaths) entry.copy(contentIdentityUnverified = true) else entry
        },
    )
}

/**
 * Selects paired files which could otherwise cause a first-sync or changed-generation conflict.
 * Different known sizes are conclusive non-equality evidence and never require content reads.
 */
fun fileSyncContentVerificationCandidates(
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
    baselines: List<FileSyncBaseline>,
    knownMismatches: List<FileSyncContentVerificationCandidate> = emptyList(),
    requireContentBackedBaseline: Boolean = false,
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
                baseline.remoteEtag == remote.etag &&
                (!requireContentBackedBaseline || baseline.contentHash != null)
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
    knownFileSyncContentMismatchResults().map(FileSyncContentVerificationResult::candidate)

/** Restores exact local mismatch evidence while both persisted generations remain unchanged. */
fun FileSyncPair.knownFileSyncContentMismatchResults(): List<FileSyncContentVerificationResult> =
    workItems.mapNotNull { work ->
        if (!work.contentMismatchVerified) return@mapNotNull null
        val local = requireNotNull(work.observedLocal)
        val remote = requireNotNull(work.observedRemote)
        FileSyncContentVerificationResult(
            candidate = FileSyncContentVerificationCandidate(
                relativePath = work.relativePath,
                localRevision = local.revision,
                remoteEtag = remote.etag,
                expectedSizeBytes = local.size,
            ),
            localContentHash = requireNotNull(work.contentMismatchLocalHash),
            matchingContentHash = null,
        )
    }

/** Keeps cached evidence only while the exact local and remote generations are still present. */
fun currentFileSyncContentVerificationResults(
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
    results: List<FileSyncContentVerificationResult>,
): List<FileSyncContentVerificationResult> {
    requireUniqueSyncContentPaths(localEntries.map(LocalSyncEntry::relativePath), "local")
    requireUniqueSyncContentPaths(remoteEntries.map(RemoteSyncEntry::relativePath), "remote")
    requireUniqueSyncContentPaths(results.map { it.candidate.relativePath }, "content verification")
    val localByPath = localEntries.associateBy(LocalSyncEntry::relativePath)
    val remoteByPath = remoteEntries.associateBy(RemoteSyncEntry::relativePath)
    return results.filter { result ->
        val candidate = result.candidate
        val local = localByPath[candidate.relativePath]
        val remote = remoteByPath[candidate.relativePath]
        local?.kind == SyncEntryKind.File &&
            remote?.kind == SyncEntryKind.File &&
            local.revision == candidate.localRevision &&
            remote.etag == candidate.remoteEtag &&
            local.contentHash == result.localContentHash &&
            (candidate.expectedSizeBytes == null || (
                local.size == candidate.expectedSizeBytes &&
                    remote.size == candidate.expectedSizeBytes
                ))
    }
}

internal fun requireValidFileSyncContentMismatchEvidence(work: FileSyncWorkItem) {
    require(
        work.contentMismatchVerified == (work.contentMismatchLocalHash != null) &&
            (!work.contentMismatchVerified || (
                work.observedLocal?.kind == SyncEntryKind.File &&
                    work.observedRemote?.kind == SyncEntryKind.File &&
                    work.observedLocal.size != null &&
                    work.observedLocal.size == work.observedRemote.size &&
                    normalizeSyncSha256(requireNotNull(work.contentMismatchLocalHash)) ==
                    work.contentMismatchLocalHash
                ))
    ) { "Verified mismatch evidence requires equal-size observed files." }
}

internal fun verifiedFileSyncContentMismatchHashes(
    mismatches: List<FileSyncContentVerificationCandidate>,
    hashes: Map<String, String>,
    localByPath: Map<String, LocalSyncEntry>,
    remoteByPath: Map<String, RemoteSyncEntry>,
): Map<String, String> {
    requireUniqueSyncContentPaths(
        mismatches.map(FileSyncContentVerificationCandidate::relativePath),
        "verified mismatch",
    )
    require(hashes.keys == mismatches.mapTo(mutableSetOf(), FileSyncContentVerificationCandidate::relativePath))
    return mismatches.associate { mismatch ->
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
        val hash = requireNotNull(hashes[mismatch.relativePath])
        require(normalizeSyncSha256(hash) == hash)
        mismatch.relativePath to hash
    }
}

/**
 * Selects generation-pinned identity reads without excluding files because of their size.
 * [maximumCandidates] still bounds one scan's work and every platform performs these reads away
 * from the UI thread. A byte budget must never turn an identical same-path file into a conflict.
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
): FileSyncContentIdentitySnapshot = applyFileSyncContentVerificationResults(
    localEntries,
    remoteEntries,
    verifiedContent.map { verified ->
        FileSyncContentVerificationResult(
            candidate = verified.candidate,
            localContentHash = verified.contentHash,
            matchingContentHash = verified.contentHash,
        )
    },
)

/** Publishes generation-bound local hashes as change evidence and exact matches as equality evidence. */
fun applyFileSyncContentVerificationResults(
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
    verificationResults: List<FileSyncContentVerificationResult>,
): FileSyncContentIdentitySnapshot {
    requireUniqueSyncContentPaths(localEntries.map(LocalSyncEntry::relativePath), "local")
    requireUniqueSyncContentPaths(remoteEntries.map(RemoteSyncEntry::relativePath), "remote")
    requireUniqueSyncContentPaths(
        verificationResults.map { it.candidate.relativePath },
        "content verification",
    )
    val localByPath = localEntries.associateBy(LocalSyncEntry::relativePath)
    val remoteByPath = remoteEntries.associateBy(RemoteSyncEntry::relativePath)
    val pairedFilePaths = localEntries.asSequence()
        .filter { local ->
            local.kind == SyncEntryKind.File &&
                remoteByPath[local.relativePath]?.kind == SyncEntryKind.File
        }
        .mapTo(mutableSetOf(), LocalSyncEntry::relativePath)
    val verifiedLocalByPath = verificationResults.associateBy { result ->
        val candidate = result.candidate
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
    val matchingByPath = verifiedLocalByPath.filterValues { it.matchingContentHash != null }
    return FileSyncContentIdentitySnapshot(
        localEntries = localEntries.map { entry ->
            when {
                entry.relativePath in verifiedLocalByPath -> entry.copy(
                    contentHash = requireNotNull(verifiedLocalByPath[entry.relativePath]).localContentHash,
                    contentIdentityUnverified = false,
                    replacementContentIdentityUnavailable = false,
                )
                entry.relativePath in pairedFilePaths -> entry.copy(contentHash = null)
                else -> entry
            }
        },
        remoteEntries = remoteEntries.map { entry ->
            when {
                entry.relativePath in matchingByPath -> entry.copy(
                    contentHash = requireNotNull(matchingByPath[entry.relativePath]).matchingContentHash,
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

const val FILE_SYNC_IDENTITY_SLICE_BYTES = 8 * 1024 * 1024
const val FILE_SYNC_IDENTITY_SCAN_SLICES = 8
const val FILE_SYNC_IDENTITY_SCAN_BYTES = FILE_SYNC_IDENTITY_SLICE_BYTES.toLong() * FILE_SYNC_IDENTITY_SCAN_SLICES
const val MAX_FILE_SYNC_IDENTITY_FILE_BYTES = Long.MAX_VALUE
const val MAX_FILE_SYNC_IDENTITY_TOTAL_BYTES = FILE_SYNC_IDENTITY_SCAN_BYTES
const val EMPTY_FILE_SYNC_IDENTITY_AGGREGATE =
    "sha256:0000000000000000000000000000000000000000000000000000000000000000"
