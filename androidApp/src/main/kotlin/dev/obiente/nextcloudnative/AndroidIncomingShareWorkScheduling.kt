package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val INCOMING_SHARE_WORK_BACKOFF_SECONDS = 30L

internal class AndroidIncomingShareUploads(private val context: Context) {
    private val store = AndroidIncomingShareStore(context.applicationContext)

    fun enqueue(
        session: NextcloudSession,
        userId: String,
        requestId: String,
        destinationPath: String,
    ): AndroidIncomingShareRequest {
        val queued = store.queue(
            id = requestId,
            accountId = NextcloudDocumentIds.accountKey(session),
            userId = userId,
            destinationPath = destinationPath,
        )
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(incomingShareCleanupWorkName(requestId))
        workManager.cancelUniqueWork(incomingShareChunkCleanupWorkName(requestId))
        workManager.cancelUniqueWork(incomingShareRetryWorkName(requestId))
        workManager.enqueueUniqueWork(
            incomingShareUploadWorkName(requestId),
            ExistingWorkPolicy.REPLACE,
            incomingShareUploadWork(requestId),
        )
        return queued
    }

    fun ensureQueuedRequestScheduled(request: AndroidIncomingShareRequest) {
        if (request.state != AndroidIncomingShareState.Queued) return
        val retryNotBefore = request.retryNotBeforeEpochMillis
        if (retryNotBefore != null && retryNotBefore > System.currentTimeMillis()) {
            scheduleIncomingShareRetry(context, request, ExistingWorkPolicy.KEEP)
            return
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            incomingShareUploadWorkName(request.id),
            ExistingWorkPolicy.KEEP,
            incomingShareUploadWork(request.id),
        )
    }

    fun cancel(requestId: String) {
        val current = store.load(requestId)
        val canceled = store.transition(
            id = requestId,
            expected = setOf(AndroidIncomingShareState.Queued, AndroidIncomingShareState.Uploading),
            target = AndroidIncomingShareState.Canceled,
            message = if (current?.state == AndroidIncomingShareState.Uploading) {
                CANCELED_INCOMING_SHARE_MUTATION_WARNING
            } else {
                "Upload canceled before a transfer was active."
            },
        )
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(incomingShareUploadWorkName(requestId))
            cancelUniqueWork(incomingShareRetryWorkName(requestId))
        }
        canceled?.let { scheduleIncomingShareCleanup(context, it.id) }
    }
}

internal class AndroidIncomingShareRetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = inputData.getString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID)
            ?: return@withContext Result.failure()
        val request = AndroidIncomingShareStore(applicationContext).load(requestId)
            ?: return@withContext Result.success()
        if (request.state != AndroidIncomingShareState.Queued) return@withContext Result.success()
        val retryNotBefore = request.retryNotBeforeEpochMillis
        if (retryNotBefore != null && retryNotBefore > System.currentTimeMillis()) {
            return@withContext Result.retry()
        }
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            incomingShareUploadWorkName(requestId),
            ExistingWorkPolicy.KEEP,
            incomingShareUploadWork(requestId),
        )
        Result.success()
    }
}

internal fun scheduleIncomingShareRetry(
    context: Context,
    request: AndroidIncomingShareRequest,
    policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
) {
    require(request.state == AndroidIncomingShareState.Queued)
    val retryNotBefore = requireNotNull(request.retryNotBeforeEpochMillis)
    val delayMillis = (retryNotBefore - System.currentTimeMillis()).coerceAtLeast(0L)
    WorkManager.getInstance(context).enqueueUniqueWork(
        incomingShareRetryWorkName(request.id),
        policy,
        OneTimeWorkRequestBuilder<AndroidIncomingShareRetryWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, request.id).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INCOMING_SHARE_WORK_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build(),
    )
}

internal fun incomingShareUploadWorkName(requestId: String) = "incoming-share-$requestId"

internal fun incomingShareRetryWorkName(requestId: String) = "incoming-share-retry-$requestId"

private fun incomingShareUploadWork(requestId: String) =
    OneTimeWorkRequestBuilder<AndroidIncomingShareUploadWorker>()
        .setInputData(Data.Builder().putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, requestId).build())
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INCOMING_SHARE_WORK_BACKOFF_SECONDS, TimeUnit.SECONDS)
        .build()
