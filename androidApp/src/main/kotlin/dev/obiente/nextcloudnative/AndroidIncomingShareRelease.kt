package dev.obiente.nextcloudnative

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Releases user-reviewed incoming-share recovery data without blocking the Activity. */
internal class AndroidIncomingShareReleaseWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = inputData.getString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID)
            ?: return@withContext Result.failure()
        val store = AndroidIncomingShareStore(applicationContext)
        val released = if (inputData.getBoolean(KEY_REMOVE_CORRUPT_RECOVERY, false)) {
            val expectedAccountId = inputData.getString(KEY_EXPECTED_ACCOUNT_ID)
                ?: return@withContext Result.failure()
            store.removeCorruptRecovery(requestId, expectedAccountId)
        } else if (inputData.getBoolean(KEY_REQUEST_DISCARD, false)) {
            val expectedFingerprint = inputData.getString(KEY_EXPECTED_FINGERPRINT)
                ?: return@withContext Result.failure()
            val discarded = store.markDiscardRequested(requestId, expectedFingerprint)
                ?: return@withContext Result.success()
            if (discarded.chunkSession == null) {
                store.removeIfDiscardRequested(requestId)
            } else {
                scheduleIncomingShareChunkCleanup(applicationContext, requestId)
                false
            }
        } else {
            val expectedFingerprint = inputData.getString(KEY_EXPECTED_FINGERPRINT)
                ?: return@withContext Result.failure()
            store.removeIfMatchingReleasable(requestId, expectedFingerprint)
        }
        if (released) {
            NotificationManagerCompat.from(applicationContext)
                .cancel(incomingShareNotificationId(requestId))
        }
        Result.success()
    }

    internal companion object {
        const val KEY_EXPECTED_FINGERPRINT = "expected_fingerprint"
        const val KEY_REMOVE_CORRUPT_RECOVERY = "remove_corrupt_recovery"
        const val KEY_REQUEST_DISCARD = "request_discard"
        const val KEY_EXPECTED_ACCOUNT_ID = "expected_account_id"
    }
}

internal fun scheduleIncomingSharePresentedDiscard(
    context: Context,
    presented: AndroidIncomingShareRequest,
) {
    require(
        presented.state == AndroidIncomingShareState.Staged ||
            presented.state in TERMINAL_INCOMING_SHARE_STATES,
    )
    WorkManager.getInstance(context).enqueueUniqueWork(
        incomingShareReleaseWorkName(presented.id),
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<AndroidIncomingShareReleaseWorker>()
            .setInputData(
                Data.Builder()
                    .putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, presented.id)
                    .putString(
                        AndroidIncomingShareReleaseWorker.KEY_EXPECTED_FINGERPRINT,
                        presented.incomingShareReleaseFingerprint(),
                    )
                    .putBoolean(AndroidIncomingShareReleaseWorker.KEY_REQUEST_DISCARD, true)
                    .build(),
            )
            .build(),
    )
}

internal fun scheduleIncomingSharePresentedRelease(
    context: Context,
    presented: AndroidIncomingShareRequest,
) {
    require(presented.canReleaseIncomingShareRequest())
    WorkManager.getInstance(context).enqueueUniqueWork(
        incomingShareReleaseWorkName(presented.id),
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<AndroidIncomingShareReleaseWorker>()
            .setInputData(
                Data.Builder()
                    .putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, presented.id)
                    .putString(
                        AndroidIncomingShareReleaseWorker.KEY_EXPECTED_FINGERPRINT,
                        presented.incomingShareReleaseFingerprint(),
                    )
                    .build(),
            )
            .build(),
    )
}

internal fun scheduleCorruptIncomingShareRemoval(context: Context, requestId: String, expectedAccountId: String) {
    require(isValidIncomingShareRequestId(requestId))
    require(expectedAccountId.isNotBlank())
    WorkManager.getInstance(context).enqueueUniqueWork(
        incomingShareReleaseWorkName(requestId),
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<AndroidIncomingShareReleaseWorker>()
            .setInputData(
                Data.Builder()
                    .putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, requestId)
                    .putBoolean(AndroidIncomingShareReleaseWorker.KEY_REMOVE_CORRUPT_RECOVERY, true)
                    .putString(AndroidIncomingShareReleaseWorker.KEY_EXPECTED_ACCOUNT_ID, expectedAccountId)
                    .build(),
            )
            .build(),
    )
}

internal fun AndroidIncomingShareRequest.incomingShareReleaseFingerprint(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toJson().toString().encodeToByteArray())
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

internal fun incomingShareReleaseWorkName(requestId: String) = "incoming-share-release-$requestId"
