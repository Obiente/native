package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.WorkManager
import androidx.work.await
import dev.obiente.nextcloudnative.app.FileOfflineQueueState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidFileOfflineAccountCleanup(context: Context) {
    private val appContext = context.applicationContext
    private val store = AndroidFileOfflineQueueStore(appContext)

    suspend fun removeForAccount(accountId: String) = withContext(Dispatchers.IO) {
        val pendingJobIds = synchronized(AndroidFileOfflineRepository.STATE_LOCK) {
            store.load().queue.jobs.filter { job -> job.key.accountId == accountId }.map { job -> job.id }
        }
        val workManager = WorkManager.getInstance(appContext)
        pendingJobIds.forEach { jobId ->
            workManager.cancelUniqueWork(AndroidFileOfflineRepository.workName(accountId, jobId)).await()
        }
        synchronized(AndroidFileOfflineRepository.STATE_LOCK) {
            store.save(removeAndroidFileOfflineAccountState(store.load(), accountId))
        }
        val accountContent = File(
            File(appContext.filesDir, AndroidFileOfflineRepository.CONTENT_DIRECTORY),
            accountId,
        )
        check(!accountContent.exists() || accountContent.deleteRecursively()) {
            "Could not remove this account's offline files."
        }
    }
}

internal fun removeAndroidFileOfflineAccountState(
    current: AndroidFileOfflinePersistedState,
    accountId: String,
): AndroidFileOfflinePersistedState = current.copy(
    queue = FileOfflineQueueState(
        records = current.queue.records.filterNot { record -> record.descriptor.key.accountId == accountId },
        jobs = current.queue.jobs.filterNot { job -> job.key.accountId == accountId },
        nextJobId = current.queue.nextJobId,
    ),
    folders = current.folders.copy(
        directPins = current.folders.directPins.filterNotTo(linkedSetOf()) { key -> key.accountId == accountId },
        roots = current.folders.roots.filterNot { root -> root.accountId == accountId },
    ),
)
