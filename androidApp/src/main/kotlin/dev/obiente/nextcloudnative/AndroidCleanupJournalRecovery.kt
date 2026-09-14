package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CancellationException

internal const val ANDROID_CLEANUP_RECOVERY_FENCE = "recovery-fence-v1"
internal const val ANDROID_CLEANUP_QUARANTINE_KEY = "account-removal-cleanup-quarantine-v1"
private const val REVIEWED_PREFIX = "recovery-reviewed-v1:"
private val ACCOUNT_KEY = Regex("[0-9a-f]{64}")

internal fun isAndroidCleanupRecoveryMetadata(entry: String): Boolean =
    entry == ANDROID_CLEANUP_RECOVERY_FENCE ||
        (entry.startsWith(REVIEWED_PREFIX) && ACCOUNT_KEY.matches(entry.removePrefix(REVIEWED_PREFIX)))

internal fun androidCleanupReviewedAccounts(encoded: Set<String>): Set<String> = encoded
    .filter { it.startsWith(REVIEWED_PREFIX) && isAndroidCleanupRecoveryMetadata(it) }
    .mapTo(linkedSetOf()) { it.removePrefix(REVIEWED_PREFIX) }

internal data class AndroidCleanupJournalQuarantine(val active: Set<String>, val quarantined: Set<String>)

internal fun quarantineAndroidCleanupJournal(encoded: Set<String>): AndroidCleanupJournalQuarantine? {
    val malformed = encoded.filterTo(linkedSetOf()) {
        !isAndroidCleanupRecoveryMetadata(it) && decodeAndroidPendingAccountRemovalCleanup(it) == null
    }
    if (malformed.isEmpty()) return null
    // A new corruption invalidates every earlier recovery decision. Retain original evidence privately.
    val active = encoded.filterTo(linkedSetOf()) { decodeAndroidPendingAccountRemovalCleanup(it) != null }
    return AndroidCleanupJournalQuarantine(active + ANDROID_CLEANUP_RECOVERY_FENCE, malformed)
}

internal fun markAndroidCleanupAccountReviewed(encoded: Set<String>, accountStorageKey: String): Set<String> {
    require(ACCOUNT_KEY.matches(accountStorageKey))
    requireAndroidAccountRemovalCleanupJournalAllowsActivation(restoreAndroidPendingAccountRemovalCleanups(encoded))
    val retained = encoded.filterTo(linkedSetOf()) { !it.startsWith(REVIEWED_PREFIX) }
    val reviewed = (androidCleanupReviewedAccounts(encoded) - accountStorageKey).toList().takeLast(63)
    return retained + (reviewed + accountStorageKey).map { REVIEWED_PREFIX + it }
}

internal suspend fun retryAndroidCleanupBeforeActivation(
    session: NextcloudSession,
    journal: AndroidAccountRemovalCleanupJournal,
    loadAccounts: () -> List<NextcloudAccountRecord>?,
    prepareRemoval: suspend (NextcloudSession) -> Unit,
    retryCleanup: suspend (NextcloudSession, String, String?, String?, String?) -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    val snapshot = journal.snapshot()
    requireAndroidAccountRemovalCleanupJournalAllowsActivation(snapshot)
    val requiresRecovery = snapshot.recoveryFence && session.accountId.storageKey !in snapshot.reviewedAccounts
    try {
        if (requiresRecovery) {
            val pending = pendingAndroidAccountRemovalCleanup(session)
            withAndroidAccountRemovalLease(pending.workIdentity) {
                journal.prepare(pending)
                prepareRemoval(session)
                retryAndroidAccountOwnedStateCleanup(session, pending, retryCleanup)
                journal.clear(pending.accountStorageKey)
                journal.markReviewed(pending.accountStorageKey)
            }
        } else {
            val pending = pendingAndroidAccountRemovalCleanupForSession(session, snapshot.cleanups) ?: return
            retryAndroidAccountRemovalCleanup(
                accountOwnedByRegistry = androidAccountRemovalCleanupOwnedByRegistry(pending, loadAccounts()),
                removeAccountOwnedWork = { retryAndroidAccountOwnedStateCleanup(session, pending, retryCleanup) },
                clearCleanup = { journal.clear(pending.accountStorageKey) },
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        recordFailure(failure)
        throw androidAccountRemovalCleanupRetryFailure(failure)
    }
}
