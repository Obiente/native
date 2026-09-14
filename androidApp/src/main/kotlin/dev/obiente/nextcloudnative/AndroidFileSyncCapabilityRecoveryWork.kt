package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun startAndroidFileSyncCapabilityRecovery(
    context: Context,
    scope: CoroutineScope,
    load: () -> AndroidFileSyncPersistedState,
    capabilities: AndroidFileSyncCapabilityLifecycle,
) {
    scope.launch {
        try {
            val request = PeriodicWorkRequestBuilder<AndroidFileSyncCapabilityRecoveryWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "file-sync-capability-cleanup-v1", ExistingPeriodicWorkPolicy.KEEP, request,
            ).await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("FolderCapabilityRecovery", "Could not schedule durable folder access cleanup.", failure)
        }
        reconcileFileSyncCapabilitiesAfterRestoration(AndroidFileSyncEngine.ENGINE_LOCK, load, capabilities)
    }
}

internal class AndroidFileSyncCapabilityRecoveryWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // A worker may start the process before the activity restores its draft.
            delay(60_000L)
            AndroidFileSyncEngine.ENGINE_LOCK.withLock {
                AndroidFileSyncCapabilityLifecycle(applicationContext).reconcile(
                    AndroidFileSyncStore(applicationContext).loadAndReconcileUploadCleanups(),
                    reclaimUnrestoredReady = true,
                )
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
