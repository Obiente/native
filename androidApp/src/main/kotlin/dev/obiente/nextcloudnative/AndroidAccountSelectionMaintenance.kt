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
    try {
        withContext(NonCancellable) { finishMaintenance() }
    } catch (failure: Exception) {
        val originalCancellation = cancellation ?: throw failure
        retainAndroidAccountMaintenanceFailure(originalCancellation, failure)
        throw originalCancellation
    }
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
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        recordCommittedAndroidAccountDiagnostic { recordFailure(failure) }
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
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        recordCommittedAndroidAccountDiagnostic { recordFailure(failure) }
    }
}

/** Required activation has completed; keep both maintenance steps owned before propagating cancellation. */
internal suspend fun finishAndroidAccountSelectionMaintenance(
    clearPreviousPreview: () -> Unit,
    resumeUploadsAndNotify: suspend () -> Unit,
) {
    var cancellation: CancellationException? = null
    try {
        clearPreviousPreview()
    } catch (cancelled: CancellationException) {
        cancellation = cancelled
    }
    try {
        resumeUploadsAndNotify()
    } catch (failure: Exception) {
        val originalCancellation = cancellation ?: throw failure
        retainAndroidAccountMaintenanceFailure(originalCancellation, failure)
        throw originalCancellation
    }
    cancellation?.let { throw it }
}

internal fun retainAndroidAccountMaintenanceFailure(original: CancellationException, failure: Exception) {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = failure
    repeat(32) {
        val cause = current ?: run { original.addSuppressed(failure); return }
        // Coroutine recovery copies can point back to the cancellation being preserved.
        if (cause === original) return
        if (!seen.add(cause)) return
        current = cause.cause
    }
    // Do not attach an unbounded chain whose link back to the original is unknown.
}
