package dev.obiente.nextcloudnative

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import dev.obiente.nextcloudnative.app.FileSyncCenterSnapshot
import dev.obiente.nextcloudnative.app.FileSyncCenterSupport
import dev.obiente.nextcloudnative.app.FileSyncContentVerificationResult
import dev.obiente.nextcloudnative.app.FileSyncConflictResolution
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncExecutionCommand
import dev.obiente.nextcloudnative.app.FileSyncExecutionSuccess
import dev.obiente.nextcloudnative.app.FileSyncExecutionState
import dev.obiente.nextcloudnative.app.FileSyncLocalRoot
import dev.obiente.nextcloudnative.app.FileSyncNetworkState
import dev.obiente.nextcloudnative.app.FileSyncPairRunState
import dev.obiente.nextcloudnative.app.FileSyncRejectionScope
import dev.obiente.nextcloudnative.app.FileSyncDecisionChoice
import dev.obiente.nextcloudnative.app.FileSyncDirection
import dev.obiente.nextcloudnative.app.FileSyncOperation
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.FileSyncUploadCheckpoint
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.MediaBackupLedgerStore
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.addFileSyncPair
import dev.obiente.nextcloudnative.app.applyFileSyncContentVerificationResults
import dev.obiente.nextcloudnative.app.claimNextFileSyncOperation
import dev.obiente.nextcloudnative.app.completeFileSyncOperation
import dev.obiente.nextcloudnative.app.cleanupJvmFileSyncOwnedUploads
import dev.obiente.nextcloudnative.app.currentFileSyncContentVerificationResults
import dev.obiente.nextcloudnative.app.failFileSyncOperation
import dev.obiente.nextcloudnative.app.fileSyncContentVerificationCandidates
import dev.obiente.nextcloudnative.app.fileSyncOwnedUploads
import dev.obiente.nextcloudnative.app.removeFileSyncPair
import dev.obiente.nextcloudnative.app.resolveFileSyncDecisions
import dev.obiente.nextcloudnative.app.retryFileSyncOperation
import dev.obiente.nextcloudnative.app.scanFileSyncPair
import dev.obiente.nextcloudnative.app.stagedFileTransferLimit
import dev.obiente.nextcloudnative.app.toCenterSummary
import dev.obiente.nextcloudnative.app.includesSyncPath
import dev.obiente.nextcloudnative.app.liveFileSyncNetworkState
import dev.obiente.nextcloudnative.app.knownFileSyncContentMismatchResults
import dev.obiente.nextcloudnative.app.markPendingFileSyncContentVerification
import dev.obiente.nextcloudnative.app.retainsResolvedFileSyncDecisions
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun retainNewestAndroidFileSyncLocalContentHashes(
    localEntries: List<LocalSyncEntry>,
    scanContentHashes: Map<String, String>,
    verifiedPaths: Set<String>,
): List<LocalSyncEntry> = localEntries.map { entry ->
    scanContentHashes[entry.relativePath]
        ?.takeIf { entry.relativePath !in verifiedPaths }
        ?.let { hash -> entry.copy(contentHash = hash) }
        ?: entry
}

/**
 * Foreground execution engine for SAF-backed sync pairs.
 *
 * State is persisted before each transfer starts and after every verified result. A process death
 * therefore recovers an interrupted operation as ready without advancing its baseline.
 */
