package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun completeAndroidAccountSelectionTransition(
    transitionDispatcher: CoroutineDispatcher = Dispatchers.IO,
    commitTransition: (() -> Unit) -> Unit,
    finishMaintenance: suspend () -> Unit,
) {
    val committed = AtomicBoolean()
    var cancellation: CancellationException? = null
    try {
        withContext(transitionDispatcher) {
            commitTransition { committed.set(true) }
        }
    } catch (cancelled: CancellationException) {
        if (!committed.get()) throw cancelled
        cancellation = cancelled
    }
    withContext(NonCancellable) { finishMaintenance() }
    cancellation?.let { throw it }
    currentCoroutineContext().ensureActive()
}

internal fun commitAndroidAccountTransitionBeforeHandoffCleanup(
    commitTransition: () -> Unit,
    clearHandoffs: () -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    commitTransition()
    try {
        clearHandoffs()
    } catch (failure: Exception) {
        runCatching { recordFailure(failure) }
    }
}

internal fun clearAndroidPreviousPreviewAfterCommittedSelection(
    previousSession: NextcloudSession?,
    selectedSession: NextcloudSession,
    clearPreviewAccount: (String) -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    if (previousSession == null || previousSession.accountId == selectedSession.accountId) return
    try {
        clearPreviewAccount(NextcloudDocumentIds.cacheAccountId(previousSession))
    } catch (failure: Exception) {
        runCatching { recordFailure(failure) }
    }
}
