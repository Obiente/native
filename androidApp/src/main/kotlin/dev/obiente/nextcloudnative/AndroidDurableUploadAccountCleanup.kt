package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.WorkManager
import androidx.work.await

internal class AndroidDurableUploadAccountCleanup(context: Context) {
    private val appContext = context.applicationContext
    private val store = AndroidDurableMultipartUploadStore(appContext)

    suspend fun removeForAccount(accountId: String) {
        val picker = AndroidLocalUploadPicker(appContext)
        removeAndroidDurableUploadJobs(
            jobs = store.list().filter { job -> job.accountId == accountId },
            cancelWork = { job ->
                WorkManager.getInstance(appContext).cancelUniqueWork(durableUploadWorkName(job.id)).await()
            },
            releaseCapability = { job ->
                releaseAndroidDurableUploadCapabilityForAccountRemoval { onQuarantined ->
                    picker.release(job.request.file, onQuarantined)
                }
            },
            removeJob = store::remove,
        )
    }
}

internal fun releaseAndroidDurableUploadCapabilityForAccountRemoval(
    release: (onQuarantined: () -> Unit) -> Boolean,
): Boolean {
    var quarantined = false
    return release { quarantined = true } || quarantined
}

internal suspend fun removeAndroidDurableUploadJobs(
    jobs: List<AndroidDurableMultipartUploadJob>,
    cancelWork: suspend (AndroidDurableMultipartUploadJob) -> Unit,
    releaseCapability: (AndroidDurableMultipartUploadJob) -> Boolean,
    removeJob: (String) -> Unit,
) {
    jobs.forEach { job -> cancelWork(job) }
    jobs.forEach { job ->
        check(releaseCapability(job)) { "The durable upload source capability could not be released." }
        removeJob(job.id)
    }
}