internal class AndroidFileSyncEngine(context: Context) {
    private val appContext = context.applicationContext
    private val store = AndroidFileSyncStore(appContext)
    private val webDav = NextcloudDocumentWebDav(
        client = androidFileSyncHttpClient(appContext),
        cloudMutationsAllowed = appContext.cloudMutationGate(),
    )
    private val scheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidFileSyncScheduler(appContext)
    }
    private val reconciliationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduledMediaReconciliations = ConcurrentHashMap.newKeySet<String>()
    private val scheduledPairScheduling = DeferredFileSyncPairSchedulingRegistry()
    private val stagingRoot = File(appContext.cacheDir, "file-sync-staging")
    private val capabilities = AndroidFileSyncCapabilityLifecycle(appContext)
    init { reconciliationScope.launch { reconcileFileSyncCapabilities(ENGINE_LOCK, store::load, capabilities) } }
    suspend fun loadCenter(
        session: NextcloudSession,
        userId: String,
    ): FileSyncCenterSnapshot {
        val accountId = NextcloudDocumentIds.accountKey(session)
        val schedulingToken = ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.capture(accountId)
        val persisted = loadFileSyncPresentationSnapshot(
            lock = ENGINE_LOCK,
            load = store::load,
            scheduleWhenIdle = { snapshot ->
                schedulingToken?.let { token ->
                    schedulePersistedPairs(snapshot, token, userId)
                }
            },
            scheduleAfterIdle = {
                schedulingToken?.let { token ->
                    schedulePersistedPairsAfterIdle(token, userId)
                }
            },
        )
        return FileSyncCenterSnapshot(
            support = FileSyncCenterSupport.Available,
            pairs = persisted.coordinator.pairs
                .filter { it.accountId == accountId }
                .map { pair ->
                    pair.toCenterSummary(
                        persisted.localDisplayNames[pair.id] ?: "Selected folder",
                        scheduleDescription = pair.configuration.scheduleDescription(),
                        runState = FileSyncPairRunState.Active,
                        networkState = currentNetworkState(pair.configuration),
                    )
                },
        )
    }

    private fun currentNetworkState(configuration: FileSyncConfiguration): FileSyncNetworkState {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return FileSyncNetworkState.Unknown
        val activeNetwork = connectivity.activeNetwork
            ?: return FileSyncNetworkState.WaitingForNetwork
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork)
            ?: return FileSyncNetworkState.Unknown
        val networkAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return liveFileSyncNetworkState(networkAvailable, unmetered, configuration.networkPolicy)
    }

    /**
     * Checks WorkManager's authoritative state before the transfer ledger recovers interrupted
     * uploads. A failed query is treated conservatively as active work so a running worker is
     * never rewritten to pending merely because WorkManager could not be inspected.
     */
    suspend fun reconcileMediaTransfersForDisplay(
        accountId: String,
        mediaStore: MediaBackupLedgerStore,
    ) {
        var runningSourceIds: Set<String>? = null
        val reconciled = try {
            runWhenFileSyncIdle(ENGINE_LOCK) {
                runningSourceIds = runningMediaSourceIds(accountId)
                mediaStore.reconcileInterruptedTransfers(
                    accountId = accountId,
                    activeSourceIds = requireNotNull(runningSourceIds),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
        if (!reconciled || !runningSourceIds.isNullOrEmpty()) {
            scheduleMediaTransferReconciliation(accountId)
        }
    }

    private suspend fun runningMediaSourceIds(accountId: String): Set<String> =
        scheduler.runningPairIds(
            store.load().coordinator.pairs
                .asSequence()
                .filter { pair -> pair.accountId == accountId }
                .map(FileSyncPair::id)
                .toList(),
        )

    private fun scheduleMediaTransferReconciliation(accountId: String) {
        if (!scheduledMediaReconciliations.add(accountId)) return
        reconciliationScope.launch {
            try {
                runFileSyncActionWhenSourceWorkIdle(
                    lock = ENGINE_LOCK,
                    runningSourceIds = { runningMediaSourceIds(accountId) },
                    awaitSourcesNotRunning = scheduler::awaitPairsNotRunning,
                ) {
                    val mediaStore = createAndroidMediaBackupLedgerStore(
                        context = appContext,
                        recoverInterruptedTransfers = false,
                    )
                    try {
                        mediaStore.reconcileInterruptedTransfers(
                            accountId = accountId,
                            activeSourceIds = emptySet(),
                        )
                    } finally {
                        mediaStore.close()
                    }
                    MediaBackupStatusUpdates.changes.tryEmit(accountId)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Reconciliation is a display repair. The next load or status update can retry it.
            } finally {
                scheduledMediaReconciliations.remove(accountId)
            }
        }
    }

    private fun schedulePersistedPairs(
        snapshot: AndroidFileSyncPersistedState,
        token: AndroidFileSyncSessionSchedulingToken,
        userId: String,
    ) {
        ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.runIfCurrent(token) {
            snapshot.coordinator.pairs
                .filter { it.accountId == token.accountId }
                .forEach { pair ->
                    scheduler.schedule(pair.id, token.accountId, userId, pair.configuration)
                }
        }
    }

    private fun schedulePersistedPairsAfterIdle(
        token: AndroidFileSyncSessionSchedulingToken,
        userId: String,
    ) {
        val scheduling = DeferredFileSyncPairScheduling(token, userId)
        if (!scheduledPairScheduling.acquire(scheduling)) return
        deferFileSyncSnapshotActionUntilIdle(
            lock = ENGINE_LOCK,
            scope = reconciliationScope,
            load = store::load,
            onFinished = { scheduledPairScheduling.release(scheduling) },
        ) { current ->
            schedulePersistedPairs(current, token, userId)
        }
    }

    suspend fun addPair(
        session: NextcloudSession,
        userId: String,
        localRoot: FileSyncLocalRoot,
        remoteRootPath: String,
        configuration: FileSyncConfiguration,
    ): FileSyncCenterActionResult = ENGINE_LOCK.withLock {
        if (!supportsAndroidFileSyncDirection(localRoot.localRootId, configuration.direction)) {
            return@withLock FileSyncCenterActionResult.Rejected(
                "Detected media folders support upload-only sync.",
            )
        }
        // Constructing the adapter verifies that its persisted SAF grant or detected media root is usable.
        createAndroidFileSyncLocalTree(appContext, localRoot.localRootId)
        val normalizedRemote = normalizeRemoteRoot(remoteRootPath)
        val accountId = NextcloudDocumentIds.accountKey(session)
        val current = store.load()
        if (hasDuplicateAndroidFileSyncRoot(current.coordinator.pairs, accountId, localRoot.localRootId, normalizedRemote)) {
            return@withLock FileSyncCenterActionResult.Rejected(
                "That local folder already belongs to a folder sync pair.",
            )
        }
        val pair = FileSyncPair(
            id = UUID.randomUUID().toString(),
            accountId = accountId,
            localRootId = localRoot.localRootId,
            remoteRootPath = normalizedRemote,
            configuration = configuration,
        )
        val ownsSafGrant = localRoot.localRootId.startsWith("content://")
        if (ownsSafGrant) capabilities.bindReady(localRoot.localRootId, pair.id)
        try {
            store.save(current.copy(
                coordinator = addFileSyncPair(current.coordinator, pair),
                localDisplayNames = current.localDisplayNames + (pair.id to localRoot.displayName),
            ))
        } catch (failure: Exception) {
            if (ownsSafGrant) recoverFailedFileSyncPairSave(pair.id, store::load, capabilities::abandonUncommittedPair)
            throw failure
        }
        scheduler.schedule(pair.id, accountId, userId, pair.configuration)
        FileSyncCenterActionResult.Completed("Folder sync pair added. Run it to review the first sync.")
    }

    private fun FileSyncConfiguration.scheduleDescription(): String {
        val network = when (networkPolicy) {
            dev.obiente.nextcloudnative.app.FileSyncNetworkPolicy.AnyConnection -> "online"
            dev.obiente.nextcloudnative.app.FileSyncNetworkPolicy.Unmetered -> "on unmetered networks"
        }
        val power = when (powerPolicy) {
            dev.obiente.nextcloudnative.app.FileSyncPowerPolicy.AnyPower -> null
            dev.obiente.nextcloudnative.app.FileSyncPowerPolicy.BatteryNotLow -> "when battery is not low"
            dev.obiente.nextcloudnative.app.FileSyncPowerPolicy.Charging -> "while charging"
        }
        return buildString {
            append("Automatic checks about every 15 minutes ").append(network)
            power?.let { append(" and ").append(it) }
        }
    }

    suspend fun removePair(session: NextcloudSession, userId: String, pairId: String): FileSyncCenterActionResult =
        ENGINE_LOCK.withLock {
            val current = store.load()
            val pair = current.coordinator.pairs.firstOrNull { it.id == pairId }
                ?: return@withLock FileSyncCenterActionResult.Rejected(
                    "The folder sync pair no longer exists.",
                )
            if (pair.accountId != NextcloudDocumentIds.accountKey(session)) {
                return@withLock FileSyncCenterActionResult.Rejected(
                    "This folder sync pair belongs to another account.",
                )
            }
            capabilities.reconcile(current)
            var cleanedCoordinator: FileSyncCoordinatorState? = null
            var remoteCleanupRejected = false
            val removed = removeConfiguredFileSyncPair(
                reconcileLocalDownloads = {
                    reconcileSafDownloadsBeforePairRemoval(appContext, pair.localRootId)
                },
                cleanRemoteUploads = {
                    val cleanupResult = cleanupJvmFileSyncOwnedUploads(
                        androidFileSyncOwnedRemoteTree(session, userId, pair, webDav, context = appContext),
                        current.coordinator, pairId, fileSyncOwnedUploads(pair),
                    )
                    remoteCleanupRejected = cleanupResult.unresolvedUploads.isNotEmpty()
                    if (!remoteCleanupRejected) cleanedCoordinator = cleanupResult.state
                    !remoteCleanupRejected
                },
                cleanLedger = {
                    val mediaStore = createAndroidMediaBackupLedgerStore(
                        context = appContext,
                        recoverInterruptedTransfers = false,
                    )
                    try {
                        mediaStore.deleteUnfinishedSource(
                            accountId = pair.accountId,
                            sourceId = pair.id,
                            legacyLocalKeys = (pair.baselines.asSequence().map(FileSyncBaseline::relativePath) +
                                pair.workItems.asSequence().map { work -> work.relativePath })
                                .distinct()
                                .map { relativePath ->
                                    legacyMediaBackupLocalKey(pair.localRootId, relativePath)
                                }
                                .toList(),
                        )
                    } finally {
                        mediaStore.close()
                    }
                },
                persistRemoval = {
                    capabilities.preparePairCleanup(pairId)
                    val remaining = removeFileSyncPair(requireNotNull(cleanedCoordinator), pairId)
                    capabilities.persistPairRemoval(store::load) {
                        store.save(current.copy(
                            coordinator = remaining,
                            localDisplayNames = current.localDisplayNames - pairId,
                        ))
                    }
                },
                cancelSchedule = { scheduler.cancel(pairId) },
                releaseLocalGrant = { capabilities.finishPairCleanup(pairId) },
            )
            if (!removed) {
                return@withLock FileSyncCenterActionResult.Rejected(if (remoteCleanupRejected) {
                    "A previous upload still needs safe recovery. Run this folder sync before removing it."
                } else "A local download still needs safe recovery. Run this folder sync before removing it.")
            }
            FileSyncCenterActionResult.Completed("Folder sync pair removed. No local or server files were deleted.")
        }
    suspend fun runPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult = withAndroidFileSyncRunCancellation { cancellation ->
        ENGINE_LOCK.withLock {
            runPairLocked(session, userId, pairId, transferCancellation = cancellation)
        }
    }

    private suspend fun runPairLocked(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        expectedResolvedWorkIds: Set<Long> = emptySet(),
        rejectedResolutionBaseline: FileSyncCoordinatorState? = null,
        startingCoordinatorOverride: FileSyncCoordinatorState? = null,
        transferCancellation: DocumentRequestCancellation,
    ): FileSyncCenterActionResult {
        var persisted = store.load().let { loaded ->
            startingCoordinatorOverride?.let { loaded.copy(coordinator = it) } ?: loaded
        }
        val initialPair = persisted.coordinator.pairs.firstOrNull { it.id == pairId }
            ?: return FileSyncCenterActionResult.Rejected(
                "The folder sync pair no longer exists.",
                FileSyncRejectionScope.Preflight,
            )
        if (initialPair.accountId != NextcloudDocumentIds.accountKey(session)) {
            return FileSyncCenterActionResult.Rejected(
                "This folder sync pair belongs to another account.",
                FileSyncRejectionScope.Preflight,
            )
        }
        if (!supportsAndroidFileSyncDirection(initialPair.localRootId, initialPair.configuration.direction)) {
            return FileSyncCenterActionResult.Rejected(
                "This detected media-folder pair is not upload-only. Remove it and add it again.",
                FileSyncRejectionScope.Preflight,
            )
        }
        return withAndroidMediaBackupLedger(appContext, initialPair) { mediaLedger ->
        val remote = androidFileSyncOwnedRemoteTree(
            session, userId, initialPair, webDav,
            transferCancellation = transferCancellation, context = appContext,
        )
        val cleanupResult = cleanupJvmFileSyncOwnedUploads(
            remote, persisted.coordinator, pairId, initialPair.pendingUploadCleanups,
        ) { coordinator ->
            persisted = persisted.copy(coordinator = coordinator)
            store.save(persisted)
        }
        if (cleanupResult.unresolvedUploads.isNotEmpty()) {
            return@withAndroidMediaBackupLedger FileSyncCenterActionResult.Rejected(
                "A previous upload still needs safe recovery. No new file changes were started.",
                FileSyncRejectionScope.Preflight,
            )
        }
        val configuration = initialPair.configuration
        val includes: (String, SyncEntryKind) -> Boolean = { relativePath, kind ->
            configuration.includesSyncPath(relativePath, kind)
        }
        val remoteEntries = remote.scan(includes).map(AndroidRemoteSyncDocument::entry)
        val local = createAndroidFileSyncLocalTree(appContext, initialPair.localRootId)
        val contentReadBudget = AndroidFileSyncContentReadBudget()
        val scannedLocalDocuments = local.scan(includes, remote::shouldContinueTransfer)
        val strengthenedLocalDocuments = strengthenAndroidFileSyncReplacementEntries(
            local = local,
            documents = scannedLocalDocuments,
            remoteEntries = remoteEntries,
            baselines = initialPair.baselines,
            configuration = configuration,
            contentReadBudget = contentReadBudget,
            shouldContinue = remote::shouldContinueTransfer,
        )
        val scanContentHashes = strengthenedLocalDocuments.mapNotNull { document ->
            document.entry.contentHash?.let { hash -> document.entry.relativePath to hash }
        }.toMap()
        val scannedLocalEntries = strengthenedLocalDocuments.map(AndroidLocalSyncDocument::entry)
        val localEntries = verifyAndroidRemoteDeletionContent(
            localEntries = scannedLocalEntries,
            remoteEntries = remoteEntries,
            baselines = initialPair.baselines,
            direction = configuration.direction,
            local = local,
            budget = contentReadBudget,
        )
        val currentCachedMismatchResults = currentFileSyncContentVerificationResults(
            localEntries,
            remoteEntries,
            initialPair.knownFileSyncContentMismatchResults(),
        )
        val cachedMismatchResults = currentCachedMismatchResults
        val candidates = fileSyncContentVerificationCandidates(
            localEntries,
            remoteEntries,
            initialPair.baselines,
            cachedMismatchResults.map(FileSyncContentVerificationResult::candidate),
            requireContentBackedBaseline = true,
        )
        // Android SAF revisions are metadata hints, not durable content generations. Verify one
        // complete candidate per background scan instead of reusing slices across weak revisions.
        val completedGeneration = candidates.firstOrNull()?.let { candidate ->
            verifyAndroidFileSyncGeneration(
                candidate = candidate,
                readLocal = { expectedBytes, maximumBytes ->
                    local.contentHashRead(
                        path = candidate.relativePath,
                        expectedLocalRevision = candidate.localRevision,
                        expectedBytes = expectedBytes,
                        maximumBytes = maximumBytes,
                    )
                },
                verifyRemote = { expectedHash, expectedBytes, maximumBytes ->
                    remote.verifyContentHash(
                        relativePath = candidate.relativePath,
                        expectedRemoteEtag = candidate.remoteEtag,
                        expectedContentHash = expectedHash,
                        expectedBytes = expectedBytes,
                        maximumBytes = maximumBytes,
                    )
                },
            )
        }
        val verificationResults = cachedMismatchResults + listOfNotNull(completedGeneration)
        val verifiedPaths = verificationResults.mapTo(mutableSetOf()) { it.candidate.relativePath }
        val pendingCandidates = candidates.filterNot { it.relativePath in verifiedPaths }
        val verifiedMismatches = verificationResults.filter { it.matchingContentHash == null }
            .map(FileSyncContentVerificationResult::candidate)
        val verifiedMismatchHashes = verificationResults
            .filter { it.matchingContentHash == null }
            .associate { it.candidate.relativePath to it.localContentHash }
        val contentIdentity = markPendingFileSyncContentVerification(
            applyFileSyncContentVerificationResults(localEntries, remoteEntries, verificationResults),
            pendingCandidates,
        )
        val reconciledLocalEntries = retainNewestAndroidFileSyncLocalContentHashes(
            localEntries = contentIdentity.localEntries,
            scanContentHashes = scanContentHashes,
            verifiedPaths = verifiedPaths,
        )
        val reconciledRemoteEntries = contentIdentity.remoteEntries
        persisted = persisted.copy(
            coordinator = scanFileSyncPair(
                persisted.coordinator,
                pairId,
                reconciledLocalEntries,
                reconciledRemoteEntries,
                System.currentTimeMillis(),
                maximumWorkItems = ANDROID_FILE_SYNC_MAX_WORK_ITEMS,
                reservedNonExecutableWorkItems = ANDROID_FILE_SYNC_NON_EXECUTABLE_RESERVE,
                verifiedContentMismatches = verifiedMismatches,
                verifiedContentMismatchHashes = verifiedMismatchHashes,
                contentVerificationProgress = emptyList(),
            ),
        )
        val scannedPair = persisted.coordinator.pairs.first { it.id == pairId }
        if (!scannedPair.retainsResolvedFileSyncDecisions(expectedResolvedWorkIds)) {
            val baseline = requireNotNull(rejectedResolutionBaseline) {
                "A rejected conflict batch is missing its pre-decision state."
            }
            persisted = persisted.copy(
                coordinator = scanFileSyncPair(
                    baseline,
                    pairId,
                    reconciledLocalEntries,
                    reconciledRemoteEntries,
                    System.currentTimeMillis(),
                    maximumWorkItems = ANDROID_FILE_SYNC_MAX_WORK_ITEMS,
                    reservedNonExecutableWorkItems = ANDROID_FILE_SYNC_NON_EXECUTABLE_RESERVE,
                    verifiedContentMismatches = verifiedMismatches,
                    verifiedContentMismatchHashes = verifiedMismatchHashes,
                    contentVerificationProgress = emptyList(),
                ),
            )
            store.save(persisted)
            return@withAndroidMediaBackupLedger FileSyncCenterActionResult.Rejected(
                "The conflict changed while you reviewed it. Review the latest device and " +
                    "Nextcloud details before choosing again.",
            )
        }
        persisted.coordinator.pairs.first { it.id == pairId }.workItems
            .filter {
                it.state == FileSyncExecutionState.Failed &&
                    it.attemptCount < 20
            }
            .forEach { work ->
                persisted = persisted.copy(
                    coordinator = retryFileSyncOperation(
                        persisted.coordinator,
                        pairId,
                        work.id,
                    ),
                )
        }
        store.save(persisted)
        mediaLedger?.recordVerifiedBaselines(
            baselines = persisted.coordinator.pairs.first { it.id == pairId }.baselines,
            localEntries = reconciledLocalEntries,
            remoteEntries = reconciledRemoteEntries,
            nowEpochMillis = System.currentTimeMillis(),
        )
        mediaLedger?.recordPlanned(
            persisted.coordinator.pairs.first { it.id == pairId }.workItems,
            System.currentTimeMillis(),
        )

        var completed = 0
        while (true) {
            val claim = claimNextFileSyncOperation(
                persisted.coordinator,
                pairId,
                System.currentTimeMillis(),
            )
            persisted = persisted.copy(coordinator = claim.state)
            store.save(persisted)
            val command = claim.command ?: break
            val claimedWork = persisted.coordinator.pairs
                .first { it.id == pairId }
                .workItems
                .first { it.id == command.workId }
            mediaLedger?.recordPlanned(listOf(claimedWork), System.currentTimeMillis())
            val checkpoints = AndroidFileSyncCheckpointPersistence(
                persisted, store, pairId, command.workId,
            )
            val execution = runCatching {
                execute(command, persisted.coordinator, local, remote, contentReadBudget, checkpoints::persist)
            }
            persisted = checkpoints.state
            val failure = execution.exceptionOrNull()
            persisted = persistAndRethrowAndroidFileSyncCancellation(
                persisted, store, pairId, command.workId, failure,
            )
            if (failure == null) {
                val success = execution.getOrThrow()
                persisted = persisted.copy(
                    coordinator = completeFileSyncOperation(
                        persisted.coordinator,
                        pairId,
                        command.workId,
                        success,
                    ),
                )
                completed += 1
                store.save(persisted)
                if (claimedWork.operation is FileSyncOperation.Upload) {
                    val baseline = success.synchronizedBaselines.single {
                        it.relativePath == claimedWork.relativePath
                    }
                    val verifiedLocal = requireNotNull(local.resolve(claimedWork.relativePath)).entry
                    mediaLedger?.recordSucceeded(
                        work = claimedWork,
                        local = verifiedLocal,
                        baseline = baseline,
                        nowEpochMillis = System.currentTimeMillis(),
                    )
                }
            } else {
                persisted = persisted.copy(
                    coordinator = failFileSyncOperation(
                        persisted.coordinator,
                        pairId,
                        command.workId,
                        safeFailureMessage(failure, "The sync operation failed."),
                    ),
                )
                store.save(persisted)
                val failedWork = persisted.coordinator.pairs
                    .first { it.id == pairId }
                    .workItems
                    .first { it.id == command.workId }
                mediaLedger?.recordPlanned(listOf(failedWork), System.currentTimeMillis())
            }
        }
        val pair = persisted.coordinator.pairs.first { it.id == pairId }
        val conflicts = pair.workItems.count { it.state == FileSyncExecutionState.AwaitingDecision }
        val failures = pair.workItems.count { it.state == FileSyncExecutionState.Failed }
        val message = buildString {
            append("$completed sync operation")
            if (completed != 1) append('s')
            append(" completed.")
            if (conflicts > 0) append(" $conflicts conflict${if (conflicts == 1) "" else "s"} need review.")
            if (failures > 0) append(" $failures operation${if (failures == 1) "" else "s"} failed.")
        }
        if (failures > 0) FileSyncCenterActionResult.Rejected(message)
        else FileSyncCenterActionResult.Completed(message)
        }
    }

    suspend fun resolveConflictAndRun(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        workId: Long,
        choice: FileSyncDecisionChoice,
    ): FileSyncCenterActionResult = resolveConflictsAndRun(
        session = session,
        userId = userId,
        pairId = pairId,
        resolutions = listOf(FileSyncConflictResolution(workId, choice)),
    )

    suspend fun resolveConflictsAndRun(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        resolutions: List<FileSyncConflictResolution>,
    ): FileSyncCenterActionResult = withAndroidFileSyncRunCancellation { cancellation ->
        ENGINE_LOCK.withLock {
        val current = store.load()
        val pair = current.coordinator.pairs.firstOrNull { it.id == pairId }
            ?: return@withLock FileSyncCenterActionResult.Rejected(
                "The folder sync pair no longer exists.",
            )
        if (pair.accountId != NextcloudDocumentIds.accountKey(session)) {
            return@withLock FileSyncCenterActionResult.Rejected(
                "This folder sync pair belongs to another account.",
            )
        }
        val resolved = runCatching {
            resolveFileSyncDecisions(current.coordinator, pairId, resolutions)
        }.getOrElse { failure ->
            return@withLock FileSyncCenterActionResult.Rejected(
                safeFailureMessage(
                    failure,
                    "That conflict decision is no longer valid. Scan again.",
                ),
            )
        }
        runPairLocked(
            session = session,
            userId = userId,
            pairId = pairId,
            expectedResolvedWorkIds = resolutions.mapTo(mutableSetOf(), FileSyncConflictResolution::workId),
            rejectedResolutionBaseline = current.coordinator,
            startingCoordinatorOverride = resolved,
            transferCancellation = cancellation,
        )
        }
    }

    private fun execute(
        command: FileSyncExecutionCommand,
        state: FileSyncCoordinatorState,
        local: AndroidFileSyncLocalTree,
        remote: AndroidFileSyncRemoteTree,
        contentReadBudget: AndroidFileSyncContentReadBudget,
        persistUploadCheckpoint: (FileSyncUploadCheckpoint) -> Unit,
    ): FileSyncExecutionSuccess {
        val pair = state.pairs.first { it.id == command.pairId }
        val work = pair.workItems.first { it.id == command.workId }
        val accountStagingRoot = androidFileSyncAccountStagingRoot(stagingRoot, pair.accountId)
        require(isAndroidFileSyncExecutionAllowed(pair.localRootId, command.operation)) {
            "Detected media folders permit upload operations only."
        }
        return when (val operation = command.operation) {
            is FileSyncOperation.Upload -> {
                val source = requireNotNull(work.observedLocal)
                val replacingType = work.observedRemote?.kind?.let { it != source.kind } == true
                if (source.kind == SyncEntryKind.Directory) {
                    if (replacingType) {
                        remote.delete(operation.relativePath, requireNotNull(operation.expectedRemoteEtag))
                    }
                    remote.createDirectory(operation.relativePath, operation.expectedRemoteEtag.takeUnless { replacingType })
                } else {
                    withAndroidFileSyncStagingFile(accountStagingRoot, "upload") { staged ->
                        val exactLocal = local.stageForUpload(
                            operation.relativePath, staged,
                            androidFileSyncStagingTransferLimit(accountStagingRoot, source.size),
                            remote::shouldContinueTransfer,
                        )
                        val protectedDirectoryReplacement =
                            shouldProtectAndroidFileSyncDirectoryReplacement(exactLocal, work.observedRemote)
                        if (replacingType && !protectedDirectoryReplacement) {
                            remote.delete(operation.relativePath, requireNotNull(operation.expectedRemoteEtag))
                        }
                        val expectedRemote = operation.expectedRemoteEtag.takeUnless {
                            replacingType && !protectedDirectoryReplacement
                        }
                        resumeAndroidFileSyncUpload(
                            staged, operation.relativePath, exactLocal, source.revision, expectedRemote,
                            work.uploadCheckpoint, persistUploadCheckpoint, remote,
                            replacingDirectoryEtag = operation.expectedRemoteEtag
                                .takeIf { protectedDirectoryReplacement },
                        )
                    }
                }
                synchronizedResult(operation.relativePath, local, remote, contentReadBudget)
            }
            is FileSyncOperation.Download -> {
                downloadAndroidFileSyncOperation(local, remote, operation, work)
                synchronizedResult(operation.relativePath, local, remote, contentReadBudget)
            }
            is FileSyncOperation.DeleteLocal -> {
                deleteAndroidFileSyncOperation(local, remote, operation, work)
                require(local.resolve(operation.relativePath) == null)
                require(remote.resolve(operation.relativePath) == null)
                FileSyncExecutionSuccess(removedRelativePaths = listOf(operation.relativePath))
            }
            is FileSyncOperation.DeleteRemote -> {
                remote.delete(operation.relativePath, operation.expectedRemoteEtag)
                require(local.resolve(operation.relativePath) == null)
                require(remote.resolve(operation.relativePath) == null)
                FileSyncExecutionSuccess(removedRelativePaths = listOf(operation.relativePath))
            }
            is FileSyncOperation.KeepBoth -> executeKeepBoth(
                operation,
                work,
                local,
                remote,
                contentReadBudget,
                accountStagingRoot,
            )
            is FileSyncOperation.NeedsDecision,
            is FileSyncOperation.Skipped,
            -> error("Non-executable sync work was claimed.")
        }
    }

    private fun executeKeepBoth(
        operation: FileSyncOperation.KeepBoth,
        work: dev.obiente.nextcloudnative.app.FileSyncWorkItem,
        local: AndroidFileSyncLocalTree,
        remote: AndroidFileSyncRemoteTree,
        contentReadBudget: AndroidFileSyncContentReadBudget,
        accountStagingRoot: File,
    ): FileSyncExecutionSuccess {
        executeAndroidFileSyncKeepBoth(operation, work, local, remote, accountStagingRoot)
        return FileSyncExecutionSuccess(
            synchronizedBaselines = listOf(
                verifiedBaseline(
                    operation.relativePath,
                    local,
                    remote,
                    contentReadBudget,
                    expectedRemoteEtag = requireNotNull(work.observedRemote).etag,
                ),
                verifiedBaseline(operation.localConflictPath, local, remote, contentReadBudget),
                verifiedBaseline(operation.remoteConflictPath, local, remote, contentReadBudget),
            ),
        )
    }

    private fun synchronizedResult(
        path: String,
        local: AndroidFileSyncLocalTree,
        remote: AndroidFileSyncRemoteTree,
        contentReadBudget: AndroidFileSyncContentReadBudget,
    ): FileSyncExecutionSuccess =
        FileSyncExecutionSuccess(
            synchronizedBaselines = listOf(verifiedBaseline(path, local, remote, contentReadBudget)),
        )

    private fun verifiedBaseline(
        path: String,
        local: AndroidFileSyncLocalTree,
        remote: AndroidFileSyncRemoteTree,
        contentReadBudget: AndroidFileSyncContentReadBudget,
        expectedRemoteEtag: String? = null,
    ): FileSyncBaseline {
        val localEntry = requireNotNull(local.resolve(path)) { "The local result could not be verified." }.entry
        val remoteEntry = requireNotNull(remote.resolve(path)) { "The server result could not be verified." }.entry
        expectedRemoteEtag?.let { expected ->
            require(remoteEntry.etag == expected) {
                "The server result changed before the synchronized baseline was recorded."
            }
        }
        require(localEntry.kind == remoteEntry.kind) { "The synchronized item types do not match." }
        val contentHash = localEntry.size
            ?.takeIf { localEntry.kind == SyncEntryKind.File && contentReadBudget.reserve(it) }
            ?.let { size -> local.contentHash(path, localEntry.revision, size, maxOf(1L, size)) }
        return FileSyncBaseline(path, localEntry.kind, localEntry.revision, remoteEntry.etag, contentHash)
    }

    internal companion object {
        internal val ENGINE_LOCK = Mutex()
    }
}
