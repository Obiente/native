package dev.obiente.nextcloudnative.app

/**
 * Persistent, credential-free state for one local-root/remote-root relationship.
 *
 * [localRootId] is an opaque platform grant or bookmark identifier, not an absolute path. Platform
 * executors remain responsible for turning it into filesystem access and for implementing every
 * guarded [FileSyncOperation].
 */
data class FileSyncPair(
    val id: String,
    val accountId: String,
    val localRootId: String,
    val remoteRootPath: String,
    val configuration: FileSyncConfiguration,
    val baselines: List<FileSyncBaseline> = emptyList(),
    val workItems: List<FileSyncWorkItem> = emptyList(),
    val nextWorkId: Long = 1,
    val lastScanEpochMillis: Long? = null,
) {
    init {
        requireValidFileSyncPair(this)
    }
}

data class FileSyncCoordinatorState(
    val pairs: List<FileSyncPair> = emptyList(),
) {
    init {
        require(pairs.size <= MAX_FILE_SYNC_PAIRS) { "The sync state contains too many pairs." }
        require(pairs.map(FileSyncPair::id).distinct().size == pairs.size) {
            "The sync state contains duplicate pair IDs."
        }
    }
}

enum class FileSyncExecutionState {
    AwaitingDecision,
    Ready,
    Running,
    Failed,
    Skipped,
}

enum class FileSyncDecisionChoice {
    UseLocal,
    UseRemote,
    KeepBoth,
    PropagateDeletion,
    RestoreMissing,
    Skip,
}

sealed interface FileSyncDecisionState {
    data object Pending : FileSyncDecisionState
    data class Resolved(val choice: FileSyncDecisionChoice) : FileSyncDecisionState
}

data class FileSyncDecision(
    val reason: FileSyncDecisionReason,
    val choices: Set<FileSyncDecisionChoice>,
    val state: FileSyncDecisionState = FileSyncDecisionState.Pending,
) {
    init {
        require(choices.isNotEmpty() && choices.all { it in allowedFileSyncDecisions(reason) }) {
            "The decision choices are not valid for the conflict reason."
        }
        val resolved = state as? FileSyncDecisionState.Resolved
        require(resolved == null || resolved.choice in choices)
    }
}

/**
 * A durable unit of planned work. The observed entries and baseline make a decision generation
 * specific: rescanning changed source revisions replaces stale decisions instead of applying them.
 */
data class FileSyncWorkItem(
    val id: Long,
    val relativePath: String,
    val observedLocal: LocalSyncEntry?,
    val observedRemote: RemoteSyncEntry?,
    val observedBaseline: FileSyncBaseline?,
    val operation: FileSyncOperation,
    val state: FileSyncExecutionState,
    val decision: FileSyncDecision? = null,
    val attemptCount: Int = 0,
    val lastAttemptEpochMillis: Long? = null,
    val failureMessage: String? = null,
) {
    init {
        require(id > 0)
        requireValidSyncPath(relativePath)
        require(observedLocal?.relativePath == relativePath || observedLocal == null)
        require(observedRemote?.relativePath == relativePath || observedRemote == null)
        require(observedBaseline?.relativePath == relativePath || observedBaseline == null)
        require(operation.relativePath == relativePath)
        require(attemptCount in 0..MAX_FILE_SYNC_ATTEMPTS)
        require(lastAttemptEpochMillis == null || lastAttemptEpochMillis >= 0)
        require(failureMessage == null || failureMessage.isSafeSyncText(MAX_FILE_SYNC_FAILURE_LENGTH))
        requireValidWorkState()
    }

    private fun requireValidWorkState() {
        when (state) {
            FileSyncExecutionState.AwaitingDecision -> {
                require(operation is FileSyncOperation.NeedsDecision)
                require(decision?.reason == operation.reason)
                require(decision.state == FileSyncDecisionState.Pending)
                require(failureMessage == null)
            }
            FileSyncExecutionState.Ready,
            FileSyncExecutionState.Running,
            FileSyncExecutionState.Failed,
            -> {
                require(operation.isExecutable())
                require(decision == null || decision.state is FileSyncDecisionState.Resolved)
                require((state == FileSyncExecutionState.Failed) == (failureMessage != null))
            }
            FileSyncExecutionState.Skipped -> {
                require(operation is FileSyncOperation.Skipped)
                require(decision == null || decision.state is FileSyncDecisionState.Resolved)
                require(failureMessage == null)
            }
        }
    }
}

data class FileSyncExecutionCommand(
    val pairId: String,
    val workId: Long,
    val operation: FileSyncOperation,
)

