package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.runBlocking

/** Never wait for a second account while account removal may already own another lease. */
internal fun <Result> withAndroidCrossAccountProviderRecovery(
    rootDocumentId: String,
    sameProfile: Boolean,
    resolveSession: () -> NextcloudSession?,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    matchesRoot: (NextcloudSession) -> Boolean = { androidRootBoundProviderRecoverySession(rootDocumentId, it) != null },
    action: (NextcloudSession) -> Result,
): Result {
    check(sameProfile) { "Cross-profile recovery needs authoritative provider access." }
    val session = checkNotNull(resolveSession()) { "The recovery account is unavailable." }
    check(matchesRoot(session)) { "The recovery account changed." }
    return runBlocking {
        guard.tryWithAccounts(androidAccountOperationIdentities(session), unavailable = {
            error("The recovery account is busy. Retry after its active operation finishes.")
        }) {
            check(resolveSession() == session && matchesRoot(session)) { "The recovery account changed." }
            action(session)
        }
    }
}
