package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.DynamicApiRequestCoalescer
import dev.obiente.nextcloudnative.app.AccountPrivateMemoryCleanup
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.durableMutationAccountScope
import dev.obiente.nextcloudnative.contracts.DynamicApiResponseCache
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class AndroidAccountOwnedStateCleanup(
    context: Context,
    private val fileReadCache: AndroidFileReadCache = AndroidFileReadCache(
        File(context.applicationContext.cacheDir, "files-read-v1"),
    ),
    private val virtualFileCache: AndroidVirtualFileCache = AndroidVirtualFileCache(context.applicationContext),
    private val clearPreviewAccount: (String) -> Unit = AndroidNativeMediaPreviewCache(
        File(context.applicationContext.cacheDir, "native-media-previews-v1"),
    )::clearAccount,
    private val dynamicApiState: AndroidDynamicApiProcessState = androidDynamicApiProcessState(
        File(context.applicationContext.cacheDir, "dynamic-api-v1"),
    ),
) {
    private val appContext = context.applicationContext
    private val fileOffline = AndroidFileOfflineAccountCleanup(appContext)
    private val incomingShares = AndroidIncomingShareAccountCleanup(appContext)
    private val durableUploads = AndroidDurableUploadAccountCleanup(appContext)
    private val mediaBackupLedger = AndroidMediaBackupAccountCleanup(appContext)
    private val mutationRecovery = AndroidAccountMutationRecoveryCleanup(appContext)
    private val deckCardDrafts = AndroidDeckCardDraftStore(appContext)

    suspend fun remove(session: NextcloudSession) {
        val accountIdentity = NextcloudDocumentIds.accountKey(session)
        val cacheIdentity = NextcloudDocumentIds.cacheAccountId(session)
        runAndroidAccountOwnedStateCleanups(
            cacheIdentity,
            clearPreviewAccount,
            listOf(
                { fenceAndroidDynamicApiStateForRemoval(cacheIdentity, dynamicApiState.coalescer, dynamicApiState.cache) },
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(session) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { mediaBackupLedger.removeForAccount(accountIdentity) },
                { deckCardDrafts.removeAccount(session.accountId.storageKey, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
                { mutationRecovery.clearDurableRecoveries(durableMutationAccountScope(session)) },
                { mutationRecovery.clearPendingDynamicMutations(cacheIdentity) },
                { AccountPrivateMemoryCleanup.removeAccount(session.accountId.storageKey) },
            ),
        )
    }

    suspend fun retry(
        session: NextcloudSession,
        accountIdentity: String,
        previewCacheIdentity: String?,
        durableMutationIdentity: String?,
    ) {
        runAndroidAccountOwnedStateCleanups(
            previewCacheIdentity,
            clearPreviewAccount,
            listOf(
                {
                    previewCacheIdentity?.let { identity ->
                        fenceAndroidDynamicApiStateForRemoval(identity, dynamicApiState.coalescer, dynamicApiState.cache)
                    }
                },
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(accountIdentity, session) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { mediaBackupLedger.removeForAccount(accountIdentity) },
                { deckCardDrafts.removeAccount(session.accountId.storageKey, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
                { durableMutationIdentity?.let(mutationRecovery::clearDurableRecoveries) },
                { previewCacheIdentity?.let(mutationRecovery::clearPendingDynamicMutations) },
                { AccountPrivateMemoryCleanup.removeAccount(session.accountId.storageKey) },
            ),
        )
    }

    suspend fun retryWithoutCredentials(
        accountStorageKey: String,
        accountIdentity: String,
        previewCacheIdentity: String? = null,
        durableMutationIdentity: String? = null,
    ) {
        runAndroidAccountOwnedStateCleanups(
            previewCacheIdentity,
            clearPreviewAccount,
            listOf(
                {
                    previewCacheIdentity?.let { identity ->
                        fenceAndroidDynamicApiStateForRemoval(identity, dynamicApiState.coalescer, dynamicApiState.cache)
                    }
                },
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(accountIdentity) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { mediaBackupLedger.removeForAccount(accountIdentity) },
                { deckCardDrafts.removeAccount(accountStorageKey, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
                { durableMutationIdentity?.let(mutationRecovery::clearDurableRecoveries) },
                { previewCacheIdentity?.let(mutationRecovery::clearPendingDynamicMutations) },
                { AccountPrivateMemoryCleanup.removeAccount(accountStorageKey) },
            ),
        )
    }
}

internal suspend fun <T> clearAndroidDynamicApiState(
    accountIdentity: String,
    coalescer: DynamicApiRequestCoalescer<T>,
    cache: DynamicApiResponseCache,
) = coalescer.fenceAccount(accountIdentity) { cache.invalidateAccount(accountIdentity) }

internal suspend fun <T> fenceAndroidDynamicApiStateForRemoval(
    accountIdentity: String,
    coalescer: DynamicApiRequestCoalescer<T>,
    cache: DynamicApiResponseCache,
) = withContext(NonCancellable) {
    clearAndroidDynamicApiState(accountIdentity, coalescer, cache)
}

internal suspend fun runAndroidAccountOwnedStateCleanups(
    previewCacheIdentity: String?,
    clearPreviewAccount: (String) -> Unit,
    cleanups: List<suspend () -> Unit>,
) {
    val previewCleanup: suspend () -> Unit = {
        previewCacheIdentity?.let(clearPreviewAccount)
    }
    runAndroidAccountRemovalCleanups(cleanups + previewCleanup)
}
