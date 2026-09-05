package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountRecord

internal sealed interface AndroidAccountRetentionSnapshot {
    data class Available(val accounts: List<NextcloudAccountRecord>) : AndroidAccountRetentionSnapshot

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
