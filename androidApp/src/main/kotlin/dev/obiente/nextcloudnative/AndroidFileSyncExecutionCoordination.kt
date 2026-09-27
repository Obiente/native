package dev.obiente.nextcloudnative

import android.content.Context
import android.net.Uri
import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import dev.obiente.nextcloudnative.app.FileSyncDirection
import dev.obiente.nextcloudnative.app.FileSyncOperation
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

internal suspend fun reconcileFileSyncCapabilities(
    lock: Mutex,
    load: () -> AndroidFileSyncPersistedState,
    capabilities: AndroidFileSyncCapabilityLifecycle,
    reclaimUnrestoredReady: Boolean = false,
    onFailure: (Exception) -> Unit = {},
) {
    lock.withLock {
        try {
            capabilities.reconcile(load(), reclaimUnrestoredReady)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // Callers with a durable retry owner propagate failure; UI recovery can defer it.
            onFailure(failure)
        }
    }
}

internal suspend fun reconcileRestoredFileSyncSetup(
    context: Context,
    session: dev.obiente.nextcloudnative.app.NextcloudSession,
    restoredLocalRoot: dev.obiente.nextcloudnative.app.FileSyncLocalRoot?,
): Boolean = AndroidFileSyncEngine.ENGINE_LOCK.withLock {
    AndroidFileSyncCapabilityLifecycle(context).reconcileRestoredSetup(
        accountId = AndroidFileSyncCapabilityAccountId(NextcloudDocumentIds.accountKey(session)),
        restoredLocalRootId = restoredLocalRoot?.localRootId,
        state = AndroidFileSyncStore(context).load(),
    )
}

internal fun recoverFailedFileSyncPairSave(
    pairId: String,
    load: () -> AndroidFileSyncPersistedState,
    abandonUncommittedPair: (String) -> Boolean,
): Boolean {
    val commitIsPresent = try {
        load().coordinator.pairs.any { it.id == pairId }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return false
    }
    if (!commitIsPresent) check(abandonUncommittedPair(pairId)) {
        "The saved folder access still needs cleanup. Retry closing the folder setup."
    }
    return commitIsPresent
}

internal fun bindAndPersistFileSyncPair(
    pairId: String,
    bindReady: () -> Unit,
    persist: () -> Unit,
    load: () -> AndroidFileSyncPersistedState,
    abandonUncommittedPair: (String) -> Boolean,
) {
    try {
        bindReady()
        persist()
    } catch (failure: Exception) {
        val committed = try {
            recoverFailedFileSyncPairSave(pairId, load, abandonUncommittedPair)
        } catch (cancelled: CancellationException) {
            if (failure is CancellationException) {
                if (cancelled !== failure) failure.addSuppressed(cancelled)
                throw failure
            }
            cancelled.addSuppressed(failure)
            throw cancelled
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
            false
        }
        if (committed && failure !is CancellationException) return
        throw failure
    }
}

internal fun scheduleCommittedFileSyncPair(schedule: () -> Unit): Boolean = try {
    schedule()
    true
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    false
}

internal fun committedFileSyncPairResult(schedule: () -> Unit): FileSyncCenterActionResult {
    val scheduled = scheduleCommittedFileSyncPair(schedule)
    return FileSyncCenterActionResult.Completed(if (scheduled) {
        "Folder sync pair added. Run it to review the first sync."
    } else {
        "Folder sync pair added. Automatic checks will retry when folder sync status is loaded."
    })
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
    reconcileLocalDownloads: suspend () -> Boolean,
    cleanRemoteUploads: suspend () -> Boolean,
    cleanLedger: suspend () -> Unit,
    persistRemoval: suspend () -> Unit,
    cancelSchedule: suspend () -> Unit,
    releaseLocalGrant: suspend () -> Unit,
): Boolean {
    if (!reconcileLocalDownloads()) return false
    currentCoroutineContext().ensureActive()
    if (!cleanRemoteUploads()) return false
    currentCoroutineContext().ensureActive()
    commitConfiguredFileSyncPairRemoval(
        cleanLedger = cleanLedger,
        persistRemoval = persistRemoval,
        cancelSchedule = cancelSchedule,
        releaseLocalGrant = releaseLocalGrant,
    )
    return true
}

internal suspend fun commitConfiguredFileSyncPairRemoval(
    cleanLedger: suspend () -> Unit,
    persistRemoval: suspend () -> Unit,
    cancelSchedule: suspend () -> Unit,
    releaseLocalGrant: suspend () -> Unit,
) = withContext(NonCancellable) {
    cleanLedger()
    persistRemoval()
    try {
        cancelSchedule()
    } finally {
        releaseLocalGrant()
    }
}

