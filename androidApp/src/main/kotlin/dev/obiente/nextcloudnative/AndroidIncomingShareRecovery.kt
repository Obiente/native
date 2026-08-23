package dev.obiente.nextcloudnative

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidIncomingShareCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = inputData.getString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID)
            ?: return@withContext Result.failure()
        val store = AndroidIncomingShareStore(applicationContext)
        when (val loaded = store.loadResult(requestId)) {
            is AndroidIncomingShareLoadResult.Available -> {
                if (loaded.request.state in TERMINAL_INCOMING_SHARE_STATES) store.remove(requestId)
            }
            is AndroidIncomingShareLoadResult.Corrupt -> store.remove(requestId)
            AndroidIncomingShareLoadResult.Missing -> Unit
        }
        Result.success()
    }
}

internal fun scheduleIncomingShareCleanup(context: Context, requestId: String) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "incoming-share-cleanup-$requestId",
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<AndroidIncomingShareCleanupWorker>()
            .setInitialDelay(7, TimeUnit.DAYS)
            .setInputData(Data.Builder().putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, requestId).build())
            .build(),
    )
}

internal fun incomingShareRecoveryPendingIntent(context: Context, requestId: String): PendingIntent =
    PendingIntent.getActivity(
        context,
        incomingShareNotificationId(requestId),
        Intent(context, AndroidShareUploadActivity::class.java)
            .putExtra(AndroidShareUploadActivity.KEY_REQUEST_ID, requestId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

internal fun incomingShareNotificationId(requestId: String): Int =
    requestId.hashCode().let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }.coerceAtLeast(1)

internal fun publishCorruptIncomingShareNotification(context: Context, requestId: String) {
    if (
        Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) return
    AndroidNotificationCoordinator(context).ensureChannels()
    val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFF8F5EAD.toInt())
        .setContentTitle("Shared upload needs attention")
        .setContentText("Tap to review or remove its protected staging data.")
        .setCategory(NotificationCompat.CATEGORY_ERROR)
        .setAutoCancel(true)
        .setContentIntent(incomingShareRecoveryPendingIntent(context, requestId))
        .build()
    try {
        NotificationManagerCompat.from(context).notify(incomingShareNotificationId(requestId), notification)
    } catch (_: SecurityException) {
        // Permission can be revoked after the explicit check.
    }
}

internal val TERMINAL_INCOMING_SHARE_STATES = setOf(
    AndroidIncomingShareState.Completed,
    AndroidIncomingShareState.Failed,
    AndroidIncomingShareState.OutcomeUnknown,
    AndroidIncomingShareState.Canceled,
)
