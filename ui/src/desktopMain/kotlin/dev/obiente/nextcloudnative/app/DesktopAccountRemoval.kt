package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException

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
        removeCredential = removeCredential,
    )
}

internal suspend fun removeDesktopAccountBeforeSyncPairCleanup(
    removeCredential: suspend () -> Boolean,
    removeSyncPairs: suspend () -> Unit,
    recordCleanupFailure: suspend (Exception) -> Unit,
): Boolean {
    val removed = removeCredential()
    if (!removed) return false
    try {
        removeSyncPairs()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        runCatching { recordCleanupFailure(failure) }
    }
    return true
}

internal suspend fun clearDesktopActiveAccountBeforeSyncPairCleanup(
    accountId: String?,
    commitRemoval: suspend () -> Unit,
    removeSyncPairs: suspend (String) -> Unit,
    recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
) {
    removeDesktopAccountBeforeSyncPairCleanup(
        removeCredential = {
            commitRemoval()
            true
        },
        removeSyncPairs = { accountId?.let { removeSyncPairs(it) } },
        recordCleanupFailure = { failure ->
            accountId?.let { recordDiagnostic(desktopAccountSyncPairCleanupFailureDiagnostic(it, failure)) }
        },
    )
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
