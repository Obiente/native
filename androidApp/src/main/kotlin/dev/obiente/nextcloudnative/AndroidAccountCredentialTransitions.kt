package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun removeActiveAndroidAccountCredentialState(
    state: AndroidAccountCredentialState,
): AndroidAccountCredentialState = state.registry.activeAccountId?.let(state::remove) ?: state

internal suspend fun replaceAndroidActiveStateWithAccountLeases(
    replacement: AndroidAccountCredentialState,
    previousSession: NextcloudSession?,
    replacedSession: NextcloudSession?,
    suspectEncrypted: String?,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    coordinator: AndroidFileRangeSessionCoordinator = ANDROID_FILE_RANGE_SESSION_COORDINATOR,
    replace: suspend (AndroidAccountCredentialState, NextcloudSession?, String?, NextcloudSession?) -> Unit,
) {
    val replacementSession = requireNotNull(replacement.activeSession)
    val accountIdentities = listOfNotNull(previousSession, replacementSession, replacedSession)
        .flatMap(::androidAccountOperationIdentities)
        .distinct()
        .sorted()
    guard.withAccounts(accountIdentities) {
        quiesceAndroidFileRangesBeforeCredentialReplacement(replacedSession, replacementSession, coordinator)
        replace(replacement, previousSession, suspectEncrypted, replacedSession)
    }
}

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
    retry: suspend (NextcloudSession, String, String?, String?, String?) -> Unit,
) {
    retry(
        session,
        pending.workIdentity,
        pending.previewCacheIdentity,
        pending.durableMutationIdentity,
        pending.legacyAccountScopeDigest,
    )
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

internal fun notifyAndroidDocumentRootsAfterCommittedTransition(
    notify: () -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    try {
        notify()
    } catch (failure: Exception) {
        recordFailure(failure)
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
    onInactiveRemovalCommitted: () -> Unit = {},
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
    notifyAndroidDocumentRootsAfterCommittedTransition(
        onInactiveRemovalCommitted,
        recordCommittedCleanupFailure,
    )
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
    onInactiveRemovalCommitted: () -> Unit = {},
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
        onInactiveRemovalCommitted = onInactiveRemovalCommitted,
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