data class FileSyncClaim(
    val state: FileSyncCoordinatorState,
    val command: FileSyncExecutionCommand?,
)

/**
 * Verified post-mutation state reported by an executor.
 *
 * A successful upload/download must return a baseline containing both resulting revisions. A
 * successful deletion must explicitly report the removed path. Keep-both must report synchronized
 * baselines for the original and both generated conflict paths.
 */
data class FileSyncExecutionSuccess(
    val synchronizedBaselines: List<FileSyncBaseline> = emptyList(),
    val removedRelativePaths: List<String> = emptyList(),
) {
    init {
        require(synchronizedBaselines.size <= MAX_FILE_SYNC_RESULT_PATHS)
        require(removedRelativePaths.size <= MAX_FILE_SYNC_RESULT_PATHS)
        requireUniqueCoordinatorPaths(
            synchronizedBaselines.map(FileSyncBaseline::relativePath),
            "result baseline",
        )
        requireUniqueCoordinatorPaths(removedRelativePaths, "removed result")
        removedRelativePaths.forEach(::requireValidSyncPath)
        require(
            synchronizedBaselines.map(FileSyncBaseline::relativePath).none { it in removedRelativePaths },
        ) { "A sync result cannot synchronize and remove the same path." }
        synchronizedBaselines.forEach { baseline ->
            require(!baseline.localRevision.isNullOrBlank() && !baseline.remoteEtag.isNullOrBlank()) {
                "A verified synchronized baseline requires both local and remote revisions."
            }
        }
    }
}

fun addFileSyncPair(
    state: FileSyncCoordinatorState,
    pair: FileSyncPair,
): FileSyncCoordinatorState {
    require(state.pairs.none { it.id == pair.id }) { "The sync pair already exists." }
    require(state.pairs.size < MAX_FILE_SYNC_PAIRS) { "The sync state contains too many pairs." }
    return state.copy(pairs = state.pairs + pair)
}

fun removeFileSyncPair(
    state: FileSyncCoordinatorState,
    pairId: String,
): FileSyncCoordinatorState {
    val pair = state.requirePair(pairId)
    require(pair.workItems.none { it.state == FileSyncExecutionState.Running }) {
        "A sync pair cannot be removed while work is running."
    }
    return state.copy(pairs = state.pairs.filterNot { it.id == pairId })
}

fun updateFileSyncPairConfiguration(
    state: FileSyncCoordinatorState,
    pairId: String,
    configuration: FileSyncConfiguration,
): FileSyncCoordinatorState = state.updatePair(pairId) { pair ->
    require(pair.workItems.none { it.state == FileSyncExecutionState.Running }) {
        "Sync configuration cannot change while work is running."
    }
    pair.copy(configuration = configuration, workItems = emptyList())
}

/**
 * Reconciles fresh abstract snapshots with persisted baselines.
 *
 * Stable pending decisions, failures, retries, and user resolutions survive rescans only while the
 * observed local revision, remote ETag, baseline, and planner outcome are unchanged.
 */
