package dev.obiente.nextcloudnative

import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

internal fun NextcloudAccountRegistry?.asDurableRegistry(): DurableUploadAccountRegistry =
    this?.let { registry ->
        DurableUploadAccountRegistry.Available(
            accounts = registry.accounts,
            activeAccountId = registry.activeAccountId,
        )
    }
        ?: DurableUploadAccountRegistry.Unavailable

internal fun NextcloudAccountRegistry?.asAccountRetentionSnapshot(): AndroidAccountRetentionSnapshot =
    this?.let { registry ->
        AndroidAccountRetentionSnapshot.Available(
            accounts = registry.accounts,
            activeAccountId = registry.activeAccountId,
        )
    }
        ?: AndroidAccountRetentionSnapshot.Unavailable

internal fun SharedPreferences.durableUploadAccountResolutionAvailable(): Boolean =
    durableUploadAccountResolutionAvailable {
        getString(ANDROID_ACCOUNT_REGISTRY_KEY, null)
    }

internal fun durableUploadAccountResolutionAvailable(
    readRegistry: () -> String?,
): Boolean = try {
    androidCredentialFreeRegistryAllowsAccountResolution(readRegistry())
} catch (_: ClassCastException) {
    false
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
    var cancellation: CancellationException? = null
    try {
        resume()
    } catch (cancelled: CancellationException) {
        cancellation = cancelled
    } catch (_: Exception) {
        try {
            recordCommittedAndroidAccountDiagnostic(recordFailure)
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
        }
    }
    try {
        notifyDocumentRootsChanged()
    } catch (cancelled: CancellationException) {
        if (cancellation == null) cancellation = cancelled
    } catch (_: Exception) {
        // This observer cannot change the outcome of the persisted account selection.
    }
    cancellation?.let { throw it }
}

internal fun notifyAndroidDocumentRootsAfterCommittedTransition(
    notify: () -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    try {
        notify()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        recordCommittedAndroidAccountDiagnostic { recordFailure(failure) }
    }
}

internal inline fun recordCommittedAndroidAccountDiagnostic(record: () -> Unit) {
    try {
        record()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Diagnostic delivery cannot undo an already committed credential transition.
    }
}

internal suspend fun removeAndroidAccountCredentialData(
    active: Boolean,
    prepareAccountRemoval: suspend () -> Unit = {},
    removeQueuedUploads: suspend () -> Unit,
    clearActiveAccount: suspend (() -> Unit) -> Unit,
    rollbackActiveRemoval: suspend () -> Unit,
    persistInactiveRemoval: suspend (() -> Unit) -> Unit,
    rollbackInactiveRemoval: suspend () -> Unit,
    onActiveRemovalCommitted: () -> Unit = {},
    onInactiveRemovalCommitted: () -> Unit = {},
    completeCommittedCleanup: suspend () -> Unit = {},
    recordCommittedCleanupFailure: (Exception) -> Unit = {},
) {
    currentCoroutineContext().ensureActive()
    prepareAccountRemoval()
    val committed = AtomicBoolean(false)
    val markCommitted = { committed.set(true) }
    if (active) {
        try {
            currentCoroutineContext().ensureActive()
            clearActiveAccount(markCommitted)
        } catch (failure: Exception) {
            if (committed.get()) throw failure
            withContext(NonCancellable) {
                runCatching { rollbackActiveRemoval() }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
        notifyAndroidDocumentRootsAfterCommittedTransition(onActiveRemovalCommitted, recordCommittedCleanupFailure)
        finishCommittedAndroidAccountRemovalCleanup(
            removeQueuedUploads,
            completeCommittedCleanup,
            recordCommittedCleanupFailure,
        )
        return
    }

    try {
        currentCoroutineContext().ensureActive()
        persistInactiveRemoval(markCommitted)
    } catch (failure: Exception) {
        if (committed.get()) throw failure
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
    persistRemoval: suspend (() -> Unit) -> Unit,
    clearActiveAccount: suspend (() -> Unit) -> Unit = persistRemoval,
    rollbackRemoval: suspend () -> Unit,
    onActiveRemovalCommitted: () -> Unit = {},
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
        onActiveRemovalCommitted = onActiveRemovalCommitted,
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
        recordCommittedAndroidAccountDiagnostic { recordFailure(failure) }
    }
}

internal suspend fun removeRecoveredAndroidAccountCredentialData(
    prepareAccountRemoval: suspend () -> Unit = {},
    removeQueuedUploads: suspend () -> Unit,
    clearRecoveredAccount: suspend (() -> Unit) -> Unit,
    rollbackRecoveredAccount: suspend () -> Unit,
    onRemovalCommitted: () -> Unit = {},
    completeCommittedCleanup: suspend () -> Unit = {},
    recordCommittedCleanupFailure: (Exception) -> Unit = {},
) = removeAndroidAccountCredentialData(
    active = true,
    prepareAccountRemoval = prepareAccountRemoval,
    removeQueuedUploads = removeQueuedUploads,
    clearActiveAccount = clearRecoveredAccount,
    onActiveRemovalCommitted = onRemovalCommitted,
    rollbackActiveRemoval = rollbackRecoveredAccount,
    persistInactiveRemoval = {},
    rollbackInactiveRemoval = {},
    completeCommittedCleanup = completeCommittedCleanup,
    recordCommittedCleanupFailure = recordCommittedCleanupFailure,
)
