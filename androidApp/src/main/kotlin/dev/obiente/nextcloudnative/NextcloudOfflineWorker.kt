package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** Executes one persisted offline queue job. Queue state remains authoritative across retries. */
internal class NextcloudOfflineWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val userId = inputData.getString(KEY_USER_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val jobId = inputData.getLong(KEY_JOB_ID, -1L).takeIf { it > 0L }
            ?: return Result.failure()
        val coroutineJob = currentCoroutineContext()[Job]
        val cancellation = object : DocumentRequestCancellation {
            private var cancelAction: (() -> Unit)? = null

            override fun throwIfCancelled() {
                if (isStopped || coroutineJob?.isCancelled == true) {
                    throw java.io.InterruptedIOException("Offline work was cancelled.")
                }
            }

            override fun setOnCancelAction(action: (() -> Unit)?) {
                cancelAction = action
                if (action != null && (isStopped || coroutineJob?.isCancelled == true)) action()
            }

            init {
                coroutineJob?.invokeOnCompletion { cancelAction?.invoke() }
            }
        }
        return when (
            AndroidFileOfflineRepository(applicationContext).execute(accountId, userId, jobId, cancellation)
        ) {
            AndroidOfflineExecutionOutcome.Complete -> Result.success()
            AndroidOfflineExecutionOutcome.Retry -> Result.retry()
        }
    }

    internal companion object {
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_JOB_ID = "job_id"
    }
}
