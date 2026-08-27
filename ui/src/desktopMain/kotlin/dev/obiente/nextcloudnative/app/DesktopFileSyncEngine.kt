package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable manual desktop executor. The common coordinator owns all planning and conflict rules. */
internal class DesktopFileSyncEngine(
    private val store: DesktopFileSyncStore = DesktopFileSyncStore(),
    private val stagingRoot: File = desktopFileSyncStagingDirectory(),
    private val minimumFreeSpaceBytes: () -> Long = { 0L },
    private val folderPicker: DesktopSystemFolderPicker = DesktopSystemFolderPicker(),
    private val stagingReservations: DesktopStagingSpaceReservations = sharedDesktopStagingSpaceReservations,
    private val onRemoteMutationCommitted: (session: NextcloudSession, userId: String, path: String) -> Unit =
        { _, _, _ -> },
) {
    private val selectedRoots = ConcurrentHashMap<String, File>()
    private val lock = Mutex()

    suspend fun chooseLocalRoot(initialRootHint: String?): FileSyncLocalRoot? = withContext(Dispatchers.IO) {
        val initialDirectory = initialRootHint?.let(selectedRoots::get)?.takeIf(File::isDirectory)
        val chosen = folderPicker.choose(initialDirectory) ?: return@withContext null
        val selected = chosen.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(selected)) {
            "Choose a regular local folder, not a symbolic link."
        }
        val token = "desktop-selection:${UUID.randomUUID()}"
        selectedRoots[token] = selected.toFile()
        FileSyncLocalRoot(token, selected.fileName?.toString()?.takeIf(String::isNotBlank) ?: "Selected folder")
    }

    suspend fun loadCenter(
        session: NextcloudSession,
        runState: FileSyncPairRunState,
        networkState: (FileSyncConfiguration) -> FileSyncNetworkState,
    ): FileSyncCenterSnapshot = lock.withLock {
        store.withExclusiveAccess {
            val accountId = desktopFileCacheAccountId(session)
            val account = store.loadAccount(accountId)
            val state = account.state
            FileSyncCenterSnapshot(
                support = FileSyncCenterSupport.Available,
                pairs = state.coordinator.pairs.filter { it.accountId == accountId }.map { pair ->
                    val root = state.roots.firstOrNull { it.id == pair.localRootId }
                    val work = account.workByPairId.getValue(pair.id)
                    pair.copy(workItems = work.conflicts).toCenterSummary(
                        localDisplayName = root?.displayName ?: "Selected folder",
                        localRootPath = root?.absolutePath,
                        scheduleDescription = "Automatic sync while Nextcloud Native is running",
                        completedCount = account.completedCountsByPairId.getValue(pair.id),
                        readyCount = work.readyCount,
                        runningCount = work.runningCount,
                        conflictCount = work.conflictCount,
                        failedCount = work.failedCount,
                        skippedCount = work.skippedCount,
                        skippedReasons = work.skippedReasons,
                        runState = runState,
                        networkState = networkState(pair.configuration),
                    )
                },
                limitation = null,
            )
        }
    }

    suspend fun loadTrayActivities(
        session: NextcloudSession,
        limit: Int = MAX_TRAY_ACTIVITY_ITEMS,
    ): List<DesktopFileSyncTrayActivity> = lock.withLock {
        store.withExclusiveAccess {
            require(limit in 1..MAX_TRAY_ACTIVITY_ITEMS)
            val accountId = desktopFileCacheAccountId(session)
            val account = store.loadAccount(accountId, trayLimit = limit)
            val state = account.state
            val pairsById = state.coordinator.pairs.associateBy(FileSyncPair::id)
            account.trayWorkItems
                .asSequence()
                .map { scoped ->
                    val pair = pairsById.getValue(scoped.pairId)
                    val root = state.roots.firstOrNull { it.id == pair.localRootId }
                    val pairLabel = syncPairLabel(root?.displayName ?: "Selected folder", pair.remoteRootPath)
                    val work = scoped.workItem
                    DesktopFileSyncTrayActivity(
                        stableId = "${pair.id}:${work.id}",
                        relativePath = work.relativePath,
                        pairLabel = pairLabel,
                        phase = when (work.state) {
                            FileSyncExecutionState.AwaitingDecision -> DesktopFileSyncTrayActivityPhase.Conflict
                            FileSyncExecutionState.Failed -> DesktopFileSyncTrayActivityPhase.Failed
                            FileSyncExecutionState.Ready -> DesktopFileSyncTrayActivityPhase.Waiting
                            FileSyncExecutionState.Running -> work.operation.toTrayActivityPhase()
                            FileSyncExecutionState.Skipped -> DesktopFileSyncTrayActivityPhase.Waiting
                        },
                        sizeBytes = work.observedLocal?.size ?: work.observedRemote?.size,
                        detail = work.failureMessage,
                    )
                }
                .sortedWith(
                    compareBy<DesktopFileSyncTrayActivity> {
                        when (it.phase) {
                            DesktopFileSyncTrayActivityPhase.Uploading,
                            DesktopFileSyncTrayActivityPhase.Downloading,
                            DesktopFileSyncTrayActivityPhase.Preparing,
                            -> 0
                            DesktopFileSyncTrayActivityPhase.Conflict,
                            DesktopFileSyncTrayActivityPhase.Failed,
                            -> 1
                            DesktopFileSyncTrayActivityPhase.Waiting -> 2
                            DesktopFileSyncTrayActivityPhase.Completed -> 3
                        }
                    }.thenBy(DesktopFileSyncTrayActivity::relativePath),
                )
                .take(limit)
                .toList()
        }
    }

    suspend fun addPair(
        session: NextcloudSession,
        localRoot: FileSyncLocalRoot,
        remoteRootPath: String,
        configuration: FileSyncConfiguration,
    ): FileSyncCenterActionResult = lock.withLock {
        store.withExclusiveAccess transaction@ {
            val selected = selectedRoots[localRoot.localRootId]
                ?: return@transaction FileSyncCenterActionResult.Rejected("Choose the local folder again.")
            val canonical = selected.canonicalFile
            DesktopFileSyncLocalTree(canonical)
            val normalizedRemote = normalizeRemoteRoot(remoteRootPath)
            val accountId = desktopFileCacheAccountId(session)
            val current = store.load()
            if (current.coordinator.pairs.any { pair ->
                    val existingRoot = current.roots.firstOrNull { it.id == pair.localRootId } ?: return@any true
                    desktopSyncMappingsOverlap(
                        existingAccountId = pair.accountId,
                        requestedAccountId = accountId,
                        existingLocalRoot = existingRoot.absolutePath,
                        requestedLocalRoot = canonical.absolutePath,
                        existingRemoteRoot = pair.remoteRootPath,
                        requestedRemoteRoot = normalizedRemote,
                    )
                }) {
                return@transaction FileSyncCenterActionResult.Rejected(
                    "This folder overlaps another local or Nextcloud sync mapping. Choose separate roots.",
                )
            }
            val rootId = UUID.randomUUID().toString()
            val pair = FileSyncPair(
                id = UUID.randomUUID().toString(),
                accountId = accountId,
                localRootId = rootId,
                remoteRootPath = normalizedRemote,
                configuration = configuration,
            )
            val added = current.copy(
                coordinator = addFileSyncPair(current.coordinator, pair),
                roots = current.roots + DesktopFileSyncRootRecord(
                    rootId,
                    canonical.absolutePath,
                    localRoot.displayName,
                ),
            )
            store.savePair(added, pair.id)
            selectedRoots.remove(localRoot.localRootId)
            FileSyncCenterActionResult.Completed("Folder sync pair added. Run it to review the first sync.")
        }
    }

    suspend fun removePair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult = lock.withLock {
        store.withExclusiveAccess transaction@ {
            val current = store.loadPair(pairId)
            val pair = current.coordinator.pairs.firstOrNull { it.id == pairId }
                ?: return@transaction FileSyncCenterActionResult.Rejected("The folder sync pair no longer exists.")
            if (pair.accountId != desktopFileCacheAccountId(session)) {
                return@transaction FileSyncCenterActionResult.Rejected(
                    "This folder sync pair belongs to another account.",
                )
            }
            val remote = DesktopFileSyncRemoteTree(
                session = session,
                userId = userId,
                remoteRootPath = pair.remoteRootPath,
                ownedUploadIds = fileSyncOwnedUploads(pair).mapTo(mutableSetOf()) { it.uploadId },
                ownedStageEtags = fileSyncOwnedUploadStageEtags(pair),
                ownedUploadPaths = fileSyncOwnedUploadPaths(pair),
                ownedReplacementBackupEtags = fileSyncOwnedReplacementBackupEtags(pair),
            )
            val cleanupCoordinator = cleanupJvmFileSyncOwnedUploads(
                remote.resumableUploadRemote(), current.coordinator, pairId, fileSyncOwnedUploads(pair),
            )
            removeFileSyncPair(cleanupCoordinator, pairId)
            val overview = store.load()
            store.deletePair(
                pairId = pairId,
                rootId = pair.localRootId,
                deleteRoot = overview.coordinator.pairs.none {
                    it.id != pairId && it.localRootId == pair.localRootId
                },
            )
            FileSyncCenterActionResult.Completed("Folder sync pair removed. No local or server files were deleted.")
        }
    }

    suspend fun runPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        onProgress: (DesktopFileSyncProgressEvent) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        resetExhaustedFailures: Boolean = false,
        onDiagnostic: (DesktopFileSyncRunDiagnosticEvent) -> Unit = {},
    ): FileSyncCenterActionResult = lock.withLock {
        store.withExclusiveAccess {
            runPairLocked(session, userId, pairId, onProgress, onDiagnostic, shouldContinue, resetExhaustedFailures)
        }
    }

    suspend fun resolveConflictAndRun(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        workId: Long,
        choice: FileSyncDecisionChoice,
        onProgress: (DesktopFileSyncProgressEvent) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        onDiagnostic: (DesktopFileSyncRunDiagnosticEvent) -> Unit,
    ): FileSyncCenterActionResult = resolveConflictsAndRun(
        session = session,
        userId = userId,
        pairId = pairId,
        resolutions = listOf(FileSyncConflictResolution(workId, choice)),
        onProgress = onProgress,
        shouldContinue = shouldContinue,
        onDiagnostic = onDiagnostic,
    )

    suspend fun resolveConflictsAndRun(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        resolutions: List<FileSyncConflictResolution>,
        onProgress: (DesktopFileSyncProgressEvent) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        onDiagnostic: (DesktopFileSyncRunDiagnosticEvent) -> Unit,
    ): FileSyncCenterActionResult = lock.withLock {
        store.withExclusiveAccess transaction@ {
            val current = store.loadPair(pairId)
            val pair = current.coordinator.pairs.firstOrNull { it.id == pairId }
                ?: return@transaction FileSyncCenterActionResult.Rejected("The folder sync pair no longer exists.")
            if (pair.accountId != desktopFileCacheAccountId(session)) {
                return@transaction FileSyncCenterActionResult.Rejected(
                    "This folder sync pair belongs to another account.",
                )
            }
            if (resolutions.any { resolution -> pair.workItems.none { it.id == resolution.workId } }) {
                return@transaction FileSyncCenterActionResult.Rejected(
                    "One or more conflicts no longer exist. Scan again.",
                )
            }
            val resolved = runCatching {
                resolveFileSyncDecisions(current.coordinator, pairId, resolutions)
            }.getOrElse { failure ->
                return@transaction FileSyncCenterActionResult.Rejected(
                    safeFailureMessage(failure, "That conflict decision is no longer valid. Scan again."),
                )
            }
            runPairLocked(
                session,
                userId,
                pairId,
                onProgress,
                onDiagnostic,
                shouldContinue,
                resetExhaustedFailures = true,
                expectedResolvedWorkIds = resolutions.mapTo(mutableSetOf(), FileSyncConflictResolution::workId),
                rejectedResolutionBaseline = current.coordinator,
                startingCoordinatorOverride = resolved,
            )
        }
    }

    private fun runPairLocked(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        onProgress: (DesktopFileSyncProgressEvent) -> Unit,
        onDiagnostic: (DesktopFileSyncRunDiagnosticEvent) -> Unit,
        shouldContinue: () -> Boolean,
        resetExhaustedFailures: Boolean,
        expectedResolvedWorkIds: Set<Long> = emptySet(),
        rejectedResolutionBaseline: FileSyncCoordinatorState? = null,
        startingCoordinatorOverride: FileSyncCoordinatorState? = null,
    ): FileSyncCenterActionResult {
        reclaimDesktopFileSyncStages(stagingRoot)
        var persisted = store.loadPair(pairId).let { loaded ->
            startingCoordinatorOverride?.let { loaded.copy(coordinator = it) } ?: loaded
        }
        val initialPair = persisted.coordinator.pairs.firstOrNull { it.id == pairId }
            ?: return FileSyncCenterActionResult.Rejected("The folder sync pair no longer exists.")
        if (initialPair.accountId != desktopFileCacheAccountId(session)) {
            return FileSyncCenterActionResult.Rejected("This folder sync pair belongs to another account.")
        }
        val root = persisted.roots.firstOrNull { it.id == initialPair.localRootId }
            ?: return FileSyncCenterActionResult.Rejected("The local folder record is missing.")
        val local = DesktopFileSyncLocalTree(File(root.absolutePath))
        val remote = DesktopFileSyncRemoteTree(
            session = session,
            userId = userId,
            remoteRootPath = initialPair.remoteRootPath,
            onMutationCommitted = { relativePath ->
                val path = desktopFileSyncRemoteMutationPath(initialPair.remoteRootPath, relativePath)
                runCatching { onRemoteMutationCommitted(session, userId, path) }
            },
            ownedUploadIds = fileSyncOwnedUploads(initialPair).mapTo(mutableSetOf()) { it.uploadId },
            ownedStageEtags = fileSyncOwnedUploadStageEtags(initialPair),
            ownedUploadPaths = fileSyncOwnedUploadPaths(initialPair),
            ownedReplacementBackupEtags = fileSyncOwnedReplacementBackupEtags(initialPair),
        )
        cleanupJvmFileSyncOwnedUploads(
            remote.resumableUploadRemote(shouldContinue),
            persisted.coordinator,
            pairId,
            initialPair.pendingUploadCleanups,
        ) { coordinator ->
            persisted = persisted.copy(coordinator = coordinator)
            store.savePair(persisted, pairId)
        }
        val includes = { path: String, kind: SyncEntryKind -> initialPair.configuration.includesSyncPath(path, kind) }
        val cachedLocalRevisions = initialPair.baselines.mapNotNull { baseline ->
            baseline.localRevision?.let { revision -> baseline.relativePath to revision }
        }.toMap()
        val scannedLocalEntries = try {
            local.scan(cachedLocalRevisions, includes, shouldContinue).map(DesktopLocalSyncDocument::entry)
        } catch (_: DesktopFileSyncScanStoppedException) {
            return FileSyncCenterActionResult.Stopped("The folder scan stopped before making changes.")
        } catch (failure: DesktopFileSyncScanLimitException) {
            onDiagnostic(failure.toDesktopFileSyncRunDiagnosticEvent(pairId, DesktopFileSyncScanStage.Local))
            throw failure
        }
        val scannedRemoteEntries = remote.scan(includes).map(DesktopRemoteSyncDocument::entry)
        val cachedMismatchResults = currentFileSyncContentVerificationResults(
            scannedLocalEntries,
            scannedRemoteEntries,
            initialPair.knownFileSyncContentMismatchResults(),
        )
        val candidates = fileSyncContentVerificationCandidates(
            scannedLocalEntries,
            scannedRemoteEntries,
            initialPair.baselines,
            initialPair.knownFileSyncContentMismatches(),
        )
        val currentProgress = currentFileSyncContentVerificationProgress(
            candidates,
            initialPair.contentVerificationProgress,
        )
        val progressByPath = currentProgress.associateByTo(mutableMapOf()) { it.candidate.relativePath }
        val localContentHashes = scannedLocalEntries.associate { entry ->
            entry.relativePath to entry.contentHash
        }
        val completedSlices = try {
            planFileSyncContentVerificationSlices(candidates, currentProgress).map { slice ->
                verifyDesktopFileSyncContentSlice(
                    slice,
                    local,
                    remote,
                    requireNotNull(localContentHashes[slice.candidate.relativePath]) {
                        "The local file has no complete content hash."
                    },
                    shouldContinue,
                ).also { outcome ->
                    progressByPath.remove(slice.candidate.relativePath)
                    outcome.progress?.let { progress ->
                        progressByPath[progress.candidate.relativePath] = progress
                    }
                }
            }
        } catch (_: DesktopFileSyncScanStoppedException) {
            return FileSyncCenterActionResult.Stopped("The folder scan stopped before making changes.")
        }
        val verificationResults = cachedMismatchResults + completedSlices.mapNotNull { it.result }
        val verifiedPaths = verificationResults.mapTo(mutableSetOf()) { it.candidate.relativePath }
        val pendingCandidates = candidates.filterNot { it.relativePath in verifiedPaths }
        val verifiedMismatches = verificationResults.filter { it.matchingContentHash == null }
            .map(FileSyncContentVerificationResult::candidate)
        val verifiedMismatchHashes = verificationResults
            .filter { it.matchingContentHash == null }
            .associate { it.candidate.relativePath to it.localContentHash }
        val contentIdentity = markPendingFileSyncContentVerification(
            applyFileSyncContentVerificationResults(
                scannedLocalEntries,
                scannedRemoteEntries,
                verificationResults,
            ),
            pendingCandidates,
        )
        val localEntries = contentIdentity.localEntries
        val remoteEntries = contentIdentity.remoteEntries
        val snapshotDiagnostics = desktopFileSyncSnapshotDiagnostics(localEntries, remoteEntries)
        persisted = persisted.copy(
            coordinator = scanFileSyncPair(
                persisted.coordinator,
                pairId,
                localEntries,
                remoteEntries,
                System.currentTimeMillis(),
                maximumWorkItems = MAX_FILE_SYNC_WORK_ITEMS,
                verifiedContentMismatches = verifiedMismatches,
                verifiedContentMismatchHashes = verifiedMismatchHashes,
                contentVerificationProgress = progressByPath.values.sortedBy { it.candidate.relativePath },
            ),
        )
        val scannedPair = persisted.coordinator.pairs.single()
        if (!scannedPair.retainsResolvedFileSyncDecisions(expectedResolvedWorkIds)) {
            val baseline = requireNotNull(rejectedResolutionBaseline) {
                "A rejected conflict batch is missing its pre-decision state."
            }
            persisted = persisted.copy(
                coordinator = scanFileSyncPair(
                    baseline,
                    pairId,
                    localEntries,
                    remoteEntries,
                    System.currentTimeMillis(),
                    maximumWorkItems = MAX_FILE_SYNC_WORK_ITEMS,
                    verifiedContentMismatches = verifiedMismatches,
                    verifiedContentMismatchHashes = verifiedMismatchHashes,
                    contentVerificationProgress = progressByPath.values.sortedBy { it.candidate.relativePath },
                ),
            )
            store.savePair(persisted, pairId)
            return FileSyncCenterActionResult.Rejected(
                "The conflict changed while you reviewed it. Review the latest device and " +
                    "Nextcloud details before choosing again.",
            )
        }
        val plannedPair = scannedPair.prepareForDesktopExecution(
            resetExhaustedFailures,
            nowEpochMillis = System.currentTimeMillis(),
        )
        persisted = persisted.copy(coordinator = FileSyncCoordinatorState(listOf(plannedPair)))
        store.savePair(persisted, pairId)

        val pairLabel = syncPairLabel(root.displayName, initialPair.remoteRootPath)
        val executableWork = plannedPair.workItems.filter { it.state == FileSyncExecutionState.Ready }
        val totalOperations = executableWork.size
        val conflicts = plannedPair.workItems.count { it.state == FileSyncExecutionState.AwaitingDecision }
        var failures = plannedPair.workItems.count { it.state == FileSyncExecutionState.Failed }
        val baselinePaths = plannedPair.baselines.mapTo(mutableSetOf(), FileSyncBaseline::relativePath)
        var completed = 0
        for (plannedWork in executableWork) {
            if (!shouldContinue()) break
            var execution = persisted.scopedToDesktopWork(plannedPair, plannedWork)
            val claim = claimNextFileSyncOperation(execution.coordinator, pairId, System.currentTimeMillis())
            execution = execution.copy(coordinator = claim.state)
            val command = requireNotNull(claim.command)
            val runningWork = execution.coordinator.pairs.single().workItems.single()
            store.saveExecutionTransition(execution, pairId, command.workId, runningWork)
            val sizeBytes = runningWork.observedLocal?.size ?: runningWork.observedRemote?.size
            onProgress(
                DesktopFileSyncProgressEvent(
                    pairId = pairId,
                    workId = command.workId,
                    relativePath = runningWork.relativePath,
                    pairLabel = pairLabel,
                    operation = command.operation,
                    completedOperations = completed,
                    totalOperations = totalOperations,
                    sizeBytes = sizeBytes,
                    attemptCount = runningWork.attemptCount,
                    snapshot = snapshotDiagnostics,
                    stage = DesktopFileSyncProgressStage.Started,
                ),
            )
            val checkpoints = DesktopFileSyncCheckpointPersistence(
                execution, store, pairId, command.workId,
            )
            try {
                requireDesktopFileSyncBaselineCapacity(command.operation, baselinePaths)
                val success = execute(
                    command = command,
                    work = runningWork,
                    local = local,
                    remote = remote,
                    persistUploadCheckpoint = checkpoints::persist,
                    retainUploadCleanup = checkpoints::retainCleanup,
                    completeUploadCleanup = checkpoints::completeCleanup,
                    shouldContinue = shouldContinue,
                )
                execution = checkpoints.state
                execution = execution.copy(
                    coordinator = completeFileSyncOperation(
                        execution.coordinator,
                        pairId,
                        command.workId,
                        success,
                    ),
                )
                store.saveExecutionTransition(
                    state = execution,
                    pairId = pairId,
                    workId = command.workId,
                    workItem = null,
                    synchronizedBaselines = success.synchronizedBaselines,
                    removedBaselinePaths = success.removedRelativePaths.toSet(),
                )
                baselinePaths.removeAll(success.removedRelativePaths.toSet())
                baselinePaths.addAll(success.synchronizedBaselines.map(FileSyncBaseline::relativePath))
                completed += 1
                onProgress(
                    DesktopFileSyncProgressEvent(
                        pairId = pairId,
                        workId = command.workId,
                        relativePath = runningWork.relativePath,
                        pairLabel = pairLabel,
                        operation = command.operation,
                        completedOperations = completed,
                        totalOperations = totalOperations,
                        sizeBytes = sizeBytes,
                        attemptCount = runningWork.attemptCount,
                        snapshot = snapshotDiagnostics,
                        stage = DesktopFileSyncProgressStage.Completed,
                    ),
                )
            } catch (cancellation: CancellationException) {
                execution = checkpoints.state.copy(
                    coordinator = releaseCancelledFileSyncOperation(
                        checkpoints.state.coordinator,
                        pairId,
                        command.workId,
                    ),
                )
                val releasedWork = execution.coordinator.pairs.single().workItems.single()
                store.saveExecutionTransition(execution, pairId, command.workId, releasedWork)
                throw cancellation
            } catch (failure: Throwable) {
                val safeMessage = safeFailureMessage(failure, "The sync operation failed.")
                execution = checkpoints.state.copy(
                    coordinator = failFileSyncOperation(
                        checkpoints.state.coordinator,
                        pairId,
                        command.workId,
                        safeMessage,
                    ),
                )
                val failedWork = execution.coordinator.pairs.single().workItems.single()
                store.saveExecutionTransition(execution, pairId, command.workId, failedWork)
                failures += 1
                onProgress(
                    DesktopFileSyncProgressEvent(
                        pairId = pairId,
                        workId = command.workId,
                        relativePath = runningWork.relativePath,
                        pairLabel = pairLabel,
                        operation = command.operation,
                        completedOperations = completed,
                        totalOperations = totalOperations,
                        sizeBytes = sizeBytes,
                        attemptCount = runningWork.attemptCount,
                        snapshot = snapshotDiagnostics,
                        stage = DesktopFileSyncProgressStage.Failed,
                        failureMessage = safeMessage,
                        failureKind = desktopFileSyncFailureKind(failure),
                    ),
                )
            }
        }
        val message = buildString {
            append(completed).append(" sync operation")
            if (completed != 1) append('s')
            append(" completed.")
            if (conflicts > 0) append(' ').append(conflicts).append(" conflicts need review.")
            if (failures > 0) append(' ').append(failures).append(" operations failed.")
        }
        return if (failures > 0) FileSyncCenterActionResult.Rejected(message)
        else FileSyncCenterActionResult.Completed(message)
    }

    private fun syncPairLabel(localDisplayName: String, remoteRootPath: String): String =
        "$localDisplayName to /${remoteRootPath.ifBlank { "Nextcloud" }}"

    private fun execute(
        command: FileSyncExecutionCommand,
        work: FileSyncWorkItem,
        local: DesktopFileSyncLocalTree,
        remote: DesktopFileSyncRemoteTree,
        persistUploadCheckpoint: (FileSyncUploadCheckpoint) -> Unit,
        retainUploadCleanup: (FileSyncPendingUploadCleanup) -> Unit,
        completeUploadCleanup: (String) -> Unit,
        shouldContinue: () -> Boolean,
    ): FileSyncExecutionSuccess {
        require(work.id == command.workId && work.operation == command.operation)
        return when (val operation = command.operation) {
            is FileSyncOperation.Upload -> {
                val source = requireNotNull(work.observedLocal)
                val replacingType = work.observedRemote?.kind?.let { it != source.kind } == true
                var exactLocal: LocalSyncEntry? = null
                var exactRemote: RemoteSyncEntry? = null
                if (source.kind == SyncEntryKind.Directory && replacingType) {
                    remote.replaceWithDirectory(
                        operation.relativePath,
                        requireNotNull(operation.expectedRemoteEtag),
                    )
                } else if (source.kind == SyncEntryKind.Directory) {
                    remote.createDirectory(operation.relativePath, operation.expectedRemoteEtag)
                } else {
                    withStagingFile("upload", source.size) { staged, maximumBytes ->
                        exactLocal = local.stageForUpload(operation.relativePath, staged, maximumBytes)
                        val uploaded = executeDesktopFileSyncUpload(
                            staged, operation.relativePath, requireNotNull(exactLocal),
                            operation.expectedRemoteEtag, work.uploadCheckpoint, replacingType,
                            persistUploadCheckpoint, retainUploadCleanup, completeUploadCleanup,
                            remote, shouldContinue,
                        )
                        // Every direct, chunked, and type-replacement upload byte-compares this
                        // exact generation before the synchronized baseline is recorded.
                        exactRemote = uploaded
                    }
                }
                if (source.kind == SyncEntryKind.File) {
                    FileSyncExecutionSuccess(
                        synchronizedBaselines = listOf(
                            FileSyncBaseline(
                                operation.relativePath,
                                SyncEntryKind.File,
                                requireNotNull(exactLocal).revision,
                                requireNotNull(exactRemote).etag,
                            ),
                        ),
                    )
                } else {
                    synchronizedResult(operation.relativePath, local, remote)
                }
            }
            is FileSyncOperation.Download -> {
                val source = requireNotNull(work.observedRemote)
                val replacingType = work.observedLocal?.kind?.let { it != source.kind } == true
                var exactLocal: LocalSyncEntry? = null
                var exactRemote: RemoteSyncEntry? = null
                if (source.kind == SyncEntryKind.Directory && replacingType) {
                    local.replaceWithDirectory(
                        operation.relativePath,
                        requireNotNull(operation.expectedLocalRevision),
                    )
                } else if (source.kind == SyncEntryKind.Directory) {
                    local.createDirectory(operation.relativePath, operation.expectedLocalRevision)
                } else {
                    val maximumDownloadBytes = source.size
                        ?: maximumSafeDesktopDownloadBytes(local, operation.relativePath)
                    requireDownloadCapacity(local, operation.relativePath, maximumDownloadBytes)
                    withStagingFile("download", maximumDownloadBytes) { staged, stagingMaximumBytes ->
                        exactRemote = remote.stageDownload(
                            operation.relativePath,
                            source.etag,
                            staged,
                            stagingMaximumBytes,
                        ) { declaredBytes ->
                            requireDownloadCapacity(
                                local,
                                operation.relativePath,
                                declaredBytes ?: maximumDownloadBytes,
                            )
                        }
                        exactLocal = if (replacingType) {
                            local.replaceWithFile(
                                operation.relativePath,
                                staged,
                                requireNotNull(operation.expectedLocalRevision),
                            )
                        } else {
                            local.writeFile(operation.relativePath, staged, operation.expectedLocalRevision)
                        }
                    }
                }
                if (source.kind == SyncEntryKind.File) {
                    FileSyncExecutionSuccess(
                        synchronizedBaselines = listOf(
                            FileSyncBaseline(
                                operation.relativePath,
                                SyncEntryKind.File,
                                requireNotNull(exactLocal).revision,
                                requireNotNull(exactRemote).etag,
                            ),
                        ),
                    )
                } else {
                    synchronizedResult(operation.relativePath, local, remote)
                }
            }
            is FileSyncOperation.DeleteLocal -> {
                local.delete(operation.relativePath, operation.expectedLocalRevision)
                require(local.resolve(operation.relativePath) == null && remote.resolve(operation.relativePath) == null)
                FileSyncExecutionSuccess(removedRelativePaths = listOf(operation.relativePath))
            }
            is FileSyncOperation.DeleteRemote -> {
                remote.delete(operation.relativePath, operation.expectedRemoteEtag)
                require(local.resolve(operation.relativePath) == null && remote.resolve(operation.relativePath) == null)
                FileSyncExecutionSuccess(removedRelativePaths = listOf(operation.relativePath))
            }
            is FileSyncOperation.KeepBoth -> executeKeepBoth(operation, work, local, remote)
            is FileSyncOperation.NeedsDecision,
            is FileSyncOperation.Skipped,
            -> error("Non-executable sync work was claimed.")
        }
    }

    private fun executeKeepBoth(
        operation: FileSyncOperation.KeepBoth,
        work: FileSyncWorkItem,
        local: DesktopFileSyncLocalTree,
        remote: DesktopFileSyncRemoteTree,
    ): FileSyncExecutionSuccess {
        val localSource = requireNotNull(work.observedLocal)
        val remoteSource = requireNotNull(work.observedRemote)
        require(localSource.kind == SyncEntryKind.File && remoteSource.kind == SyncEntryKind.File)
        withStagingFile("keep-local", localSource.size) { localBytes, localMaximumBytes ->
            withStagingFile("keep-remote", remoteSource.size) { remoteBytes, remoteMaximumBytes ->
                val currentOriginal = local.resolve(operation.relativePath)
                val preservedLocalPath = if (currentOriginal?.entry?.revision == localSource.revision) {
                    operation.relativePath
                } else {
                    operation.localConflictPath
                }
                local.stageForUpload(preservedLocalPath, localBytes, localMaximumBytes)
                remote.stageDownload(operation.relativePath, remoteSource.etag, remoteBytes, remoteMaximumBytes)
                ensureLocalFile(operation.localConflictPath, localBytes, local)
                ensureRemoteFile(operation.localConflictPath, localBytes, remote)
                ensureLocalFile(operation.remoteConflictPath, remoteBytes, local)
                ensureRemoteFile(operation.remoteConflictPath, remoteBytes, remote)
                replaceLocalOriginalOrVerify(
                    operation.relativePath,
                    remoteBytes,
                    localSource.revision,
                    local,
                )
            }
        }
        return FileSyncExecutionSuccess(
            synchronizedBaselines = listOf(
                verifiedBaseline(operation.relativePath, local, remote),
                verifiedBaseline(operation.localConflictPath, local, remote),
                verifiedBaseline(operation.remoteConflictPath, local, remote),
            ),
        )
    }

    private fun ensureLocalFile(
        path: String,
        expectedBytes: File,
        local: DesktopFileSyncLocalTree,
    ) {
        val current = local.resolve(path)
        if (current == null) {
            local.writeFile(path, expectedBytes, null)
            return
        }
        require(current.entry.kind == SyncEntryKind.File) { "A conflict-copy path is not a file." }
        withStagingFile("verify-local-conflict", current.entry.size) { actualBytes, maximumBytes ->
            local.stageForUpload(path, actualBytes, maximumBytes)
            require(filesMatch(actualBytes, expectedBytes)) {
                "A conflict-copy path contains different local content."
            }
        }
    }

    private fun ensureRemoteFile(
        path: String,
        expectedBytes: File,
        remote: DesktopFileSyncRemoteTree,
    ) {
        val current = remote.resolve(path)
        if (current == null) {
            remote.writeFile(path, expectedBytes, null)
            return
        }
        require(current.entry.kind == SyncEntryKind.File) { "A conflict-copy path is not a file." }
        withStagingFile("verify-remote-conflict", current.entry.size) { actualBytes, maximumBytes ->
            remote.stageDownload(path, current.entry.etag, actualBytes, maximumBytes)
            require(filesMatch(actualBytes, expectedBytes)) {
                "A conflict-copy path contains different server content."
            }
        }
    }

    private fun replaceLocalOriginalOrVerify(
        path: String,
        expectedBytes: File,
        originalRevision: String,
        local: DesktopFileSyncLocalTree,
    ) {
        val current = requireNotNull(local.resolve(path)) { "The original local file disappeared." }
        require(current.entry.kind == SyncEntryKind.File) { "The original local path is not a file." }
        if (current.entry.revision == originalRevision) {
            local.writeFile(path, expectedBytes, originalRevision)
            return
        }
        withStagingFile("verify-local-original", current.entry.size) { actualBytes, maximumBytes ->
            local.stageForUpload(path, actualBytes, maximumBytes)
            require(filesMatch(actualBytes, expectedBytes)) {
                "The original local file changed while conflict copies were being published."
            }
        }
    }

    private fun filesMatch(first: File, second: File): Boolean =
        first.length() == second.length() && Files.mismatch(first.toPath(), second.toPath()) == -1L

    private fun synchronizedResult(
        path: String,
        local: DesktopFileSyncLocalTree,
        remote: DesktopFileSyncRemoteTree,
    ) = FileSyncExecutionSuccess(synchronizedBaselines = listOf(verifiedBaseline(path, local, remote)))

    private fun verifiedBaseline(
        path: String,
        local: DesktopFileSyncLocalTree,
        remote: DesktopFileSyncRemoteTree,
    ): FileSyncBaseline {
        val localEntry = requireNotNull(local.resolve(path)) { "The local result could not be verified." }.entry
        val remoteEntry = requireNotNull(remote.resolve(path)) { "The server result could not be verified." }.entry
        require(localEntry.kind == remoteEntry.kind) { "The synchronized item types do not match." }
        return FileSyncBaseline(path, localEntry.kind, localEntry.revision, remoteEntry.etag, localEntry.contentHash)
    }

    private inline fun <T> withStagingFile(
        prefix: String,
        expectedBytes: Long?,
        block: (File, maximumBytes: Long) -> T,
    ): T = withDesktopFileSyncStagingFile(
        stagingRoot, stagingReservations, minimumFreeSpaceBytes, prefix, expectedBytes, block,
    )

    private fun maximumSafeDesktopDownloadBytes(
        local: DesktopFileSyncLocalTree,
        relativePath: String,
    ): Long = maximumSafeDesktopFileSyncDownloadBytes(
        stagingRoot, minimumFreeSpaceBytes, local, relativePath,
    )

    private fun requireDownloadCapacity(
        local: DesktopFileSyncLocalTree,
        relativePath: String,
        downloadBytes: Long,
    ) = requireDesktopFileSyncDownloadCapacity(
        stagingRoot, minimumFreeSpaceBytes, local, relativePath, downloadBytes,
    )

    private fun normalizeRemoteRoot(path: String): String {
        val normalized = path.trim().trim('/')
        if (normalized.isEmpty()) return ""
        require(normalized.length <= MAX_FILE_SYNC_PATH_LENGTH)
        normalized.split('/').forEach { segment ->
            require(segment.isNotBlank() && segment !in setOf(".", "..") && segment.none(Char::isISOControl))
        }
        return normalized
    }

    private fun safeFailureMessage(failure: Throwable, fallback: String): String =
        failure.message?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")
            ?.trim()?.take(MAX_FILE_SYNC_FAILURE_LENGTH)?.takeIf(String::isNotBlank) ?: fallback

}
