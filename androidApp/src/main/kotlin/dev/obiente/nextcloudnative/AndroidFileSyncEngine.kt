package dev.obiente.nextcloudnative

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import dev.obiente.nextcloudnative.app.FileSyncCenterSnapshot
import dev.obiente.nextcloudnative.app.FileSyncCenterSupport
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncExecutionCommand
import dev.obiente.nextcloudnative.app.FileSyncExecutionSuccess
import dev.obiente.nextcloudnative.app.FileSyncExecutionState
import dev.obiente.nextcloudnative.app.FileSyncLocalRoot
import dev.obiente.nextcloudnative.app.FileSyncNetworkState
import dev.obiente.nextcloudnative.app.FileSyncPairRunState
import dev.obiente.nextcloudnative.app.FileSyncDecisionChoice
import dev.obiente.nextcloudnative.app.FileSyncDirection
import dev.obiente.nextcloudnative.app.FileSyncOperation
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.MediaBackupLedgerStore
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.addFileSyncPair
import dev.obiente.nextcloudnative.app.claimNextFileSyncOperation
import dev.obiente.nextcloudnative.app.completeFileSyncOperation
import dev.obiente.nextcloudnative.app.failFileSyncOperation
import dev.obiente.nextcloudnative.app.removeFileSyncPair
import dev.obiente.nextcloudnative.app.resolveFileSyncDecision
import dev.obiente.nextcloudnative.app.retryFileSyncOperation
import dev.obiente.nextcloudnative.app.scanFileSyncPair
import dev.obiente.nextcloudnative.app.toCenterSummary
import dev.obiente.nextcloudnative.app.includesSyncPath
import dev.obiente.nextcloudnative.app.liveFileSyncNetworkState
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
        cloudMutationsAllowed = appContext.cloudMutationGate(),
    )
    private val scheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidFileSyncScheduler(appContext)
    }
    private val reconciliationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduledMediaReconciliations = ConcurrentHashMap.newKeySet<String>()
    private val scheduledPairScheduling = DeferredFileSyncPairSchedulingRegistry()
    private val stagingRoot = File(appContext.cacheDir, "file-sync-staging")

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
        createAndroidFileSyncLocalTree(appContext.contentResolver, localRoot.localRootId)
        val normalizedRemote = normalizeRemoteRoot(remoteRootPath)
        val accountId = NextcloudDocumentIds.accountKey(session)
        val current = store.load()
        if (current.coordinator.pairs.any {
                it.accountId == accountId &&
                    it.localRootId == localRoot.localRootId &&
                    it.remoteRootPath == normalizedRemote
            }
        ) {
            return@withLock FileSyncCenterActionResult.Rejected(
                "That local and Nextcloud folder pair already exists.",
            )
        }
        val pair = FileSyncPair(
            id = UUID.randomUUID().toString(),
            accountId = accountId,
            localRootId = localRoot.localRootId,
            remoteRootPath = normalizedRemote,
            configuration = configuration,
        )
        store.save(
            current.copy(
                coordinator = addFileSyncPair(current.coordinator, pair),
                localDisplayNames = current.localDisplayNames + (pair.id to localRoot.displayName),
            ),
        )
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

    suspend fun removePair(session: NextcloudSession, pairId: String): FileSyncCenterActionResult =
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
            val remaining = removeFileSyncPair(current.coordinator, pairId)
            val mediaStore = createAndroidMediaBackupLedgerStore(
                context = appContext,
                recoverInterruptedTransfers = false,
            )
            removeConfiguredFileSyncPair(
                cleanLedger = {
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
                    store.save(
                        current.copy(
                            coordinator = remaining,
                            localDisplayNames = current.localDisplayNames - pairId,
                        ),
                    )
                },
                cancelSchedule = { scheduler.cancel(pairId) },
            )
            if (
                pair.localRootId.startsWith("content://") &&
                remaining.pairs.none { it.localRootId == pair.localRootId }
            ) {
                runCatching {
                    appContext.contentResolver.releasePersistableUriPermission(
                        Uri.parse(pair.localRootId),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            FileSyncCenterActionResult.Completed("Folder sync pair removed. No local or server files were deleted.")
        }

    suspend fun runPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult = ENGINE_LOCK.withLock {
        runPairLocked(session, userId, pairId)
    }

    private suspend fun runPairLocked(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult {
        var persisted = store.load()
        val initialPair = persisted.coordinator.pairs.firstOrNull { it.id == pairId }
            ?: return FileSyncCenterActionResult.Rejected("The folder sync pair no longer exists.")
        if (initialPair.accountId != NextcloudDocumentIds.accountKey(session)) {
            return FileSyncCenterActionResult.Rejected("This folder sync pair belongs to another account.")
        }
        if (!supportsAndroidFileSyncDirection(initialPair.localRootId, initialPair.configuration.direction)) {
            return FileSyncCenterActionResult.Rejected(
                "This detected media-folder pair is not upload-only. Remove it and add it again.",
            )
        }
        return withAndroidMediaBackupLedger(appContext, initialPair) { mediaLedger ->
        val remote = AndroidFileSyncRemoteTree(
            session,
            userId,
            initialPair.remoteRootPath,
            webDav,
        )
        val configuration = initialPair.configuration
        val includes: (String, SyncEntryKind) -> Boolean = { relativePath, kind ->
            configuration.includesSyncPath(relativePath, kind)
        }
        var remoteEntries = remote.scan(includes).map(AndroidRemoteSyncDocument::entry)
        val contentHashPaths = remoteEntries
            .asSequence()
            .filter { it.kind == SyncEntryKind.File && it.contentHash != null }
            .mapTo(mutableSetOf()) { it.relativePath }
        val local = createAndroidFileSyncLocalTree(
            appContext.contentResolver,
            initialPair.localRootId,
            contentHashPaths,
        )
        var localEntries = local.scan(includes).map(AndroidLocalSyncDocument::entry)
        val baselineByPath = initialPair.baselines.associateBy(FileSyncBaseline::relativePath)
        val remoteByPath = remoteEntries.associateBy { it.relativePath }
        val verifiedContentPaths = localEntries
            .asSequence()
            .filter { localEntry ->
                val remoteEntry = remoteByPath[localEntry.relativePath]
                    localEntry.kind == SyncEntryKind.File &&
                    localEntry.contentHash != null &&
                    localEntry.contentHash == remoteEntry?.contentHash &&
                    (remoteEntry?.size ?: Long.MAX_VALUE) <= ANDROID_SYNC_CONTENT_IDENTITY_MAX_BYTES
            }
            .filter { localEntry ->
                val remoteEntry = requireNotNull(remoteByPath[localEntry.relativePath])
                val baseline = baselineByPath[localEntry.relativePath]
                baseline == null ||
                    localEntry.revision != baseline.localRevision ||
                    remoteEntry.etag != baseline.remoteEtag
            }
            .filter { localEntry ->
                val remoteEntry = requireNotNull(remoteByPath[localEntry.relativePath])
                runCatching {
                    remote.verifyContentHash(
                        relativePath = localEntry.relativePath,
                        expectedRemoteEtag = remoteEntry.etag,
                        expectedContentHash = requireNotNull(localEntry.contentHash),
                    )
                }.getOrDefault(false)
            }
            .mapTo(mutableSetOf()) { it.relativePath }
        localEntries = localEntries.map { entry ->
            entry.copy(contentHash = entry.contentHash.takeIf { entry.relativePath in verifiedContentPaths })
        }
        remoteEntries = remoteEntries.map { entry ->
            entry.copy(contentHash = entry.contentHash.takeIf { entry.relativePath in verifiedContentPaths })
        }
        persisted = persisted.copy(
            coordinator = scanFileSyncPair(
                persisted.coordinator,
                pairId,
                localEntries,
                remoteEntries,
                System.currentTimeMillis(),
                maximumWorkItems = ANDROID_FILE_SYNC_MAX_WORK_ITEMS,
                reservedNonExecutableWorkItems = ANDROID_FILE_SYNC_NON_EXECUTABLE_RESERVE,
            ),
        )
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
            localEntries = localEntries,
            remoteEntries = remoteEntries,
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
            val execution = runCatching {
                execute(command, persisted.coordinator, local, remote)
            }
            val failure = execution.exceptionOrNull()
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
    ): FileSyncCenterActionResult = ENGINE_LOCK.withLock {
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
            resolveFileSyncDecision(current.coordinator, pairId, workId, choice)
        }.getOrElse { failure ->
            return@withLock FileSyncCenterActionResult.Rejected(
                safeFailureMessage(
                    failure,
                    "That conflict decision is no longer valid. Scan again.",
                ),
            )
        }
        store.save(current.copy(coordinator = resolved))
        runPairLocked(session, userId, pairId)
    }

    private fun execute(
        command: FileSyncExecutionCommand,
        state: FileSyncCoordinatorState,
        local: AndroidFileSyncLocalTree,
        remote: AndroidFileSyncRemoteTree,
    ): FileSyncExecutionSuccess {
        val pair = state.pairs.first { it.id == command.pairId }
        val work = pair.workItems.first { it.id == command.workId }
        require(isAndroidFileSyncExecutionAllowed(pair.localRootId, command.operation)) {
            "Detected media folders permit upload operations only."
        }
        return when (val operation = command.operation) {
            is FileSyncOperation.Upload -> {
                val source = requireNotNull(work.observedLocal)
                if (work.observedRemote?.kind?.let { it != source.kind } == true) {
                    remote.delete(
                        operation.relativePath,
                        requireNotNull(operation.expectedRemoteEtag),
                    )
                }
                val expectedRemote = operation.expectedRemoteEtag
                    .takeUnless { work.observedRemote?.kind?.let { kind -> kind != source.kind } == true }
                if (source.kind == SyncEntryKind.Directory) {
                    remote.createDirectory(operation.relativePath, expectedRemote)
                } else {
                    withStagingFile("upload") { staged ->
                        local.stageForUpload(operation.relativePath, staged, MAX_SYNC_FILE_BYTES)
                        remote.writeFile(operation.relativePath, staged, expectedRemote)
                    }
                }
                synchronizedResult(operation.relativePath, local, remote)
            }
            is FileSyncOperation.Download -> {
                val source = requireNotNull(work.observedRemote)
                if (work.observedLocal?.kind?.let { it != source.kind } == true) {
                    local.delete(
                        operation.relativePath,
                        requireNotNull(operation.expectedLocalRevision),
                    )
                }
                val expectedLocal = operation.expectedLocalRevision
                    .takeUnless { work.observedLocal?.kind?.let { kind -> kind != source.kind } == true }
                if (source.kind == SyncEntryKind.Directory) {
                    local.createDirectory(operation.relativePath, expectedLocal)
                } else {
                    withStagingFile("download") { staged ->
                        remote.stageDownload(
                            operation.relativePath,
                            source.etag,
                            staged,
                            MAX_SYNC_FILE_BYTES,
                        )
                        local.writeFile(operation.relativePath, staged, expectedLocal)
                    }
                }
                synchronizedResult(operation.relativePath, local, remote)
            }
            is FileSyncOperation.DeleteLocal -> {
                local.delete(operation.relativePath, operation.expectedLocalRevision)
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
            is FileSyncOperation.KeepBoth -> executeKeepBoth(operation, work, local, remote)
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
    ): FileSyncExecutionSuccess {
        val localSource = requireNotNull(work.observedLocal)
        val remoteSource = requireNotNull(work.observedRemote)
        require(localSource.kind == SyncEntryKind.File && remoteSource.kind == SyncEntryKind.File) {
            "Keep both currently supports file conflicts only."
        }
        withStagingFile("keep-local") { localBytes ->
            withStagingFile("keep-remote") { remoteBytes ->
                local.stageForUpload(operation.relativePath, localBytes, MAX_SYNC_FILE_BYTES)
                remote.stageDownload(
                    operation.relativePath,
                    remoteSource.etag,
                    remoteBytes,
                    MAX_SYNC_FILE_BYTES,
                )
                remote.writeFile(operation.localConflictPath, localBytes, expectedRemoteEtag = null)
                local.writeFile(operation.localConflictPath, localBytes, expectedLocalRevision = null)
                remote.writeFile(operation.remoteConflictPath, remoteBytes, expectedRemoteEtag = null)
                local.writeFile(operation.remoteConflictPath, remoteBytes, expectedLocalRevision = null)
                local.writeFile(operation.relativePath, remoteBytes, localSource.revision)
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

    private fun synchronizedResult(
        path: String,
        local: AndroidFileSyncLocalTree,
        remote: AndroidFileSyncRemoteTree,
    ): FileSyncExecutionSuccess =
        FileSyncExecutionSuccess(synchronizedBaselines = listOf(verifiedBaseline(path, local, remote)))

    private fun verifiedBaseline(
        path: String,
        local: AndroidFileSyncLocalTree,
        remote: AndroidFileSyncRemoteTree,
    ): FileSyncBaseline {
        val localEntry = requireNotNull(local.resolve(path)) { "The local result could not be verified." }.entry
        val remoteEntry = requireNotNull(remote.resolve(path)) { "The server result could not be verified." }.entry
        require(localEntry.kind == remoteEntry.kind) { "The synchronized item types do not match." }
        return FileSyncBaseline(path, localEntry.kind, localEntry.revision, remoteEntry.etag)
    }

    private inline fun <T> withStagingFile(prefix: String, block: (File) -> T): T {
        check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Could not create sync staging storage." }
        val file = File.createTempFile("$prefix-", ".tmp", stagingRoot)
        return try {
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun normalizeRemoteRoot(path: String): String {
        val normalized = path.trim().trim('/')
        if (normalized.isEmpty()) return ""
        require(normalized.length <= 8_192)
        require(normalized.split('/').all {
            it.isNotBlank() && it !in setOf(".", "..") && it.none(Char::isISOControl)
        }) { "The Nextcloud folder path is invalid." }
        return normalized
    }

    private fun safeFailureMessage(failure: Throwable, fallback: String): String =
        failure.message
            ?.map { if (it.isISOControl()) ' ' else it }
            ?.joinToString("")
            ?.trim()
            ?.take(1_024)
            ?.takeIf(String::isNotBlank)
            ?: fallback

    private companion object {
        const val MAX_SYNC_FILE_BYTES = 8L * 1024L * 1024L * 1024L
        val ENGINE_LOCK = Mutex()
    }
}

internal const val ANDROID_FILE_SYNC_MAX_WORK_ITEMS = 10_000
internal const val ANDROID_FILE_SYNC_NON_EXECUTABLE_RESERVE = 1_000

internal fun supportsAndroidFileSyncDirection(
    localRootId: String,
    direction: FileSyncDirection,
): Boolean =
    !localRootId.startsWith(MEDIA_STORE_SYNC_ROOT_PREFIX) || direction == FileSyncDirection.UploadOnly

internal fun isAndroidFileSyncExecutionAllowed(
    localRootId: String,
    operation: FileSyncOperation,
): Boolean =
    !localRootId.startsWith(MEDIA_STORE_SYNC_ROOT_PREFIX) || operation is FileSyncOperation.Upload

internal suspend fun runWhenFileSyncIdle(
    lock: Mutex,
    action: suspend () -> Unit,
): Boolean {
    if (!lock.tryLock()) return false
    return try {
        action()
        true
    } finally {
        lock.unlock()
    }
}

internal fun deferFileSyncActionUntilIdle(
    lock: Mutex,
    scope: CoroutineScope,
    action: suspend () -> Unit,
): Job = scope.launch {
    lock.withLock {
        action()
    }
}

/**
 * Runs [action] only when both the engine and its WorkManager sources are idle.
 *
 * Source state is inspected while [lock] is held. A running worker is then awaited without the
 * engine lock so it can finish, after which current persisted sources are loaded and checked again.
 */
internal suspend fun runFileSyncActionWhenSourceWorkIdle(
    lock: Mutex,
    runningSourceIds: suspend () -> Set<String>,
    awaitSourcesNotRunning: suspend (Set<String>) -> Unit,
    action: suspend () -> Unit,
) {
    while (true) {
        var completed = false
        val running = lock.withLock {
            runningSourceIds().also { activeSourceIds ->
                if (activeSourceIds.isEmpty()) {
                    action()
                    completed = true
                }
            }
        }
        if (completed) return
        awaitSourcesNotRunning(running)
    }
}

internal fun <T> deferFileSyncSnapshotActionUntilIdle(
    lock: Mutex,
    scope: CoroutineScope,
    load: () -> T,
    onFinished: () -> Unit = {},
    action: (T) -> Unit,
): Job {
    val job = scope.launch {
        lock.withLock {
            action(load())
        }
    }
    job.invokeOnCompletion { onFinished() }
    return job
}

/**
 * Reads a complete atomic snapshot without waiting for active execution.
 *
 * Scheduling is allowed only from a snapshot loaded after acquiring [lock], so a concurrent pair
 * removal cannot be followed by stale work being re-enqueued. A busy read requests a deferred
 * post-idle reload rather than scheduling from the displayed, potentially stale snapshot.
 */
internal fun <T> loadFileSyncPresentationSnapshot(
    lock: Mutex,
    load: () -> T,
    scheduleWhenIdle: (T) -> Unit,
    scheduleAfterIdle: () -> Unit = {},
): T {
    if (!lock.tryLock()) {
        return load().also { scheduleAfterIdle() }
    }
    return try {
        load().also(scheduleWhenIdle)
    } finally {
        lock.unlock()
    }
}

internal suspend fun removeConfiguredFileSyncPair(
    cleanLedger: suspend () -> Unit,
    persistRemoval: suspend () -> Unit,
    cancelSchedule: suspend () -> Unit,
) {
    cleanLedger()
    persistRemoval()
    cancelSchedule()
}
