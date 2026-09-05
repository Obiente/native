package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountRecord

internal fun androidAccountIdentityIsRetained(
    accountIdentity: String,
    retainedAccounts: List<NextcloudAccountRecord>,
): Boolean = retainedAccounts.any { account ->
    NextcloudDocumentIds.accountKey(account.serverUrl, account.loginName) == accountIdentity
}