internal suspend fun reconcileSafDownloadsBeforePairRemoval(
    context: Context,
    localRootId: String,
    localRecoveryPaths: Set<String>,
    providerRecoverySession: NextcloudSession? = null,
): Boolean {
    if (!localRootId.startsWith("content://")) return true
    val shouldContinue = androidFileSyncJobContinuation(currentCoroutineContext()[Job])
    if (!shouldContinue()) throw CancellationException("Pair removal was cancelled.")
    val treeUri = Uri.parse(localRootId)
    val hasPersistedGrant = try {
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission && permission.isWritePermission
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        return false
    }
    if (!shouldContinue()) throw CancellationException("Pair removal was cancelled.")
    val hasPendingRecovery = try {
        createAndroidSafDownloadOwnershipStore(
            context.applicationContext,
            localRootId,
        ).hasPendingTransactions()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        return false
    }
    if (!shouldContinue()) throw CancellationException("Pair removal was cancelled.")
    val reconciled = reconcileSafDownloadsBeforePairRemoval(hasPersistedGrant, hasPendingRecovery) {
        if (
            androidPickerUriRejection(localRootId, context.applicationContext.packageName) ==
            AndroidPickerUriRejection.OwnDocumentsProvider
        ) {
            reconcileOwnProviderSafDownloadsBeforePairRemoval(
                context = context,
                localRootId = localRootId,
                localRecoveryPaths = localRecoveryPaths,
                shouldContinue = shouldContinue,
                providerRecoverySession = providerRecoverySession,
            )
        } else {
            createAndroidFileSyncLocalTree(context, localRootId).reconcileOwnedDownloads(shouldContinue)
        }
    }
    if (!shouldContinue()) throw CancellationException("Pair removal was cancelled.")
    return reconciled
}

internal fun androidFileSyncJobContinuation(job: Job?): () -> Boolean =
    { job?.isActive != false && !Thread.currentThread().isInterrupted }

internal fun reconcileSafDownloadsBeforePairRemoval(
    hasPersistedGrant: Boolean,
    hasPendingRecovery: Boolean,
    reconcile: () -> Unit,
): Boolean {
    if (!hasPendingRecovery) return true
    if (!hasPersistedGrant) return false
    return try {
        reconcile()
        true
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        false
    }
}

internal suspend fun retireAndroidFileSyncAccountPairs(context: Context, accountId: String, providerRecoverySession: NextcloudSession? = null) {
    val store = AndroidFileSyncStore(context)
    fun accountPairs() = store.load().coordinator.pairs.filter { it.accountId == accountId }
    val capabilities = AndroidFileSyncCapabilityLifecycle(context)
    val snapshot = AndroidFileSyncEngine.ENGINE_LOCK.withLock {
        val current = store.loadAndReconcileUploadCleanups()
        capabilities.retireAccountSetup(AndroidFileSyncCapabilityAccountId(accountId), state = current)
        reconcileAndroidFileSyncAccountRetirement(current, accountId, capabilities)
    }
    if (snapshot.isEmpty()) return
    withRecoveredFileSyncPairSnapshot(
        lock = AndroidFileSyncEngine.ENGINE_LOCK,
        snapshot = snapshot,
        readCurrentSnapshot = ::accountPairs,
        reconcile = { pair ->
            reconcileSafDownloadsBeforePairRemoval(
                context, pair.localRootId, androidSafOwnedDownloadRecoveryPaths(pair), providerRecoverySession,
            )
        },
        onRecoveryRejected = { error("A local download still needs safe recovery. Run this folder sync before removing the account.") },
        onSnapshotChanged = { error("Folder sync changed during recovery. Review it before removing the account.") },
    ) {
        val current = store.load()
        capabilities.reconcile(current)
        val scheduler = AndroidFileSyncScheduler(context)
        val notifications = AndroidNotificationCoordinator(context)
        retireConfiguredFileSyncAccountPairs(
            retiredPairs = snapshot,
            reconcileLocalDownloads = { true },
            cancelSchedule = { pair -> scheduler.cancel(pair.id) },
            cancelNotification = { pair ->
                notifications.cancel(pair.accountId, androidFileSyncNotificationId(pair.id))
            },
            prepareLocalGrantCleanup = capabilities::preparePairCleanup,
            persistRetirement = { store.save(removeAndroidFileSyncAccountPairs(current, accountId)) },
            finishLocalGrantCleanup = { pairId -> capabilities.finishPairCleanupOrRetry(pairId, load = store::load) },
        )
    }
}

