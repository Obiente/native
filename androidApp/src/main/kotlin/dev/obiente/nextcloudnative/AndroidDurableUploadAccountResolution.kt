package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DurableUploadState
import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import dev.obiente.nextcloudnative.app.NextcloudSession

internal sealed interface DurableUploadAccountResolution {
    data class Available(val session: NextcloudSession) : DurableUploadAccountResolution
    data object RegistryUnavailable : DurableUploadAccountResolution
    data object CredentialUnavailable : DurableUploadAccountResolution
    data object AccountUnavailable : DurableUploadAccountResolution
}

internal sealed interface DurableUploadAccountRegistry {
    data class Available(val accounts: List<NextcloudAccountRecord>) : DurableUploadAccountRegistry
    data object Unavailable : DurableUploadAccountRegistry
}

internal fun queuedDurableUploadsForAccount(
    jobs: List<AndroidDurableMultipartUploadJob>,
    accountId: String,
): List<AndroidDurableMultipartUploadJob> = jobs.filter { job ->
    job.accountId == accountId && job.state == DurableUploadState.Queued
}

internal fun resolveDurableUploadSession(
    expectedAccountId: String,
    registry: DurableUploadAccountRegistry,
    loadSession: (NextcloudAccountId) -> NextcloudSession?,
): DurableUploadAccountResolution {
    val accounts = when (registry) {
        is DurableUploadAccountRegistry.Available -> registry.accounts
        DurableUploadAccountRegistry.Unavailable -> return DurableUploadAccountResolution.RegistryUnavailable
    }
    val account = accounts.singleOrNull { record ->
        NextcloudDocumentIds.accountKey(record.serverUrl, record.loginName) == expectedAccountId
    } ?: return DurableUploadAccountResolution.AccountUnavailable
    val session = loadSession(account.id)
        ?.takeIf { loaded -> NextcloudDocumentIds.accountKey(loaded) == expectedAccountId }
        ?: return DurableUploadAccountResolution.CredentialUnavailable
    return DurableUploadAccountResolution.Available(session)
}

internal fun resolveDurableUploadSessionWithRegistryRecovery(
    expectedAccountId: String,
    readRegistry: () -> DurableUploadAccountRegistry,
    recoverRegistry: () -> NextcloudSession?,
    loadSession: (NextcloudAccountId) -> NextcloudSession?,
): DurableUploadAccountResolution {
    val initial = readRegistry()
    val recoveryRequired = when (initial) {
        DurableUploadAccountRegistry.Unavailable -> true
        is DurableUploadAccountRegistry.Available -> initial.accounts.none { account ->
            NextcloudDocumentIds.accountKey(account.serverUrl, account.loginName) == expectedAccountId
        }
    }
    if (!recoveryRequired) return resolveDurableUploadSession(expectedAccountId, initial, loadSession)
    val recoveredSession = recoverRegistry()
    if (
        recoveredSession != null &&
        NextcloudDocumentIds.accountKey(recoveredSession) == expectedAccountId
    ) {
        return DurableUploadAccountResolution.Available(recoveredSession)
    }
    return resolveDurableUploadSession(expectedAccountId, readRegistry(), loadSession)
}
