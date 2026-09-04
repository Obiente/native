package dev.obiente.nextcloudnative

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import androidx.work.await
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.useAndroidNextcloudCertificateTrust
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal data class AndroidIncomingShareAccountRequest(
    val id: String,
    val request: AndroidIncomingShareRequest?,
)

internal class AndroidIncomingShareAccountCleanup(context: Context) {
    private val appContext = context.applicationContext
    private val store = AndroidIncomingShareStore(appContext)

    suspend fun removeForAccount(session: NextcloudSession) =
        removeForAccount(NextcloudDocumentIds.accountKey(session), session)

    suspend fun removeForAccount(accountId: String) = removeForAccount(accountId, session = null)

    private suspend fun removeForAccount(accountId: String, session: NextcloudSession?) = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(appContext)
        val webDav = session?.let {
            NextcloudDocumentWebDav(
                client = OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .useAndroidNextcloudCertificateTrust(appContext)
                    .build(),
                cloudMutationsAllowed = appContext.cloudMutationGate(),
            )
        }
        removeAndroidIncomingShareRequests(
            requests = store.listForAccount(accountId),
            cancelWork = { requestId ->
                incomingShareAccountWorkNames(requestId).forEach { workName ->
                    workManager.cancelUniqueWork(workName).await()
                }
            },
            releaseChunk = { request, uploadId ->
                if (session == null || webDav == null) return@removeAndroidIncomingShareRequests
                val userId = requireNotNull(request.userId?.takeIf(String::isNotBlank)) {
                    "The staged share chunk is missing its account owner."
                }
                val cancellation = CoroutineDocumentRequestCancellation(currentCoroutineContext().job)
                try {
                    webDav.deleteChunkUpload(session, userId, uploadId, cancellation)
                } finally {
                    cancellation.close()
                }
            },
            recordChunkReleaseFailure = { failure ->
                Log.w(LOG_TAG, "Remote staged-share chunk cleanup deferred during account removal", failure)
            },
            removeRequest = { requestId ->
                check(store.remove(requestId)) { "The staged share data could not be released." }
                NotificationManagerCompat.from(appContext).apply {
                    cancel(incomingShareNotificationId(requestId))
                    cancel(incomingShareForegroundNotificationId(requestId))
                }
            },
        )
    }
}

internal fun AndroidIncomingShareStore.listForAccount(accountId: String): List<AndroidIncomingShareAccountRequest> {
    require(accountId.isNotBlank())
    return synchronized(AndroidIncomingShareStore.LOCK) {
        root.listFiles().orEmpty()
            .asSequence()
            .filter { directory ->
                directory.isDirectory && runCatching { UUID.fromString(directory.name) }.isSuccess
            }
            .mapNotNull { directory ->
                val id = directory.name
                when (val loaded = loadResult(id)) {
                    AndroidIncomingShareLoadResult.Missing -> null
                    is AndroidIncomingShareLoadResult.Available -> loaded.request
                        .takeIf { request -> request.accountId == accountId }
                        ?.let { request -> AndroidIncomingShareAccountRequest(id, request) }
                    is AndroidIncomingShareLoadResult.Corrupt ->
                        id.takeIf { corruptRecoveryAccountId(id) == accountId }
                            ?.let { AndroidIncomingShareAccountRequest(it, request = null) }
                }
            }
            .toList()
    }
}

internal fun incomingShareAccountWorkNames(requestId: String): List<String> = listOf(
    incomingShareUploadWorkName(requestId),
    incomingShareRetryWorkName(requestId),
    incomingShareCleanupWorkName(requestId),
    incomingShareChunkCleanupWorkName(requestId),
    incomingShareReleaseWorkName(requestId),
    incomingShareAbandonedStagingWorkName(requestId),
)

internal suspend fun removeAndroidIncomingShareRequests(
    requests: List<AndroidIncomingShareAccountRequest>,
    cancelWork: suspend (String) -> Unit,
    releaseChunk: suspend (AndroidIncomingShareRequest, String) -> Unit,
    recordChunkReleaseFailure: (Throwable) -> Unit = {},
    removeRequest: (String) -> Unit,
) {
    requests.forEach { request -> cancelWork(request.id) }
    requests.forEach { accountRequest ->
        accountRequest.request?.chunkSession?.let { chunk ->
            try {
                releaseChunk(accountRequest.request, chunk.uploadId)
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                recordChunkReleaseFailure(failure)
            }
        }
    }
    requests.forEach { request -> removeRequest(request.id) }
}

private const val LOG_TAG = "IncomingShareCleanup"
