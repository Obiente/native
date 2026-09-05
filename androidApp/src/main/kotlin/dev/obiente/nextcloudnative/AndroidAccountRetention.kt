package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord

internal sealed interface AndroidAccountRetentionSnapshot {
    data class Available(
        val accounts: List<NextcloudAccountRecord>,
        val activeAccountId: NextcloudAccountId? = null,
    ) : AndroidAccountRetentionSnapshot

    data object Unavailable : AndroidAccountRetentionSnapshot
}

internal fun AndroidAccountRetentionSnapshot.accountsOrEmpty(): List<NextcloudAccountRecord> =
    (this as? AndroidAccountRetentionSnapshot.Available)?.accounts.orEmpty()

internal fun androidAccountIdentityIsRetained(
    accountIdentity: String,
    retainedAccounts: List<NextcloudAccountRecord>,
): Boolean = retainedAccounts.any { account ->
    NextcloudDocumentIds.accountKey(account.serverUrl, account.loginName) == accountIdentity
}

internal fun shouldRetryIncomingShareForMissingSession(
    accountIdentity: String,
    snapshot: AndroidAccountRetentionSnapshot,
): Boolean = when (snapshot) {
    is AndroidAccountRetentionSnapshot.Available ->
        androidAccountIdentityIsRetained(accountIdentity, snapshot.accounts)
    AndroidAccountRetentionSnapshot.Unavailable -> true
}

internal fun AndroidAccountRetentionSnapshot.expectedAccountState(
    accountIdentity: String,
): AndroidExpectedAccountState = when (this) {
    is AndroidAccountRetentionSnapshot.Available -> {
        val expected = accounts.firstOrNull { account ->
            NextcloudDocumentIds.accountKey(account.serverUrl, account.loginName) == accountIdentity
        }
        when {
            expected == null -> AndroidExpectedAccountState.Absent
            expected.id == activeAccountId -> AndroidExpectedAccountState.Active
            else -> AndroidExpectedAccountState.Inactive
        }
    }
    AndroidAccountRetentionSnapshot.Unavailable -> AndroidExpectedAccountState.Unknown
}

internal enum class AndroidExpectedAccountState {
    Active,
    Inactive,
    Absent,
    Unknown,
}

internal enum class DurableUploadAccountMismatchOutcome {
    RetryAccountRecovery,
    DeferAccountActivation,
    AccountUnavailable,
}

internal fun durableUploadAccountMismatchOutcome(
    expectedAccountId: String,
    accountSnapshot: AndroidAccountRetentionSnapshot,
): DurableUploadAccountMismatchOutcome = when (accountSnapshot.expectedAccountState(expectedAccountId)) {
    AndroidExpectedAccountState.Active,
    AndroidExpectedAccountState.Unknown,
    -> DurableUploadAccountMismatchOutcome.RetryAccountRecovery
    AndroidExpectedAccountState.Inactive -> DurableUploadAccountMismatchOutcome.DeferAccountActivation
    AndroidExpectedAccountState.Absent -> DurableUploadAccountMismatchOutcome.AccountUnavailable
}

internal fun shouldRetryAndroidOfflineJobForMissingSession(
    expectedAccountId: String,
    snapshot: AndroidAccountRetentionSnapshot,
): Boolean = snapshot.expectedAccountState(expectedAccountId) != AndroidExpectedAccountState.Absent
