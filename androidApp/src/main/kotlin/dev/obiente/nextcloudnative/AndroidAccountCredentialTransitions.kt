package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun removeActiveAndroidAccountCredentialState(
    state: AndroidAccountCredentialState,
): AndroidAccountCredentialState = state.registry.activeAccountId?.let(state::remove) ?: state

internal fun NextcloudAccountRegistry?.asDurableRegistry(): DurableUploadAccountRegistry =
    this?.let { registry -> DurableUploadAccountRegistry.Available(registry.accounts) }
        ?: DurableUploadAccountRegistry.Unavailable

internal fun NextcloudAccountRegistry?.asAccountRetentionSnapshot(): AndroidAccountRetentionSnapshot =
    this?.let { registry -> AndroidAccountRetentionSnapshot.Available(registry.accounts) }
        ?: AndroidAccountRetentionSnapshot.Unavailable

internal suspend fun rollbackUnavailableAndroidAccountRemoval(
    active: Boolean = false,
    recovered: AndroidAccountCredentialState,
    persistRecovered: suspend (AndroidAccountCredentialState) -> Unit,
    clearCleanup: suspend () -> Unit,
) {
    if (!active) persistRecovered(recovered)
    clearCleanup()
}

internal suspend fun retryAndroidAccountRemovalCleanup(
    accountOwnedByRegistry: Boolean?,
    removeAccountOwnedWork: suspend () -> Unit,
    clearCleanup: suspend () -> Unit,
) {
    when (accountOwnedByRegistry) {
        true -> clearCleanup()
        false -> {
            removeAccountOwnedWork()
            clearCleanup()
        }
        null -> error("Account ownership is unavailable; pending cleanup cannot run safely.")
    }
}

internal fun androidAccountRemovalCleanupRetryFailure(failure: Exception) = IllegalStateException(
    "Previous account cleanup must finish before this account can be added again.",
    failure,
)

internal suspend fun retryAndroidAccountOwnedStateCleanup(
    session: NextcloudSession,
    pending: AndroidPendingAccountRemovalCleanup,
    retry: suspend (NextcloudSession, String, String?, String?) -> Unit,
) {
    retry(session, pending.workIdentity, pending.previewCacheIdentity, pending.durableMutationIdentity)
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

internal suspend fun removeUnavailableAndroidAccountCredentialData(
    accountIdentity: String,
    active: Boolean = false,
    prepareAccountRemoval: suspend () -> Unit,
    removeAccountOwnedWorkWithoutCredentials: suspend (String) -> Unit,
    persistRemoval: suspend () -> Unit,
    clearActiveAccount: suspend () -> Unit = persistRemoval,
    rollbackRemoval: suspend () -> Unit,
    completeCommittedCleanup: suspend () -> Unit = {},
    recordCommittedCleanupFailure: (Exception) -> Unit = {},
) {
    require(accountIdentity.isNotBlank())
    removeAndroidAccountCredentialData(
        active = active,
        prepareAccountRemoval = prepareAccountRemoval,
        removeQueuedUploads = { removeAccountOwnedWorkWithoutCredentials(accountIdentity) },
        clearActiveAccount = clearActiveAccount,
        rollbackActiveRemoval = rollbackRemoval,
        persistInactiveRemoval = persistRemoval,
        rollbackInactiveRemoval = rollbackRemoval,
        completeCommittedCleanup = completeCommittedCleanup,
        recordCommittedCleanupFailure = recordCommittedCleanupFailure,
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
