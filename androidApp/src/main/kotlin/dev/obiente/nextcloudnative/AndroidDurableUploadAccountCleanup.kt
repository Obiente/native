package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.WorkManager
import androidx.work.await

internal class AndroidDurableUploadAccountCleanup(context: Context) {
    private val appContext = context.applicationContext
    private val store = AndroidDurableMultipartUploadStore(appContext)

    suspend fun removeForAccount(accountId: String) {
        store.list().filter { job -> job.accountId == accountId }.forEach { job ->
            WorkManager.getInstance(appContext).cancelUniqueWork(durableUploadWorkName(job.id)).await()
        }
        val removed = store.removeForAccount(accountId)
        val picker = AndroidLocalUploadPicker(appContext)
        removed.forEach { job -> picker.release(job.request.file) }
    }
}
