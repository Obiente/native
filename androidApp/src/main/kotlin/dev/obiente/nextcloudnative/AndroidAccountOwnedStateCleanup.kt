package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.AccountPrivateMemoryCleanup
import dev.obiente.nextcloudnative.app.DynamicApiRequestCoalescer
import dev.obiente.nextcloudnative.app.DynamicNativeMemoryAccountLifecycle
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.durableMutationAccountScope
import dev.obiente.nextcloudnative.app.removeAndroidHomeWorkspaceAccountPreferences
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
    private val dynamicDiscoveryCache: AndroidDynamicDiscoveryCache = AndroidDynamicDiscoveryCacheCoordinator.get(
        File(context.applicationContext.filesDir, "contracts/discoveries-v1"),
    ),
    private val removeSupportAccount: suspend (String) -> Unit,
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
                {
                    fenceAndroidDynamicApiStateForRemoval(
                        cacheIdentity,
                        dynamicApiState.coalescer,
                        dynamicApiState.cache,
                        session.accountId.storageKey,
                    )
                },
                { dynamicDiscoveryCache.retireAccount(session.accountId.storageKey, cacheIdentity) },
                { removeSupportAccount(accountIdentity) },
                {
                    removeAndroidHomeWorkspaceAccountPreferences(
                        appContext,
                        session.accountId.storageKey,
                        legacyAndroidAccountPersistenceScopeDigest(session),
                    )
                },
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(session) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { removeLegacyAndroidFileSyncStaging(File(appContext.cacheDir, "file-sync-staging")) },
                { removeAndroidFileSyncAccountStaging(File(appContext.cacheDir, "file-sync-staging"), accountIdentity) },
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
        legacyAccountScopeDigest: String?,
    ) {
        runAndroidAccountOwnedStateCleanups(
            previewCacheIdentity,
            clearPreviewAccount,
            listOf(
                {
                    if (previewCacheIdentity == null) {
                        DynamicNativeMemoryAccountLifecycle.retireAccount(session.accountId.storageKey)
                    } else {
                        fenceAndroidDynamicApiStateForRemoval(
                            previewCacheIdentity,
                            dynamicApiState.coalescer,
                            dynamicApiState.cache,
                            session.accountId.storageKey,
                        )
                    }
                },
                { dynamicDiscoveryCache.retireAccount(session.accountId.storageKey, previewCacheIdentity) },
                { removeSupportAccount(accountIdentity) },
                {
                    removeAndroidHomeWorkspaceAccountPreferences(
                        appContext,
                        session.accountId.storageKey,
                        legacyAccountScopeDigest,
                    )
                },
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(accountIdentity, session) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { removeLegacyAndroidFileSyncStaging(File(appContext.cacheDir, "file-sync-staging")) },
                { removeAndroidFileSyncAccountStaging(File(appContext.cacheDir, "file-sync-staging"), accountIdentity) },
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
        legacyAccountScopeDigest: String? = null,
    ) {
        runAndroidAccountOwnedStateCleanups(
            previewCacheIdentity,
            clearPreviewAccount,
            listOf(
                {
                    if (previewCacheIdentity == null) {
                        DynamicNativeMemoryAccountLifecycle.retireAccount(accountStorageKey)
                    } else {
                        fenceAndroidDynamicApiStateForRemoval(
                            previewCacheIdentity,
                            dynamicApiState.coalescer,
                            dynamicApiState.cache,
                            accountStorageKey,
                        )
                    }
                },
                { dynamicDiscoveryCache.retireAccount(accountStorageKey, previewCacheIdentity) },
                { removeSupportAccount(accountIdentity) },
                {
                    removeAndroidHomeWorkspaceAccountPreferences(
                        appContext,
                        accountStorageKey,
                        legacyAccountScopeDigest,
                    )
                },
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(accountIdentity) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { removeLegacyAndroidFileSyncStaging(File(appContext.cacheDir, "file-sync-staging")) },
                { removeAndroidFileSyncAccountStaging(File(appContext.cacheDir, "file-sync-staging"), accountIdentity) },
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
    accountStorageKey: String? = null,
    retireMemoryAccount: (String) -> Unit = DynamicNativeMemoryAccountLifecycle::retireAccount,
) = coalescer.fenceAccount(accountIdentity) {
    accountStorageKey?.let(retireMemoryAccount)
    cache.invalidateAccount(accountIdentity)
}

internal suspend fun <T> fenceAndroidDynamicApiStateForRemoval(
    accountIdentity: String,
    coalescer: DynamicApiRequestCoalescer<T>,
    cache: DynamicApiResponseCache,
    accountStorageKey: String? = null,
    retireMemoryAccount: (String) -> Unit = DynamicNativeMemoryAccountLifecycle::retireAccount,
) = withContext(NonCancellable) {
    clearAndroidDynamicApiState(accountIdentity, coalescer, cache, accountStorageKey, retireMemoryAccount)
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
