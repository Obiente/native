package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.AccountPrivateMemoryLifecycle
import dev.obiente.nextcloudnative.app.DynamicApiRequestCoalescer
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
        AndroidFileSyncRootAcquisitionGenerations.retireCanonical(session.accountId.storageKey)
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
                { dynamicDiscoveryCache.retireAccount(session.accountId.storageKey, cacheIdentity, accountIdentity) },
                {
                    removeAndroidHomeWorkspaceAccountPreferences(
                        appContext,
                        session.accountId.storageKey,
                        legacyAndroidAccountPersistenceScopeDigest(session),
                    )
                },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(session) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncBeforeGrantRevocation(
                    { retireAndroidFileSyncAccountPairs(appContext, accountIdentity, session) },
                    { removeAndroidAccountDiagnosticsAndGrants(appContext, session.accountId.storageKey, accountIdentity, removeSupportAccount) },
                ) },
                { removeLegacyAndroidFileSyncStaging(File(appContext.cacheDir, "file-sync-staging")) },
                { removeAndroidFileSyncAccountStaging(File(appContext.cacheDir, "file-sync-staging"), accountIdentity) },
                { mediaBackupLedger.removeForAccount(accountIdentity) },
                { deckCardDrafts.removeAccount(session.accountId.storageKey, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
                { mutationRecovery.clearDurableRecoveries(durableMutationAccountScope(session)) },
                { mutationRecovery.clearPendingDynamicMutations(cacheIdentity) },
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
        AndroidFileSyncRootAcquisitionGenerations.retireCanonical(session.accountId.storageKey)
        val cacheIdentity = previewCacheIdentity ?: NextcloudDocumentIds.cacheAccountId(session)
        runAndroidAccountOwnedStateCleanups(
            cacheIdentity,
            clearPreviewAccount,
            listOf(
                {
                    fenceAndroidDynamicApiStateForRemoval(
                        cacheIdentity, dynamicApiState.coalescer, dynamicApiState.cache, session.accountId.storageKey,
                    )
                },
                { dynamicDiscoveryCache.retireAccount(session.accountId.storageKey, cacheIdentity, accountIdentity) },
                {
                    removeAndroidHomeWorkspaceAccountPreferences(
                        appContext,
                        session.accountId.storageKey,
                        legacyAccountScopeDigest,
                    )
                },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(accountIdentity, session) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncBeforeGrantRevocation(
                    { retireAndroidFileSyncAccountPairs(appContext, accountIdentity, session) },
                    { removeAndroidAccountDiagnosticsAndGrants(appContext, session.accountId.storageKey, accountIdentity, removeSupportAccount) },
                ) },
                { removeLegacyAndroidFileSyncStaging(File(appContext.cacheDir, "file-sync-staging")) },
                { removeAndroidFileSyncAccountStaging(File(appContext.cacheDir, "file-sync-staging"), accountIdentity) },
                { mediaBackupLedger.removeForAccount(accountIdentity) },
                { deckCardDrafts.removeAccount(session.accountId.storageKey, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
                { durableMutationIdentity?.let(mutationRecovery::clearDurableRecoveries) },
                { mutationRecovery.clearPendingDynamicMutations(cacheIdentity) },
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
        AndroidFileSyncRootAcquisitionGenerations.retireCanonical(accountStorageKey)
        runAndroidAccountOwnedStateCleanups(
            previewCacheIdentity,
            clearPreviewAccount,
            listOf(
                {
                    if (previewCacheIdentity == null) {
                        AccountPrivateMemoryLifecycle.retireAccount(accountStorageKey)
                    } else {
                        fenceAndroidDynamicApiStateForRemoval(
                            previewCacheIdentity,
                            dynamicApiState.coalescer,
                            dynamicApiState.cache,
                            accountStorageKey,
                        )
                    }
                },
                { dynamicDiscoveryCache.retireAccount(accountStorageKey, previewCacheIdentity, accountIdentity) },
                {
                    removeAndroidHomeWorkspaceAccountPreferences(
                        appContext,
                        accountStorageKey,
                        legacyAccountScopeDigest,
                    )
                },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(accountIdentity) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncBeforeGrantRevocation(
                    { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                    { removeAndroidAccountDiagnosticsAndGrants(appContext, accountStorageKey, accountIdentity, removeSupportAccount) },
                ) },
                { removeLegacyAndroidFileSyncStaging(File(appContext.cacheDir, "file-sync-staging")) },
                { removeAndroidFileSyncAccountStaging(File(appContext.cacheDir, "file-sync-staging"), accountIdentity) },
                { mediaBackupLedger.removeForAccount(accountIdentity) },
                { deckCardDrafts.removeAccount(accountStorageKey, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
                { durableMutationIdentity?.let(mutationRecovery::clearDurableRecoveries) },
                { previewCacheIdentity?.let(mutationRecovery::clearPendingDynamicMutations) },
            ),
        )
    }
}

internal suspend fun <T> clearAndroidDynamicApiState(
    accountIdentity: String,
    coalescer: DynamicApiRequestCoalescer<T>,
    cache: DynamicApiResponseCache,
    accountStorageKey: String? = null,
    retireMemoryAccount: (String) -> Unit = AccountPrivateMemoryLifecycle::retireAccount,
) = coalescer.fenceAccount(accountIdentity) {
    accountStorageKey?.let(retireMemoryAccount)
    cache.invalidateAccount(accountIdentity)
}

internal suspend fun <T> fenceAndroidDynamicApiStateForRemoval(
    accountIdentity: String,
    coalescer: DynamicApiRequestCoalescer<T>,
    cache: DynamicApiResponseCache,
    accountStorageKey: String? = null,
    retireMemoryAccount: (String) -> Unit = AccountPrivateMemoryLifecycle::retireAccount,
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

internal suspend fun retireAndroidFileSyncBeforeGrantRevocation(
    retire: suspend () -> Unit,
    revoke: suspend () -> Unit,
) {
    retire()
    revoke()
}
