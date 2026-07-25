package dev.obiente.nextcloudnative

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Periodic account-scoped folder sync. Persisted coordinator state remains authoritative. */
internal class NextcloudFileSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pairId = inputData.getString(KEY_PAIR_ID)?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure()
        val accountId = inputData.getString(KEY_ACCOUNT_ID)?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure()
        val userId = inputData.getString(KEY_USER_ID)?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure()
        val services = AndroidNextcloudServices(applicationContext)
        val session = services.loadSession()
            ?: return@withContext Result.failure()
        if (NextcloudDocumentIds.accountKey(session) != accountId) {
            return@withContext Result.failure()
        }
        AndroidNotificationCoordinator(applicationContext).ensureChannels()
        try {
            setForeground(createForegroundInfo(pairId))
        } catch (error: IllegalStateException) {
            if (!isForegroundServiceStartNotAllowed(error)) {
                throw error
            }
            // WorkManager may still execute short work when the OS temporarily refuses an FGS.
        }
        val engine = AndroidFileSyncEngine(applicationContext)
        val result = runCatching { engine.runPair(session, userId, pairId) }
            .getOrElse { return@withContext Result.retry() }
        val pair = engine.loadCenter(session, userId).pairs.firstOrNull { it.id == pairId }
            ?: return@withContext Result.success()
        pair.conflicts.firstOrNull()?.let { conflict ->
            AndroidNotificationCoordinator(applicationContext).post(
                NextcloudNotificationEvent.SyncConflict(
                    id = stableNotificationId(pairId),
                    accountKey = accountId,
                    path = conflict.relativePath,
                    detail = "${pair.conflicts.size} sync conflict" +
                        if (pair.conflicts.size == 1) " needs review." else "s need review.",
                ),
            )
        }
        if (pair.failedCount > 0 || result is FileSyncCenterActionResult.Rejected) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private fun createForegroundInfo(pairId: String): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF8F5EAD.toInt())
            .setContentTitle("Syncing Nextcloud folder")
            .setContentText("Checking device and server changes")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        val id = stableNotificationId(pairId)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun stableNotificationId(pairId: String): Int =
        pairId.hashCode().let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }.coerceAtLeast(1)

    private fun isForegroundServiceStartNotAllowed(error: IllegalStateException): Boolean =
        Build.VERSION.SDK_INT >= 31 && isForegroundServiceStartNotAllowedApi31(error)

    @RequiresApi(31)
    private fun isForegroundServiceStartNotAllowedApi31(error: IllegalStateException): Boolean =
        error is ForegroundServiceStartNotAllowedException

    internal companion object {
        const val KEY_PAIR_ID = "pair_id"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_USER_ID = "user_id"
    }
}
