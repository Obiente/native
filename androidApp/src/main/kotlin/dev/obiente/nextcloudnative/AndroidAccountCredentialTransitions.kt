package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun removeActiveAndroidAccountCredentialState(
    state: AndroidAccountCredentialState,
): AndroidAccountCredentialState = state.registry.activeAccountId?.let(state::remove) ?: state

internal suspend fun rollbackUnavailableAndroidAccountRemoval(
    recovered: AndroidAccountCredentialState,
    persistRecovered: suspend (AndroidAccountCredentialState) -> Unit,
    clearCleanup: suspend () -> Unit,
) {
    persistRecovered(recovered)
    clearCleanup()
}

internal suspend fun resumeAndroidQueuedUploadsAfterSelection(
    resume: suspend () -> Unit,
    notifyDocumentRootsChanged: () -> Unit,
    recordFailure: () -> Unit,
) {
    try {
        resume()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        recordFailure()
    } finally {
        notifyDocumentRootsChanged()
    }
}

internal suspend fun removeAndroidAccountCredentialData(
    active: Boolean,
    prepareAccountRemoval: suspend () -> Unit = {},
    removeQueuedUploads: suspend () -> Unit,
    clearActiveAccount: suspend () -> Unit,
    rollbackActiveRemoval: suspend () -> Unit,
    persistInactiveRemoval: suspend () -> Unit,
    rollbackInactiveRemoval: suspend () -> Unit,
    completeCommittedCleanup: suspend () -> Unit = {},
    recordCommittedCleanupFailure: (Exception) -> Unit = {},
) {
    prepareAccountRemoval()
    if (active) {
        try {
            clearActiveAccount()
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                runCatching { rollbackActiveRemoval() }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
        finishCommittedAndroidAccountRemovalCleanup(
            removeQueuedUploads,
            completeCommittedCleanup,
            recordCommittedCleanupFailure,
        )
        return
    }

    try {
        persistInactiveRemoval()
    } catch (failure: Exception) {
        withContext(NonCancellable) {
            runCatching { rollbackInactiveRemoval() }
                .onFailure(failure::addSuppressed)
        }
        throw failure
    }
    finishCommittedAndroidAccountRemovalCleanup(
        removeQueuedUploads,
        completeCommittedCleanup,
        recordCommittedCleanupFailure,
    )
}

private suspend fun finishCommittedAndroidAccountRemovalCleanup(
    removeQueuedUploads: suspend () -> Unit,
    completeCommittedCleanup: suspend () -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    try {
        removeQueuedUploads()
        completeCommittedCleanup()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        recordFailure(failure)
    }
}

internal suspend fun removeRecoveredAndroidAccountCredentialData(
    prepareAccountRemoval: suspend () -> Unit = {},
    removeQueuedUploads: suspend () -> Unit,
    clearRecoveredAccount: suspend () -> Unit,
    rollbackRecoveredAccount: suspend () -> Unit,
    completeCommittedCleanup: suspend () -> Unit = {},
    recordCommittedCleanupFailure: (Exception) -> Unit = {},
) = removeAndroidAccountCredentialData(
    active = true,
    prepareAccountRemoval = prepareAccountRemoval,
    removeQueuedUploads = removeQueuedUploads,
    clearActiveAccount = clearRecoveredAccount,
    rollbackActiveRemoval = rollbackRecoveredAccount,
    persistInactiveRemoval = {},
    rollbackInactiveRemoval = {},
    completeCommittedCleanup = completeCommittedCleanup,
    recordCommittedCleanupFailure = recordCommittedCleanupFailure,
)
