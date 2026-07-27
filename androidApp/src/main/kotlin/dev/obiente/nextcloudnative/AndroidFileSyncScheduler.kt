package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncNetworkPolicy
import dev.obiente.nextcloudnative.app.FileSyncPowerPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/** Durable WorkManager schedule for one sync pair. WorkManager restores it after reboot. */
internal class AndroidFileSyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun schedule(
        pairId: String,
        accountId: String,
        userId: String,
        configuration: FileSyncConfiguration,
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                when (configuration.networkPolicy) {
                    FileSyncNetworkPolicy.AnyConnection -> NetworkType.CONNECTED
                    FileSyncNetworkPolicy.Unmetered -> NetworkType.UNMETERED
                },
            )
            .setRequiresBatteryNotLow(configuration.powerPolicy == FileSyncPowerPolicy.BatteryNotLow)
            .setRequiresCharging(configuration.powerPolicy == FileSyncPowerPolicy.Charging)
            .build()
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
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(pairTag(pairId))
            .build()
        workManager.enqueueUniquePeriodicWork(
            workName(pairId),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    suspend fun cancel(pairId: String) {
        workManager.cancelUniqueWork(workName(pairId)).await()
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    suspend fun runningPairIds(pairIds: Collection<String>): Set<String> {
        return pairIds.filterTo(mutableSetOf()) { pairId ->
            workManager.getWorkInfosForUniqueWorkFlow(workName(pairId)).first()
                .any { work -> work.state == WorkInfo.State.RUNNING }
        }
    }

    private fun workName(pairId: String): String = "nextcloud-native-file-sync-$pairId"
    private fun pairTag(pairId: String): String = "file-sync-pair-$pairId"

    private companion object {
        const val TAG = "nextcloud-native-file-sync"
        const val REPEAT_MINUTES = 15L
    }
}
