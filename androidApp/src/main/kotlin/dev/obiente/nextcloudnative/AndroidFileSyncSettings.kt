package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.await
import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncExecutionState
import dev.obiente.nextcloudnative.app.FileSyncRejectionScope
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.fileSyncOwnedUploads
import dev.obiente.nextcloudnative.app.updateFileSyncPairConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal suspend fun updateAndroidFileSyncSettings(
    context: Context,
    session: NextcloudSession,
    userId: String,
    pairId: String,
    expected: FileSyncConfiguration,
    configuration: FileSyncConfiguration,
    resolveSession: suspend () -> NextcloudSession?,
): FileSyncCenterActionResult {
    if (userId.isBlank()) return settingsRejected("Connect to your account before changing sync settings.")
    if (AndroidFileSyncEngine.ENGINE_LOCK.isLocked) return settingsRejected("Wait for the current sync to finish, then save again.")
    return ANDROID_ACCOUNT_OPERATION_GUARD.withExactAccountSession(
        expectedSession = session, resolveSession = resolveSession,
        unavailable = { settingsRejected("The account changed. Reopen this sync before saving.") },
    ) { current ->
        val lock = AndroidFileSyncEngine.ENGINE_LOCK
        if (!lock.tryLock()) return@withExactAccountSession settingsRejected("Wait for the current sync to finish, then save again.")
        try {
            val scheduler = AndroidFileSyncScheduler(context)
            val accountId = NextcloudDocumentIds.accountKey(current)
            persistAndroidFileSyncSettings(
                store = AndroidFileSyncStore(context), accountId = accountId, pairId = pairId,
                expected = expected, configuration = configuration,
                reschedule = {
                    withTimeout(5_000) { scheduler.schedule(pairId, accountId, userId, configuration).await() }
                },
                recoverSchedule = { scheduler.restorePersistedPairSchedules(accountId) },
            )
        } finally { lock.unlock() }
    }
}

/** The caller owns the account and engine locks. No remote operation occurs while editing. */
internal suspend fun persistAndroidFileSyncSettings(
    store: AndroidFileSyncStore,
    accountId: String,
    pairId: String,
    expected: FileSyncConfiguration,
    configuration: FileSyncConfiguration,
    reschedule: suspend () -> Unit,
    recoverSchedule: () -> Unit,
): FileSyncCenterActionResult {
    currentCoroutineContext().ensureActive()
    val current = store.load()
    val pair = current.coordinator.pairs.singleOrNull { it.id == pairId && it.accountId == accountId }
        ?: return settingsRejected("This sync is no longer available for this account.")
    if (pair.configuration != expected) return settingsRejected("Sync settings changed. Close the editor and review them before saving again.")
    if (configuration == expected) return FileSyncCenterActionResult.Completed("Sync settings are unchanged.")
    if (pair.workItems.any { it.state == FileSyncExecutionState.Running } || fileSyncOwnedUploads(pair).isNotEmpty()) {
        return settingsRejected("Recover unfinished uploads with Sync now before changing settings.")
    }
    if (!supportsAndroidFileSyncDirection(pair.localRootId, configuration.direction)) {
        return settingsRejected("Detected media folders support device-to-Nextcloud sync only.")
    }
    val updated = current.copy(coordinator = updateFileSyncPairConfiguration(current.coordinator, pairId, configuration))
    currentCoroutineContext().ensureActive()
    // Once committed, finish schedule reconciliation even if the editor closes or rotates.
    return withContext(NonCancellable) {
        store.save(updated)
        try {
            reschedule()
            FileSyncCenterActionResult.Completed("Sync settings saved. The next check will use these settings.")
        } catch (failure: Exception) {
            // A scheduling timeout is local to this bounded reconciliation, not an unknown remote write.
            if (failure is CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
            val recoveryQueued = try { recoverSchedule(); true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            FileSyncCenterActionResult.Completed(if (recoveryQueued) {
                "Sync settings saved. Reopen the app if automatic checks do not resume."
            } else {
                "Sync settings saved. Reopen the app to restore automatic checks."
            })
        }
    }
}

private fun settingsRejected(message: String) = FileSyncCenterActionResult.Rejected(message, FileSyncRejectionScope.Preflight)
