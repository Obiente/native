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

internal fun markAndroidCleanupAccountReviewed(
    encoded: Set<String>,
    accountStorageKey: String,
    retainedAccountStorageKeys: Set<String>?,
): Set<String> {
    require(ACCOUNT_KEY.matches(accountStorageKey))
    requireAndroidAccountRemovalCleanupJournalAllowsActivation(restoreAndroidPendingAccountRemovalCleanups(encoded))
    val retained = encoded.filterTo(linkedSetOf()) { !it.startsWith(REVIEWED_PREFIX) }
    val reviewed = androidCleanupReviewedAccounts(encoded).let { existing ->
        retainedAccountStorageKeys?.let(existing::intersect) ?: existing
    }
    return retained + (reviewed + accountStorageKey).map { REVIEWED_PREFIX + it }
}

internal suspend fun retryAndroidCleanupBeforeActivation(
    session: NextcloudSession,
    journal: AndroidAccountRemovalCleanupJournal,
    loadAccounts: () -> List<NextcloudAccountRecord>?,
    prepareRemoval: suspend (NextcloudSession) -> Unit,
    retryCleanup: suspend (NextcloudSession, String, String?, String?, String?) -> Unit,
    recordFailure: (Exception) -> Unit,
    completeRecovery: suspend (NextcloudSession) -> Unit = {},
) {
    val snapshot = journal.snapshot()
    requireAndroidAccountRemovalCleanupJournalAllowsActivation(snapshot)
    val requiresRecovery = snapshot.recoveryFence && session.accountId.storageKey !in snapshot.reviewedAccounts
    try {
        if (requiresRecovery) {
            val retainedAccounts = loadAccounts()?.mapTo(linkedSetOf()) { it.id.storageKey }
            val cleanups = listOfNotNull(
                pendingAndroidAccountRemovalCleanupForSession(session, snapshot.cleanups),
                pendingAndroidAccountRemovalCleanup(session),
            ).distinct()
            withAndroidAccountRemovalLease(session, additionalAccountIdentities = cleanups.map { it.workIdentity }) {
                // Retain old ownership until all scopes and their document incarnation are retired.
                journal.prepare(cleanups.first())
                prepareRemoval(session)
                cleanups.forEach { pending ->
                    journal.prepare(pending)
                    retryAndroidAccountOwnedStateCleanup(session, pending, retryCleanup)
                }
                completeRecovery(session)
                journal.clear(session.accountId.storageKey)
            }
            journal.markReviewed(session.accountId.storageKey, retainedAccounts)
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
