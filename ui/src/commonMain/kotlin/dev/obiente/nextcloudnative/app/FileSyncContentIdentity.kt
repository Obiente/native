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
): List<FileSyncContentVerificationCandidate> {
    requireUniqueSyncContentPaths(localEntries.map(LocalSyncEntry::relativePath), "local")
    requireUniqueSyncContentPaths(remoteEntries.map(RemoteSyncEntry::relativePath), "remote")
    requireUniqueSyncContentPaths(baselines.map(FileSyncBaseline::relativePath), "baseline")
    val remoteByPath = remoteEntries.associateBy(RemoteSyncEntry::relativePath)
    val baselineByPath = baselines.associateBy(FileSyncBaseline::relativePath)
    return localEntries.asSequence()
        .filter { it.kind == SyncEntryKind.File }
        .mapNotNull { local ->
            val remote = remoteByPath[local.relativePath]
                ?.takeIf { it.kind == SyncEntryKind.File }
                ?: return@mapNotNull null
            if (local.size != null && remote.size != null && local.size != remote.size) {
                return@mapNotNull null
            }
            val baseline = baselineByPath[local.relativePath]
            if (
                baseline != null &&
                baseline.kind == SyncEntryKind.File &&
                baseline.localRevision == local.revision &&
                baseline.remoteEtag == remote.etag
            ) {
                return@mapNotNull null
            }
            FileSyncContentVerificationCandidate(
                relativePath = local.relativePath,
                localRevision = local.revision,
                remoteEtag = remote.etag,
                expectedSizeBytes = local.size ?: remote.size,
            )
        }
        .sortedBy(FileSyncContentVerificationCandidate::relativePath)
        .toList()
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