fun scanFileSyncPair(
    state: FileSyncCoordinatorState,
    pairId: String,
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
    nowEpochMillis: Long,
    maximumWorkItems: Int = MAX_FILE_SYNC_WORK_ITEMS,
    reservedNonExecutableWorkItems: Int = 0,
): FileSyncCoordinatorState = state.updatePair(pairId) { pair ->
    require(nowEpochMillis >= 0)
    require(maximumWorkItems in 1..MAX_FILE_SYNC_WORK_ITEMS)
    require(reservedNonExecutableWorkItems in 0 until maximumWorkItems)
    require(localEntries.size <= MAX_FILE_SYNC_ENTRIES) { "The local sync snapshot is too large." }
    require(remoteEntries.size <= MAX_FILE_SYNC_ENTRIES) { "The remote sync snapshot is too large." }
    require(pair.workItems.none { it.state == FileSyncExecutionState.Running }) {
        "A sync pair cannot be rescanned while work is running."
    }
    require(localEntries.map(LocalSyncEntry::relativePath).distinct().size == localEntries.size) {
        "The local sync snapshot contains duplicate paths."
    }
    require(remoteEntries.map(RemoteSyncEntry::relativePath).distinct().size == remoteEntries.size) {
        "The remote sync snapshot contains duplicate paths."
    }
    val scopedLocalEntries = localEntries.filter { entry ->
        pair.configuration.includesSyncPath(entry.relativePath, entry.kind)
    }
    val scopedRemoteEntries = remoteEntries.filter { entry ->
        pair.configuration.includesSyncPath(entry.relativePath, entry.kind)
    }
    val scopedBaselines = pair.baselines.filter { baseline ->
        pair.configuration.includesSyncPath(baseline.relativePath, baseline.kind)
    }
    val localByPath = scopedLocalEntries.associateBy(LocalSyncEntry::relativePath)
    val remoteByPath = scopedRemoteEntries.associateBy(RemoteSyncEntry::relativePath)
    val baselineByPath = scopedBaselines.associateBy(FileSyncBaseline::relativePath)
    val existingWorkByPath = pair.workItems.associateBy(FileSyncWorkItem::relativePath)
    val plan = planFileSync(scopedLocalEntries, scopedRemoteEntries, scopedBaselines, pair.configuration)
    require(plan.operations.size <= MAX_FILE_SYNC_WORK_ITEMS) {
        "The sync snapshot requires too many operations. Narrow the selected folders."
    }
    fun stableExistingWork(operation: FileSyncOperation): FileSyncWorkItem? {
        val path = operation.relativePath
        return existingWorkByPath[path]?.takeIf { current ->
            current.sameGeneration(operation, localByPath[path], remoteByPath[path], baselineByPath[path])
        }
    }
    val sortedOperations = plan.operations.sortedWith(
        fileSyncOperationComparator(pair.configuration, localByPath, remoteByPath),
    )
    val selectedOperations = if (reservedNonExecutableWorkItems == 0) {
        sortedOperations.take(maximumWorkItems)
    } else {
        val maximumExecutableWorkItems = maximumWorkItems - reservedNonExecutableWorkItems
        val executable = ArrayList<FileSyncOperation>(maximumExecutableWorkItems)
        val retainedDecisions = ArrayList<FileSyncOperation>(reservedNonExecutableWorkItems)
        val newDecisions = ArrayList<FileSyncOperation>(reservedNonExecutableWorkItems)
        val retainedOther = ArrayList<FileSyncOperation>(reservedNonExecutableWorkItems)
        val newSkipped = ArrayList<FileSyncOperation>(reservedNonExecutableWorkItems)
        sortedOperations.forEach { operation ->
            val existing = stableExistingWork(operation)
            val effectiveOperation = existing?.operation ?: operation
            val canRunAutomatically = effectiveOperation.isExecutable() &&
                (existing == null || existing.canRunAutomatically())
            when {
                canRunAutomatically && executable.size < maximumExecutableWorkItems ->
                    executable += operation
                effectiveOperation is FileSyncOperation.NeedsDecision &&
                    existing != null && retainedDecisions.size < reservedNonExecutableWorkItems ->
                    retainedDecisions += operation
                effectiveOperation is FileSyncOperation.NeedsDecision &&
                    newDecisions.size < reservedNonExecutableWorkItems ->
                    newDecisions += operation
                existing != null && retainedOther.size < reservedNonExecutableWorkItems ->
                    retainedOther += operation
                effectiveOperation is FileSyncOperation.Skipped &&
                    newSkipped.size < reservedNonExecutableWorkItems -> newSkipped += operation
            }
        }
        executable + (retainedDecisions + newDecisions + retainedOther + newSkipped)
            .take(reservedNonExecutableWorkItems)
    }
    var nextId = pair.nextWorkId
    val work = selectedOperations.map { operation ->
        val path = operation.relativePath
        val local = localByPath[path]
        val remote = remoteByPath[path]
        val baseline = baselineByPath[path]
        stableExistingWork(operation) ?: FileSyncWorkItem(
            id = nextId.also {
                require(it < Long.MAX_VALUE) { "The sync work ID space is exhausted." }
                nextId += 1
            },
            relativePath = path,
            observedLocal = local,
            observedRemote = remote,
            observedBaseline = baseline,
            operation = operation,
            state = operation.initialExecutionState(),
            decision = (operation as? FileSyncOperation.NeedsDecision)?.let { needed ->
                FileSyncDecision(
                    needed.reason,
                    allowedFileSyncDecisions(needed.reason, pair.configuration),
                )
            },
        )
    }
    val structuralBaselines = scopedLocalEntries
        .asSequence()
        .filter { it.kind == SyncEntryKind.Directory && it.relativePath !in baselineByPath }
        .mapNotNull { local ->
            remoteByPath[local.relativePath]
                ?.takeIf { it.kind == SyncEntryKind.Directory }
                ?.let { remote ->
                    FileSyncBaseline(
                        relativePath = local.relativePath,
                        kind = SyncEntryKind.Directory,
                        localRevision = local.revision,
                        remoteEtag = remote.etag,
                    )
                }
        }
        .toList()
    val contentVerifiedBaselines = scopedLocalEntries
        .asSequence()
        .filter { local ->
            local.kind == SyncEntryKind.File &&
                local.contentHash != null &&
                local.contentHash == remoteByPath[local.relativePath]?.contentHash
        }
        .map { local ->
            val remote = requireNotNull(remoteByPath[local.relativePath])
            FileSyncBaseline(
                relativePath = local.relativePath,
                kind = SyncEntryKind.File,
                localRevision = local.revision,
                remoteEtag = remote.etag,
            )
        }
        .toList()
    val contentVerifiedPaths = contentVerifiedBaselines
        .mapTo(mutableSetOf(), FileSyncBaseline::relativePath)
    pair.copy(
        baselines = (
            pair.baselines.filterNot { it.relativePath in contentVerifiedPaths } +
                structuralBaselines +
                contentVerifiedBaselines
            ).sortedBy(FileSyncBaseline::relativePath),
        workItems = work,
        nextWorkId = nextId,
        lastScanEpochMillis = nowEpochMillis,
    )
}

