package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.NextcloudSession

internal class AndroidAccountOwnedStateCleanup(context: Context) {
    private val appContext = context.applicationContext
    private val fileOffline = AndroidFileOfflineAccountCleanup(appContext)
    private val incomingShares = AndroidIncomingShareAccountCleanup(appContext)
    private val durableUploads = AndroidDurableUploadAccountCleanup(appContext)

    suspend fun remove(session: NextcloudSession) {
        val accountIdentity = NextcloudDocumentIds.accountKey(session)
        runAndroidAccountRemovalCleanups(
            listOf(
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(session) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
            ),
        )
    }

    suspend fun retry(accountIdentity: String) {
        runAndroidAccountRemovalCleanups(
            listOf(
                { fileOffline.removeForAccount(accountIdentity) },
                { incomingShares.removeForAccount(accountIdentity) },
                { durableUploads.removeForAccount(accountIdentity) },
                { retireAndroidFileSyncAccountPairs(appContext, accountIdentity) },
            ),
        )
    }
}