internal suspend fun reconcileAndroidFileSyncAccountDownloadsBeforeCredentialRemoval(
    context: Context,
    accountId: String,
    providerRecoverySession: NextcloudSession,
    accountLeaseHeld: Boolean = false,
) {
    val store = AndroidFileSyncStore(context)
    fun accountPairs() = store.load().coordinator.pairs.filter { it.accountId == accountId }
    if (AndroidFileSyncEngine.ENGINE_LOCK.withLock { accountPairs().isEmpty() }) return
    val services = AndroidNextcloudServices(context.applicationContext)
    withAndroidFileSyncAccountRecoveryLease(
        expectedSession = providerRecoverySession,
        resolveSession = { services.loadSession(providerRecoverySession.accountId) },
        accountLeaseHeld = accountLeaseHeld,
    ) {
        val snapshot = AndroidFileSyncEngine.ENGINE_LOCK.withLock { accountPairs() }
        withRecoveredFileSyncPairSnapshot(
            lock = AndroidFileSyncEngine.ENGINE_LOCK,
            snapshot = snapshot,
            readCurrentSnapshot = ::accountPairs,
            reconcile = { pair ->
                reconcileSafDownloadsBeforePairRemoval(
                    context, pair.localRootId, androidSafOwnedDownloadRecoveryPaths(pair), providerRecoverySession,
                )
            },
            onRecoveryRejected = { error("A local download still needs safe recovery. Run this folder sync before removing the account.") },
            onSnapshotChanged = { error("Folder sync changed during recovery. Review it before removing the account.") },
            commit = {},
        )
    }
}

internal suspend fun <Result> withAndroidFileSyncAccountRecoveryLease(
    expectedSession: NextcloudSession,
    resolveSession: suspend () -> NextcloudSession?,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    accountLeaseHeld: Boolean = false,
    action: suspend () -> Result,
): Result = if (accountLeaseHeld) action() else guard.withAccounts(androidAccountOperationIdentities(expectedSession)) {
    check(resolveSession() == expectedSession) { "The account changed before folder sync recovery could start." }
    action()
}

internal suspend fun reconcileConfiguredFileSyncAccountDownloadsBeforeCredentialRemoval(
    pairs: List<FileSyncPair>,
    accountId: String,
    reconcileLocalDownloads: suspend (FileSyncPair) -> Boolean,
) {
    require(accountId.isNotBlank())
    pairs.filter { pair -> pair.accountId == accountId }.forEach { pair ->
        check(reconcileLocalDownloads(pair)) {
            "A local download still needs safe recovery. Run this folder sync before removing the account."
        }
        currentCoroutineContext().ensureActive()
    }
}

internal fun reconcileAndroidFileSyncAccountRetirement(
    state: AndroidFileSyncPersistedState,
    accountId: String,
    capabilities: AndroidFileSyncCapabilityLifecycle,
): List<FileSyncPair> {
    capabilities.reconcile(state)
    return state.coordinator.pairs.filter { pair -> pair.accountId == accountId }
}

internal suspend fun retireConfiguredFileSyncAccountPairs(
    retiredPairs: List<FileSyncPair>,
    reconcileLocalDownloads: suspend (FileSyncPair) -> Boolean,
    cancelSchedule: suspend (FileSyncPair) -> Unit,
    cancelNotification: suspend (FileSyncPair) -> Unit,
    prepareLocalGrantCleanup: suspend (String) -> Unit,
    persistRetirement: suspend () -> Unit,
    finishLocalGrantCleanup: suspend (String) -> Unit,
) {
    retiredPairs.forEach { pair ->
        check(reconcileLocalDownloads(pair)) {
            "A local download still needs safe recovery. Run this folder sync before removing the account."
        }
        currentCoroutineContext().ensureActive()
    }
    withContext(NonCancellable) {
        retiredPairs.forEach { pair -> prepareLocalGrantCleanup(pair.id) }
    }
    currentCoroutineContext().ensureActive()

    retiredPairs.forEach { pair ->
        cancelSchedule(pair)
        cancelNotification(pair)
    }
    currentCoroutineContext().ensureActive()

    withContext(NonCancellable) {
        persistRetirement()
        retiredPairs.forEach { pair -> finishLocalGrantCleanup(pair.id) }
    }
}

internal suspend fun requireAndroidFileSyncAccountRemovalReady(context: Context, accountId: String) {
    AndroidFileSyncEngine.ENGINE_LOCK.withLock {
        requireAndroidFileSyncAccountRemovalReady(AndroidFileSyncStore(context).load(), accountId)
    }
}