private fun fileSyncOperationComparator(
    configuration: FileSyncConfiguration,
    localByPath: Map<String, LocalSyncEntry>,
    remoteByPath: Map<String, RemoteSyncEntry>,
): Comparator<FileSyncOperation> = Comparator { left, right ->
    compareFileSyncOperations(
        left,
        localByPath[left.relativePath]?.kind ?: remoteByPath[left.relativePath]?.kind,
        right,
        localByPath[right.relativePath]?.kind ?: remoteByPath[right.relativePath]?.kind,
        configuration,
    )
}

private fun compareFileSyncOperations(
    left: FileSyncOperation,
    leftKind: SyncEntryKind?,
    right: FileSyncOperation,
    rightKind: SyncEntryKind?,
    configuration: FileSyncConfiguration,
): Int {
    val leftDelete = left is FileSyncOperation.DeleteLocal || left is FileSyncOperation.DeleteRemote
    val rightDelete = right is FileSyncOperation.DeleteLocal || right is FileSyncOperation.DeleteRemote
    val leftDirectory = leftKind == SyncEntryKind.Directory
    val rightDirectory = rightKind == SyncEntryKind.Directory
    return when {
        leftDelete != rightDelete -> if (leftDelete) 1 else -1
        leftDelete && leftDirectory != rightDirectory -> if (leftDirectory) 1 else -1
        leftDelete -> compareValues(
            right.relativePath.count { it == '/' }, left.relativePath.count { it == '/' },
        ).takeIf { it != 0 } ?: compareValues(left.relativePath, right.relativePath)
        leftDirectory != rightDirectory -> if (leftDirectory) -1 else 1
        leftDirectory -> compareValues(
            left.relativePath.count { it == '/' }, right.relativePath.count { it == '/' },
        ).takeIf { it != 0 } ?: compareValues(left.relativePath, right.relativePath)
        else -> compareValues(
            configuration.fileSyncPriority(left.relativePath),
            configuration.fileSyncPriority(right.relativePath),
        ).takeIf { it != 0 } ?: compareValues(left.relativePath, right.relativePath)
    }
}

fun resolveFileSyncDecision(
    state: FileSyncCoordinatorState,
    pairId: String,
    workId: Long,
    choice: FileSyncDecisionChoice,
): FileSyncCoordinatorState = state.updatePair(pairId) { pair ->
    pair.updateWork(workId) { work ->
        require(work.state == FileSyncExecutionState.AwaitingDecision) {
            "The sync work item is not awaiting a decision."
        }
        val decision = requireNotNull(work.decision)
        require(choice in decision.choices) { "That choice is not valid for this conflict." }
        val operation = resolveDecisionOperation(pair, work, choice)
        work.copy(
            operation = operation,
            state = operation.initialExecutionState(),
            decision = decision.copy(state = FileSyncDecisionState.Resolved(choice)),
        )
    }
}

fun claimNextFileSyncOperation(
    state: FileSyncCoordinatorState,
    pairId: String,
    nowEpochMillis: Long,
): FileSyncClaim {
    require(nowEpochMillis >= 0)
    val pair = state.requirePair(pairId)
    require(pair.workItems.none { it.state == FileSyncExecutionState.Running }) {
        "Only one operation per sync pair may run at a time."
    }
    val next = pair.workItems.firstOrNull { it.state == FileSyncExecutionState.Ready }
        ?: return FileSyncClaim(state, null)
    require(next.attemptCount < MAX_FILE_SYNC_ATTEMPTS) { "The sync work item exceeded its retry limit." }
    val updated = state.updatePair(pairId) { current ->
        current.updateWork(next.id) { work ->
            work.copy(
                state = FileSyncExecutionState.Running,
                attemptCount = work.attemptCount + 1,
                lastAttemptEpochMillis = nowEpochMillis,
            )
        }
    }
    return FileSyncClaim(updated, FileSyncExecutionCommand(pairId, next.id, next.operation))
}

