package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun startAndroidFileSyncCapabilityRecovery(
    context: Context,
    scope: CoroutineScope,
    load: () -> AndroidFileSyncPersistedState,
    capabilities: AndroidFileSyncCapabilityLifecycle,
) {
    scope.launch {
        runAndroidFileSyncCapabilityStartupRecovery(
            cancelLegacy = { WorkManager.getInstance(context).cancelUniqueWork("file-sync-capability-cleanup-v1").await() },
            reconcile = {
                AndroidFileSyncEngine.ENGINE_LOCK.withLock {
                    capabilities.reconcile(load())
                    capabilities.hasRecoveryWork()
                }
            },
            scheduleRecovery = { requestAndroidFileSyncCapabilityRecovery(context) },
            recordFailure = { failure ->
                android.util.Log.w("FolderCapabilityRecovery", "Folder access cleanup is awaiting recovery.", failure)
            },
        )
    }
}

internal suspend fun runAndroidFileSyncCapabilityStartupRecovery(
    cancelLegacy: suspend () -> Unit,
    reconcile: suspend () -> Boolean,
    scheduleRecovery: suspend () -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    try {
        cancelLegacy()
        if (reconcile()) scheduleRecovery()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        try {
            scheduleRecovery()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (schedulingFailure: Exception) {
            failure.addSuppressed(schedulingFailure)
        }
        recordFailure(failure)
    }
}

internal fun requestAndroidFileSyncCapabilityRecovery(context: Context) {
    val request = OneTimeWorkRequestBuilder<AndroidFileSyncCapabilityRecoveryWorker>()
        .setInitialDelay(1, TimeUnit.MINUTES)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .build()
    // Append preserves a new request arriving while the previous worker finishes.
    val operation = WorkManager.getInstance(context).enqueueUniqueWork(
        "file-sync-capability-cleanup-v2", ExistingWorkPolicy.APPEND_OR_REPLACE, request,
    )
    // This boundary runs on the owned IO path, never the Activity result callback.
    runBlocking { withTimeout(30_000L) { operation.await() } }
}

internal class AndroidFileSyncCapabilityRecoveryWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val capabilities = AndroidFileSyncCapabilityLifecycle(applicationContext)
            val store = AndroidFileSyncStore(applicationContext)
            // A worker may start the process before the activity restores its draft.
            reconcileFileSyncCapabilitiesAfterRestoration(
                AndroidFileSyncEngine.ENGINE_LOCK, store::loadAndReconcileUploadCleanups, capabilities,
                onFailure = { throw it },
            )
            AndroidFileSyncEngine.ENGINE_LOCK.withLock {
                if (capabilities.hasRecoveryWork()) Result.retry() else Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
