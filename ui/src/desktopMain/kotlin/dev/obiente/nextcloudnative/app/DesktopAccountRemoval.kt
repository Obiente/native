package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException

internal enum class DesktopAccountSyncPairCleanupPhase {
    Prepared,
    Committed,
}

internal data class DesktopAccountSyncPairCleanup(
    val accountId: String,
    val phase: DesktopAccountSyncPairCleanupPhase,
)

internal class DesktopAccountSyncPairCleanupJournal(
    private val preferences: Preferences,
) {
    fun prepare(accountId: String) = persist(accountId, DesktopAccountSyncPairCleanupPhase.Prepared)

    fun commit(accountId: String) = persist(accountId, DesktopAccountSyncPairCleanupPhase.Committed)

    fun clear(accountId: String) {
        validateDesktopSyncPairCleanupAccountId(accountId)
        preferences.remove(cleanupKey(accountId))
        preferences.flush()
    }

    fun pending(): List<DesktopAccountSyncPairCleanup> = preferences.keys()
        .asSequence()
        .filter { key -> key.startsWith(KEY_PREFIX) }
        .map { key ->
            val accountId = key.removePrefix(KEY_PREFIX)
            validateDesktopSyncPairCleanupAccountId(accountId)
            val phase = when (preferences.get(key, null)) {
                PREPARED -> DesktopAccountSyncPairCleanupPhase.Prepared
                COMMITTED -> DesktopAccountSyncPairCleanupPhase.Committed
                else -> error("The desktop account sync cleanup journal is invalid.")
            }
            DesktopAccountSyncPairCleanup(accountId, phase)
        }
        .toList()
        .also { cleanups ->
            check(cleanups.size <= MAX_LOCAL_ACCOUNTS) {
                "The desktop account sync cleanup journal is too large."
            }
        }

    private fun persist(accountId: String, phase: DesktopAccountSyncPairCleanupPhase) {
        validateDesktopSyncPairCleanupAccountId(accountId)
        val pending = pending()
        check(pending.any { cleanup -> cleanup.accountId == accountId } || pending.size < MAX_LOCAL_ACCOUNTS) {
            "The desktop account sync cleanup journal is too large."
        }
        preferences.put(
            cleanupKey(accountId),
            if (phase == DesktopAccountSyncPairCleanupPhase.Prepared) PREPARED else COMMITTED,
        )
        preferences.flush()
    }

    private fun cleanupKey(accountId: String): String = "$KEY_PREFIX$accountId".also { key ->
        check(key.length <= Preferences.MAX_KEY_LENGTH)
    }

    private companion object {
        const val KEY_PREFIX = "fsac."
        const val PREPARED = "prepared"
        const val COMMITTED = "committed"
    }
}

private fun validateDesktopSyncPairCleanupAccountId(accountId: String) {
    require(accountId.length == 64 && accountId.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }) { "The desktop account sync cleanup identity is invalid." }
}

internal fun requireDesktopAccountRemovalReady(accountId: String, linuxDesktop: Boolean) {
    if (linuxDesktop) {
        requireDesktopAccountRemovalWritebacksResolved(
            defaultDesktopLinuxWritebackStore(accountId).pendingWritebacks().size,
        )
    }
}

internal fun removeDesktopAccountCredential(
    preferences: Preferences,
    providerAccountId: String?,
    credentialStillExists: () -> Boolean,
    removeCredential: () -> Boolean,
): Boolean {
    val providerKey = providerAccountId?.let(::virtualFileProviderPreferenceKey)
    val providerWasEnabled = providerKey?.let { key -> preferences.getBoolean(key, false) } == true
    return removeDesktopCredentialWithoutProviderReactivation(
        providerWasEnabled = providerWasEnabled,
        clearProviderPreference = {
            providerKey?.let(preferences::remove)
            preferences.flush()
        },
        restoreProviderPreference = { enabled ->
            providerKey?.let { key ->
                if (enabled) preferences.putBoolean(key, true) else preferences.remove(key)
            }
            preferences.flush()
        },
        removalCommitted = { !credentialStillExists() },
        removeCredential = removeCredential,
    )
}