fun completeFileSyncOperation(
    state: FileSyncCoordinatorState,
    pairId: String,
    workId: Long,
    success: FileSyncExecutionSuccess,
): FileSyncCoordinatorState = state.updatePair(pairId) { pair ->
    val work = pair.requireWork(workId)
    require(work.state == FileSyncExecutionState.Running) { "The sync work item is not running." }
    val footprint = work.operation.executionFootprint()
    val synchronizedPaths = success.synchronizedBaselines.map(FileSyncBaseline::relativePath).toSet()
    val removedPaths = success.removedRelativePaths.toSet()
    require(synchronizedPaths + removedPaths == footprint) {
        "The verified sync result does not exactly cover the operation footprint."
    }
    when (work.operation) {
        is FileSyncOperation.DeleteLocal,
        is FileSyncOperation.DeleteRemote,
        -> require(removedPaths == setOf(work.relativePath)) {
            "A verified deletion must remove its planned path."
        }
        is FileSyncOperation.Upload,
        is FileSyncOperation.Download,
        -> require(synchronizedPaths == setOf(work.relativePath)) {
            "A verified transfer must synchronize its planned path."
        }
        is FileSyncOperation.KeepBoth -> require(removedPaths.isEmpty()) {
            "Keep-both cannot report removed paths."
        }
        is FileSyncOperation.NeedsDecision,
        is FileSyncOperation.Skipped,
        -> error("Non-executable work cannot complete.")
    }
    val replacedPaths = footprint
    pair.copy(
        baselines = (
            pair.baselines.filterNot { it.relativePath in replacedPaths } +
                success.synchronizedBaselines
            ).sortedBy(FileSyncBaseline::relativePath),
        workItems = pair.workItems.filterNot { it.id == workId },
    )
}

fun failFileSyncOperation(
    state: FileSyncCoordinatorState,
    pairId: String,
    workId: Long,
    message: String,
): FileSyncCoordinatorState {
    require(message.isSafeSyncText(MAX_FILE_SYNC_FAILURE_LENGTH)) {
        "The sync failure message is invalid."
    }
    return state.updatePair(pairId) { pair ->
        pair.updateWork(workId) { work ->
            require(work.state == FileSyncExecutionState.Running) { "The sync work item is not running." }
            work.copy(state = FileSyncExecutionState.Failed, failureMessage = message)
        }
    }
}

fun retryFileSyncOperation(
    state: FileSyncCoordinatorState,
    pairId: String,
    workId: Long,
): FileSyncCoordinatorState = state.updatePair(pairId) { pair ->
    pair.updateWork(workId) { work ->
        require(work.state == FileSyncExecutionState.Failed) { "The sync work item has not failed." }
        require(work.attemptCount < MAX_FILE_SYNC_ATTEMPTS) { "The sync work item exceeded its retry limit." }
        work.copy(state = FileSyncExecutionState.Ready, failureMessage = null)
    }
}

/** Explicit user recovery for work that exhausted the automatic retry budget. */
fun resetExhaustedFileSyncOperations(
    state: FileSyncCoordinatorState,
    pairId: String,
): FileSyncCoordinatorState = state.updatePair(pairId) { pair ->
    pair.copy(
        workItems = pair.workItems.map { work ->
            if (work.state == FileSyncExecutionState.Failed && work.attemptCount >= MAX_FILE_SYNC_ATTEMPTS) {
                work.copy(
                    state = FileSyncExecutionState.Ready,
                    attemptCount = 0,
                    lastAttemptEpochMillis = null,
                    failureMessage = null,
                )
            } else {
                work
            }
        },
    )
}

internal fun recoverInterruptedFileSyncWork(state: FileSyncCoordinatorState): FileSyncCoordinatorState =
    state.copy(
        pairs = state.pairs.map { pair ->
            pair.copy(
                workItems = pair.workItems.map { work ->
                    if (work.state == FileSyncExecutionState.Running) {
                        work.copy(state = FileSyncExecutionState.Ready)
                    } else {
                        work
                    }
                },
            )
        },
    )

