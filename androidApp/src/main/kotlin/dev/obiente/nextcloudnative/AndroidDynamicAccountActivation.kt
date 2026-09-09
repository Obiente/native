package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DynamicApiRequestCoalescer
import dev.obiente.nextcloudnative.app.AccountPrivateMemoryLifecycle
import dev.obiente.nextcloudnative.app.NextcloudApiResponse
import dev.obiente.nextcloudnative.app.NextcloudSession

internal class AndroidDynamicAccountActivation(
    private val coalescer: DynamicApiRequestCoalescer<NextcloudApiResponse>,
    private val activateMemory: (String) -> Unit = AccountPrivateMemoryLifecycle::activateAccount,
) {
    suspend fun afterCredentialSave(persistedSession: NextcloudSession) {
        activateMemory(persistedSession.accountId.storageKey)
        coalescer.activateAccount(NextcloudDocumentIds.cacheAccountId(persistedSession))
    }
}