internal suspend fun removeDesktopAccountBeforeSyncPairCleanup(
    accountId: String,
    prepareCleanup: suspend (String) -> Unit,
    commitCleanup: suspend (String) -> Unit,
    clearCleanup: suspend (String) -> Unit,
    accountStillExists: (String) -> Boolean,
    removeCredential: suspend () -> Boolean,
    removeSyncPairs: suspend () -> Unit,
    recordCleanupFailure: suspend (Exception) -> Unit,
): Boolean {
    prepareCleanup(accountId)
    val removed = try {
        removeCredential()
    } catch (failure: Throwable) {
        runCatching {
            if (accountStillExists(accountId)) clearCleanup(accountId) else commitCleanup(accountId)
        }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    if (!removed) {
        clearCleanup(accountId)
        return false
    }
    try {
        commitCleanup(accountId)
        removeSyncPairs()
        clearCleanup(accountId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        runCatching { recordCleanupFailure(failure) }
    }
    return true
}

internal suspend fun clearDesktopActiveAccountBeforeSyncPairCleanup(
    accountId: String?,
    cleanupJournal: DesktopAccountSyncPairCleanupJournal,
    accountStillExists: (String) -> Boolean,
    commitRemoval: suspend () -> Unit,
    removeSyncPairs: suspend (String) -> Unit,
    recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
) {
    if (accountId == null) {
        commitRemoval()
        return
    }
    removeDesktopAccountBeforeSyncPairCleanup(
        accountId = accountId,
        prepareCleanup = cleanupJournal::prepare,
        commitCleanup = cleanupJournal::commit,
        clearCleanup = cleanupJournal::clear,
        accountStillExists = accountStillExists,
        removeCredential = {
            commitRemoval()
            true
        },
        removeSyncPairs = { removeSyncPairs(accountId) },
        recordCleanupFailure = { failure ->
            recordDiagnostic(desktopAccountSyncPairCleanupFailureDiagnostic(accountId, failure))
        },
    )
}

internal suspend fun retryDesktopAccountSyncPairCleanup(
    cleanup: DesktopAccountSyncPairCleanup,
    accountStillExists: (String) -> Boolean,
    removeSyncPairs: suspend (String) -> Unit,
    clearCleanup: suspend (String) -> Unit,
) {
    if (cleanup.phase == DesktopAccountSyncPairCleanupPhase.Prepared && accountStillExists(cleanup.accountId)) {
        clearCleanup(cleanup.accountId)
        return
    }
    removeSyncPairs(cleanup.accountId)
    clearCleanup(cleanup.accountId)
}

internal suspend fun retryPendingDesktopAccountSyncPairCleanups(
    cleanupJournal: DesktopAccountSyncPairCleanupJournal,
    accountStillExists: (String) -> Boolean,
    removeSyncPairs: suspend (String) -> Unit,
    recordCleanupFailure: (String, Exception) -> Unit,
) {
    cleanupJournal.pending().forEach { cleanup ->
        try {
            retryDesktopAccountSyncPairCleanup(
                cleanup,
                accountStillExists,
                removeSyncPairs,
                cleanupJournal::clear,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            runCatching { recordCleanupFailure(cleanup.accountId, failure) }
        }
    }
}

internal suspend fun recoverDesktopBackgroundAccountSyncPairCleanups(
    retry: suspend () -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    try {
        retry()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        runCatching { recordFailure(failure) }
    }
}

internal fun desktopAccountSyncPairCleanupFailureDiagnostic(accountId: String, failure: Exception) =
    SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Error,
        component = SupportDiagnosticComponent.Sync,
        operation = "account.remove-sync-cleanup",
        outcome = "failed",
        fields = desktopAccountDiagnosticFields(accountId),
        exception = failure.toSupportDiagnosticExceptionDraft(),
    )

internal fun desktopAccountSyncPairCleanupJournalFailureDiagnostic(failure: Exception) =
    SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Error,
        component = SupportDiagnosticComponent.Sync,
        operation = "account.remove-sync-cleanup-journal",
        outcome = "failed",
        exception = failure.toSupportDiagnosticExceptionDraft(),
    )
