package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.util.concurrent.TimeUnit

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
        workManager.enqueueUniqueWork(
            workName(requestId),
            ExistingWorkPolicy.REPLACE,
            incomingShareUploadWork(requestId),
        )
        return queued
    }

    fun ensureQueuedRequestScheduled(request: AndroidIncomingShareRequest) {
        if (request.state != AndroidIncomingShareState.Queued) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(request.id),
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
        WorkManager.getInstance(context).cancelUniqueWork(workName(requestId))
        canceled?.let { scheduleIncomingShareCleanup(context, it.id) }
    }

    private fun workName(requestId: String) = "incoming-share-$requestId"

    private fun incomingShareUploadWork(requestId: String) =
        OneTimeWorkRequestBuilder<AndroidIncomingShareUploadWorker>()
            .setInputData(Data.Builder().putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, requestId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
}
