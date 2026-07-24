package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Durable WorkManager schedule for one sync pair. WorkManager restores it after reboot. */
internal class AndroidFileSyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun schedule(pairId: String, accountId: String, userId: String) {
        val request = PeriodicWorkRequestBuilder<NextcloudFileSyncWorker>(
            REPEAT_MINUTES,
            TimeUnit.MINUTES,
        )
            .setInputData(
                Data.Builder()
                    .putString(NextcloudFileSyncWorker.KEY_PAIR_ID, pairId)
                    .putString(NextcloudFileSyncWorker.KEY_ACCOUNT_ID, accountId)
                    .putString(NextcloudFileSyncWorker.KEY_USER_ID, userId)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(pairTag(pairId))
            .build()
        workManager.enqueueUniquePeriodicWork(
            workName(pairId),
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(pairId: String) {
        workManager.cancelUniqueWork(workName(pairId))
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    private fun workName(pairId: String): String = "nextcloud-native-file-sync-$pairId"
    private fun pairTag(pairId: String): String = "file-sync-pair-$pairId"

    private companion object {
        const val TAG = "nextcloud-native-file-sync"
        const val REPEAT_MINUTES = 15L
    }
}
