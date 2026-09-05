package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.File

internal class AndroidAccountOwnedStateCleanup(
    context: Context,
    private val fileReadCache: AndroidFileReadCache = AndroidFileReadCache(
        File(context.applicationContext.cacheDir, "files-read-v1"),
    ),
    private val virtualFileCache: AndroidVirtualFileCache = AndroidVirtualFileCache(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val fileOffline = AndroidFileOfflineAccountCleanup(appContext)
    private val incomingShares = AndroidIncomingShareAccountCleanup(appContext)
    private val durableUploads = AndroidDurableUploadAccountCleanup(appContext)

    suspend fun remove(session: NextcloudSession) {
        val accountIdentity = NextcloudDocumentIds.accountKey(session)
        runAndroidAccountRemovalCleanups(
            listOf(
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(session) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
            ),
        )
    }

    suspend fun retry(session: NextcloudSession, accountIdentity: String) {
        runAndroidAccountRemovalCleanups(
            listOf(
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(accountIdentity, session) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
            ),
        )
    }

    suspend fun retryWithoutCredentials(accountIdentity: String) {
        runAndroidAccountRemovalCleanups(
            listOf(
                { revokeAndroidAccountDocumentGrants(appContext, accountIdentity) },
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(accountIdentity) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
                { fileReadCache.clearAccount(accountIdentity) },
                { virtualFileCache.clearAccount(accountIdentity) },
            ),
        )
    }
}