private fun allowedFileSyncDecisions(reason: FileSyncDecisionReason): Set<FileSyncDecisionChoice> = when (reason) {
    FileSyncDecisionReason.FirstSyncCollision,
    FileSyncDecisionReason.SimultaneousEdit,
    -> setOf(
        FileSyncDecisionChoice.UseLocal,
        FileSyncDecisionChoice.UseRemote,
        FileSyncDecisionChoice.KeepBoth,
        FileSyncDecisionChoice.Skip,
    )
    FileSyncDecisionReason.TypeChanged -> setOf(
        FileSyncDecisionChoice.UseLocal,
        FileSyncDecisionChoice.UseRemote,
        FileSyncDecisionChoice.Skip,
    )
    FileSyncDecisionReason.LocalDeletion,
    FileSyncDecisionReason.RemoteDeletion,
    -> setOf(
        FileSyncDecisionChoice.PropagateDeletion,
        FileSyncDecisionChoice.RestoreMissing,
        FileSyncDecisionChoice.Skip,
    )
}

private fun allowedFileSyncDecisions(
    reason: FileSyncDecisionReason,
    configuration: FileSyncConfiguration,
): Set<FileSyncDecisionChoice> = allowedFileSyncDecisions(reason).filterTo(linkedSetOf()) { choice ->
    when (choice) {
        FileSyncDecisionChoice.PropagateDeletion -> when (reason) {
            FileSyncDecisionReason.LocalDeletion -> configuration.direction != FileSyncDirection.DownloadOnly
            FileSyncDecisionReason.RemoteDeletion -> configuration.direction != FileSyncDirection.UploadOnly
            else -> true
        }
        FileSyncDecisionChoice.RestoreMissing -> when (reason) {
            FileSyncDecisionReason.LocalDeletion -> configuration.direction != FileSyncDirection.UploadOnly
            FileSyncDecisionReason.RemoteDeletion -> configuration.direction != FileSyncDirection.DownloadOnly
            else -> true
        }
        else -> true
    }
}

private fun resolveDecisionOperation(
    pair: FileSyncPair,
    work: FileSyncWorkItem,
    choice: FileSyncDecisionChoice,
): FileSyncOperation = when (choice) {
    FileSyncDecisionChoice.UseLocal -> {
        requireNotNull(work.observedLocal) { "There is no local entry to keep." }
        FileSyncOperation.Upload(work.relativePath, work.observedRemote?.etag)
    }
    FileSyncDecisionChoice.UseRemote -> {
        requireNotNull(work.observedRemote) { "There is no remote entry to keep." }
        FileSyncOperation.Download(work.relativePath, work.observedLocal?.revision)
    }
    FileSyncDecisionChoice.KeepBoth -> {
        requireNotNull(work.observedLocal) { "There is no local entry to preserve." }
        requireNotNull(work.observedRemote) { "There is no remote entry to preserve." }
        val label = pair.configuration.deviceLabel.syncDeviceLabel()
        FileSyncOperation.KeepBoth(
            work.relativePath,
            fileSyncConflictCopyPath(work.relativePath, "$label-local"),
            fileSyncConflictCopyPath(work.relativePath, "server"),
        )
    }
    FileSyncDecisionChoice.PropagateDeletion -> when (work.decision?.reason) {
        FileSyncDecisionReason.LocalDeletion -> if (
            pair.configuration.hasPartialDirectoryView() && work.observedRemote?.kind == SyncEntryKind.Directory
        ) {
            FileSyncOperation.Skipped(work.relativePath, PARTIAL_DIRECTORY_DECISION_REASON)
        } else {
            FileSyncOperation.DeleteRemote(work.relativePath, requireNotNull(work.observedRemote).etag)
        }
        FileSyncDecisionReason.RemoteDeletion -> if (
            pair.configuration.hasPartialDirectoryView() && work.observedLocal?.kind == SyncEntryKind.Directory
        ) {
            FileSyncOperation.Skipped(work.relativePath, PARTIAL_DIRECTORY_DECISION_REASON)
        } else {
            FileSyncOperation.DeleteLocal(work.relativePath, requireNotNull(work.observedLocal).revision)
        }
        else -> error("There is no deletion to propagate.")
    }
    FileSyncDecisionChoice.RestoreMissing -> when (work.decision?.reason) {
        FileSyncDecisionReason.LocalDeletion ->
            FileSyncOperation.Download(work.relativePath, expectedLocalRevision = null)
        FileSyncDecisionReason.RemoteDeletion ->
            FileSyncOperation.Upload(work.relativePath, expectedRemoteEtag = null)
        else -> error("There is no missing entry to restore.")
    }
    FileSyncDecisionChoice.Skip ->
        FileSyncOperation.Skipped(work.relativePath, "Skipped by the user for this observed generation.")
}

private fun FileSyncConfiguration.hasPartialDirectoryView(): Boolean =
    selectedPaths.isNotEmpty() || ignoredPatterns.isNotEmpty()

