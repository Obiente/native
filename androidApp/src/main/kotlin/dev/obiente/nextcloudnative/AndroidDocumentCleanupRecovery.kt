package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import dev.obiente.nextcloudnative.app.NextcloudSession

internal suspend fun retryAndroidDocumentCleanupBeforeActivation(
    context: Context,
    session: NextcloudSession,
    journal: AndroidAccountRemovalCleanupJournal,
    loadAccounts: () -> List<NextcloudAccountRecord>?,
    prepareRemoval: suspend (NextcloudSession) -> AndroidDocumentProviderIncarnationRetirement,
    retryCleanup: suspend (NextcloudSession, String, String?, String?, String?) -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    var retirement: AndroidDocumentProviderIncarnationRetirement? = null
    retryAndroidCleanupBeforeActivation(
        session, journal, loadAccounts,
        prepareRemoval = { retirement = prepareRemoval(it) },
        retryCleanup = retryCleanup,
        recordFailure = recordFailure,
        completeRecovery = {
            // Fence recovery can retain the account registry entry. Its old grants
            // still need a retired incarnation before a fresh one can be published.
            renewRecoveredAndroidDocumentIncarnation(
                AndroidDocumentProviderIncarnationStore(context), requireNotNull(retirement),
            )
        },
    )
}

internal fun renewRecoveredAndroidDocumentIncarnation(
    store: AndroidDocumentProviderIncarnationStore,
    retirement: AndroidDocumentProviderIncarnationRetirement,
) {
    store.complete(retirement)
    store.prepareForAccountSave(retirement.accountIdentity, accountAlreadyStored = false)
}
