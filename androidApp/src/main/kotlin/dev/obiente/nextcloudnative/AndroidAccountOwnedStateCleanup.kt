package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.DynamicApiRequestCoalescer
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.durableMutationAccountScope
import dev.obiente.nextcloudnative.contracts.DynamicApiResponseCache
import java.io.File

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
    private val mutationRecovery = AndroidAccountMutationRecoveryCleanup(appContext)

    suspend fun remove(session: NextcloudSession) {
        val accountIdentity = NextcloudDocumentIds.accountKey(session)
        val cacheIdentity = NextcloudDocumentIds.cacheAccountId(session)
        runAndroidAccountOwnedStateCleanups(
            cacheIdentity,
            clearPreviewAccount,
            listOf(
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(session) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
                { mutationRecovery.clearDurableRecoveries(durableMutationAccountScope(session)) },
                { clearDynamicApiState(cacheIdentity) },
                { mutationRecovery.clearPendingDynamicMutations(cacheIdentity) },
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
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(accountIdentity, session) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
                { durableMutationIdentity?.let(mutationRecovery::clearDurableRecoveries) },
                { previewCacheIdentity?.let { clearDynamicApiState(it) } },
                { previewCacheIdentity?.let(mutationRecovery::clearPendingDynamicMutations) },
            ),
        )
    }

    suspend fun retryWithoutCredentials(
        accountIdentity: String,
        previewCacheIdentity: String? = null,
        durableMutationIdentity: String? = null,
    ) {
        runAndroidAccountOwnedStateCleanups(
            previewCacheIdentity,
            clearPreviewAccount,
            listOf(
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(accountIdentity) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
                { durableMutationIdentity?.let(mutationRecovery::clearDurableRecoveries) },
                { previewCacheIdentity?.let { clearDynamicApiState(it) } },
                { previewCacheIdentity?.let(mutationRecovery::clearPendingDynamicMutations) },
            ),
        )
    }

    private suspend fun clearDynamicApiState(accountIdentity: String) =
        clearAndroidDynamicApiState(accountIdentity, dynamicApiState.coalescer, dynamicApiState.cache)
}

internal suspend fun <T> clearAndroidDynamicApiState(
    accountIdentity: String,
    coalescer: DynamicApiRequestCoalescer<T>,
    cache: DynamicApiResponseCache,
) = coalescer.fenceAccount(accountIdentity) { cache.invalidateAccount(accountIdentity) }

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