private const val PARTIAL_DIRECTORY_DECISION_REASON =
    "Directory deletion is paused because selective or ignored items may exist below it."

private fun FileSyncWorkItem.sameGeneration(
    planned: FileSyncOperation,
    local: LocalSyncEntry?,
    remote: RemoteSyncEntry?,
    baseline: FileSyncBaseline?,
): Boolean {
    if (observedLocal != local || observedRemote != remote || observedBaseline != baseline) return false
    return when {
        planned is FileSyncOperation.NeedsDecision ->
            decision?.reason == planned.reason
        decision != null -> false
        else -> operation == planned
    }
}

private fun FileSyncOperation.initialExecutionState(): FileSyncExecutionState = when (this) {
    is FileSyncOperation.NeedsDecision -> FileSyncExecutionState.AwaitingDecision
    is FileSyncOperation.Skipped -> FileSyncExecutionState.Skipped
    else -> FileSyncExecutionState.Ready
}

private fun FileSyncOperation.isExecutable(): Boolean =
    this !is FileSyncOperation.NeedsDecision && this !is FileSyncOperation.Skipped

private fun FileSyncWorkItem.canRunAutomatically(): Boolean = when (state) {
    FileSyncExecutionState.Ready -> true
    FileSyncExecutionState.Failed -> attemptCount < MAX_FILE_SYNC_ATTEMPTS
    FileSyncExecutionState.AwaitingDecision,
    FileSyncExecutionState.Running,
    FileSyncExecutionState.Skipped,
    -> false
}

private fun FileSyncOperation.executionFootprint(): Set<String> = when (this) {
    is FileSyncOperation.KeepBoth -> setOf(relativePath, localConflictPath, remoteConflictPath)
    else -> setOf(relativePath)
}

private fun FileSyncPair.updateWork(
    workId: Long,
    update: (FileSyncWorkItem) -> FileSyncWorkItem,
): FileSyncPair {
    val existing = requireWork(workId)
    val updated = update(existing)
    require(updated.id == existing.id)
    return copy(workItems = workItems.map { if (it.id == workId) updated else it })
}

private fun FileSyncPair.requireWork(workId: Long): FileSyncWorkItem =
    workItems.firstOrNull { it.id == workId } ?: error("The sync work item does not exist.")

private fun FileSyncCoordinatorState.requirePair(pairId: String): FileSyncPair =
    pairs.firstOrNull { it.id == pairId } ?: error("The sync pair does not exist.")

private fun FileSyncCoordinatorState.updatePair(
    pairId: String,
    update: (FileSyncPair) -> FileSyncPair,
): FileSyncCoordinatorState {
    val pair = requirePair(pairId)
    val updated = update(pair)
    require(updated.id == pair.id)
    return copy(pairs = pairs.map { if (it.id == pairId) updated else it })
}

private fun requireValidFileSyncPair(pair: FileSyncPair) {
    require(pair.id.isSafeSyncText(MAX_FILE_SYNC_ID_LENGTH))
    require(pair.accountId.isSafeSyncText(MAX_FILE_SYNC_ID_LENGTH))
    require(pair.localRootId.isSafeSyncText(MAX_FILE_SYNC_ROOT_ID_LENGTH))
    if (pair.remoteRootPath.isNotEmpty()) requireValidSyncPath(pair.remoteRootPath)
    require(pair.remoteRootPath.length <= MAX_FILE_SYNC_PATH_LENGTH)
    require(pair.configuration.deviceLabel.isSafeSyncText(MAX_FILE_SYNC_DEVICE_LABEL_LENGTH))
    pair.configuration.selectedPaths.forEach {
        require(it.length <= MAX_FILE_SYNC_PATH_LENGTH)
    }
    require(pair.baselines.size <= MAX_FILE_SYNC_ENTRIES) { "The sync pair contains too many baselines." }
    require(pair.workItems.size <= MAX_FILE_SYNC_WORK_ITEMS) { "The sync pair contains too much work." }
    requireUniqueCoordinatorPaths(pair.baselines.map(FileSyncBaseline::relativePath), "baseline")
    requireUniqueCoordinatorPaths(pair.workItems.map(FileSyncWorkItem::relativePath), "work")
    require(pair.workItems.map(FileSyncWorkItem::id).distinct().size == pair.workItems.size)
    require(pair.nextWorkId > 0 && pair.workItems.all { it.id < pair.nextWorkId })
    require(pair.lastScanEpochMillis == null || pair.lastScanEpochMillis >= 0)
    pair.baselines.forEach(::requireBoundedBaseline)
    pair.workItems.forEach { work ->
        requireBoundedWorkItem(work)
        val resolved = work.decision?.state as? FileSyncDecisionState.Resolved
        work.decision?.let { decision ->
            require(decision.choices == allowedFileSyncDecisions(decision.reason, pair.configuration)) {
                "The persisted sync decision choices do not match the pair direction."
            }
        }
        val expectedOperation = if (resolved != null) {
            resolveDecisionOperation(pair, work, resolved.choice)
        } else {
            planFileSync(
                localEntries = listOfNotNull(work.observedLocal),
                remoteEntries = listOfNotNull(work.observedRemote),
                baselines = listOfNotNull(work.observedBaseline),
                configuration = pair.configuration,
            ).operations.singleOrNull()
        }
        require(work.operation == expectedOperation) {
            "The persisted sync operation does not match its observed generation."
        }
    }
}

