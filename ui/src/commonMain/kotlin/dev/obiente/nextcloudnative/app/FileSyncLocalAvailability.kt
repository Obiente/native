package dev.obiente.nextcloudnative.app

/** Why a platform scan could not observe one local item. */
enum class FileSyncLocalUnavailableReason {
    /** The item disappeared or was replaced between directory listing and reading. */
    Vanished,

    /** The item exists but could not be read, for example while another app holds it open. */
    Unreadable,

    /** The item is not a regular file or folder, for example a socket, device, or reparse point. */
    Unsupported,
}

/**
 * A local path whose current state is unknown for one scan.
 *
 * The item and every descendant are withheld from planning, so an unreadable subtree is never
 * interpreted as a local deletion, a missing file to restore, or new content to upload.
 */
data class FileSyncUnavailableLocalItem(
    val relativePath: String,
    val reason: FileSyncLocalUnavailableReason,
) {
    init {
        requireValidSyncPath(relativePath)
        require(relativePath.length <= MAX_FILE_SYNC_PATH_LENGTH)
    }
}

/** Local and remote observations that remain after unavailable local subtrees are withheld. */
data class FileSyncAvailableObservations(
    val localEntries: List<LocalSyncEntry>,
    val remoteEntries: List<RemoteSyncEntry>,
    val unavailable: List<FileSyncUnavailableLocalItem>,
)

/**
 * Withholds every local and remote observation at or below an unavailable local path.
 *
 * Persisted baselines are untouched, so a later complete scan resumes normal planning. Nested
 * reports collapse into their outermost unavailable ancestor.
 */
fun withholdUnavailableFileSyncPaths(
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
    unavailable: List<FileSyncUnavailableLocalItem>,
): FileSyncAvailableObservations {
    val withheldPaths = HashSet<String>()
    fun withheld(path: String): Boolean = path.syncPathAndAncestors().any { it in withheldPaths }
    // Shallower paths first, so every nested report finds its already-withheld ancestor.
    val outermost = unavailable
        .sortedWith(compareBy({ it.relativePath.count { char -> char == '/' } }, { it.relativePath }))
        .filter { item -> !withheld(item.relativePath) && withheldPaths.add(item.relativePath) }
        .sortedBy(FileSyncUnavailableLocalItem::relativePath)
    if (outermost.isEmpty()) return FileSyncAvailableObservations(localEntries, remoteEntries, emptyList())
    fun available(path: String) = !withheld(path)
    return FileSyncAvailableObservations(
        localEntries = localEntries.filter { available(it.relativePath) },
        remoteEntries = remoteEntries.filter { available(it.relativePath) },
        unavailable = outermost,
    )
}

/**
 * Records each unavailable local item as visible skipped work after planning.
 *
 * At most [maximumReports] items become work items; the run summary reports the complete count.
 */
fun FileSyncCoordinatorState.withUnavailableFileSyncReports(
    pairId: String,
    unavailable: List<FileSyncUnavailableLocalItem>,
    maximumReports: Int = MAX_FILE_SYNC_UNAVAILABLE_REPORTS,
): FileSyncCoordinatorState {
    require(maximumReports in 0..MAX_FILE_SYNC_UNAVAILABLE_REPORTS)
    if (unavailable.isEmpty() || maximumReports == 0) return this
    return copy(
        pairs = pairs.map { pair ->
            if (pair.id != pairId) return@map pair
            val plannedPaths = pair.workItems.mapTo(HashSet(), FileSyncWorkItem::relativePath)
            var nextId = pair.nextWorkId
            val reports = unavailable.asSequence()
                .filter { it.relativePath !in plannedPaths }
                .take(maximumReports)
                .map { item ->
                    require(nextId < Long.MAX_VALUE) { "The sync work ID space is exhausted." }
                    FileSyncWorkItem(
                        id = nextId++,
                        relativePath = item.relativePath,
                        observedLocal = null,
                        observedRemote = null,
                        observedBaseline = null,
                        operation = FileSyncOperation.Skipped(item.relativePath, item.skippedReason()),
                        state = FileSyncExecutionState.Skipped,
                    )
                }
                .toList()
            pair.copy(workItems = pair.workItems + reports, nextWorkId = nextId)
        },
    )
}

/**
 * True for a skipped report produced by [withUnavailableFileSyncReports]. Such work has no observed
 * generation to re-plan, so persisted validation instead requires an unexecuted item whose reason
 * is exactly one generated for its path.
 */
internal fun FileSyncWorkItem.isUnavailableLocalItemReport(): Boolean {
    val skipped = operation as? FileSyncOperation.Skipped ?: return false
    return state == FileSyncExecutionState.Skipped &&
        observedLocal == null && observedRemote == null && observedBaseline == null &&
        decision == null && attemptCount == 0 && lastAttemptEpochMillis == null &&
        uploadCheckpoint == null && !contentMismatchVerified &&
        FileSyncLocalUnavailableReason.entries.any { reason ->
            FileSyncUnavailableLocalItem(relativePath, reason).skippedReason() == skipped.reason
        }
}

/** One reviewable sentence per item; the path keeps each unavailable item individually visible. */
internal fun FileSyncUnavailableLocalItem.skippedReason(): String {
    val path = relativePath.boundedForSyncReason()
    return when (reason) {
        FileSyncLocalUnavailableReason.Vanished ->
            "Not synced this time: $path disappeared or changed while the folder was checked. " +
                "It will be checked again on the next sync."
        FileSyncLocalUnavailableReason.Unreadable ->
            "Not synced this time: $path could not be read. It may be open in another app or " +
                "access may be denied. It will be checked again on the next sync."
        FileSyncLocalUnavailableReason.Unsupported ->
            "Not synced: $path is not a regular file or folder."
    }
}

private fun String.boundedForSyncReason(): String {
    if (length <= MAX_FILE_SYNC_REASON_PATH_LENGTH) return this
    val keep = (MAX_FILE_SYNC_REASON_PATH_LENGTH - 3) / 2
    return take(keep) + "..." + takeLast(keep)
}

private fun String.syncPathAndAncestors(): Sequence<String> =
    generateSequence(this) { path -> path.substringBeforeLast('/', "").takeIf(String::isNotEmpty) }

/** Visible skipped-work reports kept per run; every unavailable path is withheld regardless. */
internal const val MAX_FILE_SYNC_UNAVAILABLE_REPORTS = 1_000
private const val MAX_FILE_SYNC_REASON_PATH_LENGTH = 600