private fun requireBoundedBaseline(baseline: FileSyncBaseline) {
    require(baseline.relativePath.length <= MAX_FILE_SYNC_PATH_LENGTH)
    require(baseline.localRevision == null || baseline.localRevision.isSafeSyncText(MAX_FILE_SYNC_REVISION_LENGTH))
    require(baseline.remoteEtag == null || baseline.remoteEtag.isSafeSyncText(MAX_FILE_SYNC_REVISION_LENGTH))
}

private fun requireBoundedWorkItem(work: FileSyncWorkItem) {
    require(work.relativePath.length <= MAX_FILE_SYNC_PATH_LENGTH)
    work.observedLocal?.let {
        require(it.revision.isSafeSyncText(MAX_FILE_SYNC_REVISION_LENGTH))
    }
    work.observedRemote?.let {
        require(it.etag.isSafeSyncText(MAX_FILE_SYNC_REVISION_LENGTH))
    }
    work.observedBaseline?.let(::requireBoundedBaseline)
    when (val operation = work.operation) {
        is FileSyncOperation.Upload ->
            require(operation.expectedRemoteEtag == null ||
                operation.expectedRemoteEtag.isSafeSyncText(MAX_FILE_SYNC_REVISION_LENGTH))
        is FileSyncOperation.Download ->
            require(operation.expectedLocalRevision == null ||
                operation.expectedLocalRevision.isSafeSyncText(MAX_FILE_SYNC_REVISION_LENGTH))
        is FileSyncOperation.DeleteLocal ->
            require(operation.expectedLocalRevision.isSafeSyncText(MAX_FILE_SYNC_REVISION_LENGTH))
        is FileSyncOperation.DeleteRemote ->
            require(operation.expectedRemoteEtag.isSafeSyncText(MAX_FILE_SYNC_REVISION_LENGTH))
        is FileSyncOperation.KeepBoth -> {
            requireValidSyncPath(operation.localConflictPath)
            requireValidSyncPath(operation.remoteConflictPath)
            require(operation.localConflictPath.length <= MAX_FILE_SYNC_PATH_LENGTH)
            require(operation.remoteConflictPath.length <= MAX_FILE_SYNC_PATH_LENGTH)
            require(setOf(operation.relativePath, operation.localConflictPath, operation.remoteConflictPath).size == 3)
        }
        is FileSyncOperation.NeedsDecision -> Unit
        is FileSyncOperation.Skipped ->
            require(operation.reason.isSafeSyncText(MAX_FILE_SYNC_FAILURE_LENGTH))
    }
}

private fun String.syncDeviceLabel(): String =
    lowercase().map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("").trim('-').take(24).ifBlank { "device" }

private fun String.isSafeSyncText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)

private fun requireUniqueCoordinatorPaths(paths: List<String>, source: String) {
    require(paths.distinct().size == paths.size) { "The $source sync state contains duplicate paths." }
}

internal const val MAX_FILE_SYNC_PAIRS = 64
internal const val MAX_FILE_SYNC_ENTRIES = 100_000
internal const val MAX_FILE_SYNC_WORK_ITEMS = MAX_FILE_SYNC_ENTRIES
internal const val MAX_FILE_SYNC_RESULT_PATHS = 3
internal const val MAX_FILE_SYNC_ATTEMPTS = 20
internal const val MAX_FILE_SYNC_ID_LENGTH = 256
internal const val MAX_FILE_SYNC_ROOT_ID_LENGTH = 2_048
internal const val MAX_FILE_SYNC_PATH_LENGTH = 8_192
internal const val MAX_FILE_SYNC_REVISION_LENGTH = 4_096
internal const val MAX_FILE_SYNC_DEVICE_LABEL_LENGTH = 128
internal const val MAX_FILE_SYNC_FAILURE_LENGTH = 1_024
