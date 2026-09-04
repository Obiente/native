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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.obiente.nextcloudnative.app.IncomingShareRecoveryPage
import dev.obiente.nextcloudnative.app.IncomingShareUploadPresentation
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.useAndroidNextcloudCertificateTrust
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal class AndroidIncomingShareCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = inputData.getString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID)
            ?: return@withContext Result.failure()
        val store = AndroidIncomingShareStore(applicationContext)
        val released = when (val loaded = store.loadResult(requestId)) {
            is AndroidIncomingShareLoadResult.Available -> {
                val chunk = loaded.request.chunkSession
                if (chunk != null) {
                    val claimed = store.claimChunkSessionForCleanup(requestId, chunk.uploadId)
                    if (claimed != null) {
                        scheduleIncomingShareChunkCleanup(applicationContext, requestId)
                        return@withContext Result.retry()
                    }
                    return@withContext Result.success()
                }
                loaded.request.canExpireIncomingShareRecovery() && store.remove(requestId)
            }
            is AndroidIncomingShareLoadResult.Corrupt -> false
            AndroidIncomingShareLoadResult.Missing -> true
        }
        if (released) {
            NotificationManagerCompat.from(applicationContext)
                .cancel(incomingShareNotificationId(requestId))
        }
        Result.success()
    }
}

internal class AndroidIncomingShareAbandonedStagingCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = inputData.getString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID)
            ?: return@withContext Result.failure()
        val store = AndroidIncomingShareStore(applicationContext)
        when (store.loadResult(requestId)) {
            AndroidIncomingShareLoadResult.Missing -> {
                if (store.removeExpiredAbandonedStaging(requestId)) Result.success() else Result.retry()
            }
            is AndroidIncomingShareLoadResult.Available -> Result.success()
            is AndroidIncomingShareLoadResult.Corrupt -> {
                publishCorruptIncomingShareNotification(applicationContext, requestId)
                Result.success()
            }
        }
    }
}

internal class AndroidIncomingShareChunkCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = inputData.getString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID)
            ?: return@withContext Result.failure()
        val attemptOffset = inputData.getInt(KEY_INCOMING_SHARE_CHUNK_CLEANUP_ATTEMPT, 0).coerceAtLeast(0)
        val cleanupAttempt = attemptOffset + runAttemptCount
        val store = AndroidIncomingShareStore(applicationContext)
        val request = when (val loaded = store.loadResult(requestId)) {
            is AndroidIncomingShareLoadResult.Available -> loaded.request
            is AndroidIncomingShareLoadResult.Corrupt -> return@withContext Result.success()
            AndroidIncomingShareLoadResult.Missing -> return@withContext Result.success()
        }
        val chunk = request.chunkSession ?: return@withContext Result.success()
        val claimed = store.claimChunkSessionForCleanup(requestId, chunk.uploadId)
            ?: return@withContext Result.success()
        val services = AndroidNextcloudServices(applicationContext)
        val unavailable = {
            retryOrReleaseIncomingShareChunkCleanup(
                store,
                requestId,
                claimed,
                cleanupAttempt,
            )
        }
        val accountIdentity = request.accountId ?: return@withContext unavailable()
        val userId = request.userId?.takeIf(String::isNotBlank) ?: return@withContext unavailable()
        return@withContext ANDROID_ACCOUNT_OPERATION_GUARD.withAccountSession(
            accountId = accountIdentity,
            resolveSession = {
                resolveStoredAndroidAccountSession(
                    accountIdentity = accountIdentity,
                    listAccounts = services::listAccounts,
                    loadSession = { accountId -> services.loadSession(accountId) },
                )
            },
            unavailable = unavailable,
        ) { session ->
            val cancellation = CoroutineDocumentRequestCancellation(currentCoroutineContext().job)
            try {
                val remote = AndroidFileSyncRemoteTree(
                    session = session,
                    userId = userId,
                    remoteRootPath = request.destinationPath.orEmpty(),
                    webDav = NextcloudDocumentWebDav(
                        client = OkHttpClient.Builder()
                            .followRedirects(false)
                            .followSslRedirects(false)
                            .retryOnConnectionFailure(false)
                            .useAndroidNextcloudCertificateTrust(applicationContext)
                            .build(),
                        cloudMutationsAllowed = applicationContext.cloudMutationGate(),
                    ),
                )
                remote.deleteChunkUpload(claimed.uploadId, cancellation)
                store.clearChunkSessionForCleanup(requestId, claimed.uploadId)
                releaseDiscardedIncomingShare(store, requestId)
                Result.success()
            } catch (failure: Throwable) {
                cancellation.throwIfCancelled()
                if (
                    failure.isRetryableIncomingShareChunkCleanupFailure() &&
                    canRetryIncomingShareChunkCleanup(cleanupAttempt)
                ) {
                    val nowEpochMillis = System.currentTimeMillis()
                    val retryDelayMillis = failure.incomingShareChunkCleanupRetryDelayMillis(nowEpochMillis)
                    if (retryDelayMillis != null) {
                        scheduleIncomingShareChunkCleanup(
                            context = applicationContext,
                            requestId = requestId,
                            initialDelayMillis = retryDelayMillis,
                            cleanupAttempt = cleanupAttempt + 1,
                            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
                        )
                        Result.success()
                    } else {
                        Result.retry()
                    }
                } else {
                    // Nextcloud expires abandoned upload collections server-side. Once cleanup is
                    // definitively rejected or exhausts its bounded retries, release local staging.
                    store.clearChunkSessionForCleanup(requestId, claimed.uploadId)
                    releaseDiscardedIncomingShare(store, requestId)
                    Result.success()
                }
            } finally {
                cancellation.close()
            }
        }
    }

    private fun retryOrReleaseIncomingShareChunkCleanup(
        store: AndroidIncomingShareStore,
        requestId: String,
        claimed: AndroidIncomingShareChunkSession,
        cleanupAttempt: Int,
    ): Result = if (canRetryIncomingShareChunkCleanup(cleanupAttempt)) {
        Result.retry()
    } else {
        store.clearChunkSessionForCleanup(requestId, claimed.uploadId)
        releaseDiscardedIncomingShare(store, requestId)
        Result.success()
    }

    private fun releaseDiscardedIncomingShare(store: AndroidIncomingShareStore, requestId: String) {
        if (store.removeIfDiscardRequested(requestId)) {
            NotificationManagerCompat.from(applicationContext)
                .cancel(incomingShareNotificationId(requestId))
        }
    }
}

internal fun Throwable.isRetryableIncomingShareChunkCleanupFailure(): Boolean {
    val dav = this as? DocumentWebDavException
    return this is IOException ||
        dav?.error in setOf(DocumentWebDavError.Locked, DocumentWebDavError.Throttled) ||
        (dav?.error == DocumentWebDavError.Server && dav.status >= 500)
}

internal fun Throwable.incomingShareChunkCleanupRetryDelayMillis(nowEpochMillis: Long): Long? =
    incomingShareRetryNotBeforeEpochMillis(nowEpochMillis)
        ?.let { retryNotBefore -> (retryNotBefore - nowEpochMillis).coerceAtLeast(0L) }

internal fun canRetryIncomingShareChunkCleanup(cleanupAttempt: Int): Boolean {
    require(cleanupAttempt >= 0)
    return cleanupAttempt + 1 < MAX_INCOMING_SHARE_CHUNK_CLEANUP_ATTEMPTS
}

internal const val MAX_INCOMING_SHARE_CHUNK_CLEANUP_ATTEMPTS = 8
internal const val KEY_INCOMING_SHARE_CHUNK_CLEANUP_ATTEMPT = "chunk_cleanup_attempt"

internal fun scheduleIncomingShareCleanup(context: Context, requestId: String) {
    scheduleIncomingShareChunkCleanup(context, requestId)
    WorkManager.getInstance(context).enqueueUniqueWork(
        incomingShareCleanupWorkName(requestId),
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<AndroidIncomingShareCleanupWorker>()
            .setInitialDelay(7, TimeUnit.DAYS)
            .setInputData(Data.Builder().putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, requestId).build())
            .build(),
    )
}

internal fun scheduleIncomingShareAbandonedStagingCleanup(context: Context, requestId: String) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "incoming-share-abandoned-staging-$requestId",
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<AndroidIncomingShareAbandonedStagingCleanupWorker>()
            .setInitialDelay(ABANDONED_INCOMING_SHARE_STAGING_RETENTION_MILLIS, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, requestId).build())
            .build(),
    )
}

internal fun scheduleIncomingShareChunkCleanup(
    context: Context,
    requestId: String,
    initialDelayMillis: Long = 0L,
    cleanupAttempt: Int = 0,
    policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
) {
    require(initialDelayMillis >= 0L && cleanupAttempt in 0 until MAX_INCOMING_SHARE_CHUNK_CLEANUP_ATTEMPTS)
    val request = OneTimeWorkRequestBuilder<AndroidIncomingShareChunkCleanupWorker>()
        .setInputData(
            Data.Builder()
                .putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, requestId)
                .putInt(KEY_INCOMING_SHARE_CHUNK_CLEANUP_ATTEMPT, cleanupAttempt)
                .build(),
        )
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .apply { if (initialDelayMillis > 0L) setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS) }
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        incomingShareChunkCleanupWorkName(requestId),
        policy,
        request,
    )
}

internal fun incomingShareCleanupWorkName(requestId: String) = "incoming-share-cleanup-$requestId"

internal fun incomingShareChunkCleanupWorkName(requestId: String) = "incoming-share-chunk-cleanup-$requestId"

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
    scopedIncomingShareNotificationId(requestId, "terminal")

internal fun incomingShareForegroundNotificationId(requestId: String): Int =
    scopedIncomingShareNotificationId(requestId, "foreground")

private fun scopedIncomingShareNotificationId(requestId: String, scope: String): Int =
    "$scope:$requestId".hashCode()
        .let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }
        .coerceAtLeast(1)

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

internal const val CANCELED_INCOMING_SHARE_MUTATION_WARNING = "Upload canceled. The active file may already have " +
    "reached Nextcloud; check Files before sharing it again."

internal fun AndroidIncomingShareRequest.canExpireIncomingShareRecovery(): Boolean =
    state == AndroidIncomingShareState.Completed || discardRequested

internal fun AndroidIncomingShareRequest.requiresIncomingShareRecovery(accountId: String): Boolean {
    if (this.accountId != accountId) return false
    return state in setOf(
        AndroidIncomingShareState.Staged,
        AndroidIncomingShareState.Queued,
        AndroidIncomingShareState.Uploading,
        AndroidIncomingShareState.Failed,
        AndroidIncomingShareState.OutcomeUnknown,
        AndroidIncomingShareState.Canceled,
    )
}

internal suspend fun loadAndroidIncomingShareRecoveries(
    context: Context,
    session: NextcloudSession,
    userId: String,
    cursor: String?,
): IncomingShareRecoveryPage = withContext(Dispatchers.IO) {
    require(userId.isNotBlank())
    val recoveries = AndroidIncomingShareStore(context)
        .listRecoverablePage(NextcloudDocumentIds.accountKey(session), cursor)
    val uploads = AndroidIncomingShareUploads(context)
    recoveries.requests.forEach(uploads::ensureQueuedRequestScheduled)
    IncomingShareRecoveryPage(
        requests = recoveries.requests.map(AndroidIncomingShareRequest::toPresentation) +
            recoveries.corruptRequestIds.map { requestId ->
                IncomingShareUploadPresentation(
                    id = requestId,
                    files = emptyList(),
                    state = dev.obiente.nextcloudnative.app.IncomingShareUploadState.Failed,
                    destinationPath = null,
                    completedFiles = 0,
                    message = "This recovery record is damaged.",
                    isCorruptRecovery = true,
                )
            },
        nextCursor = recoveries.nextCursor,
    )
}

internal fun openAndroidIncomingShareRecovery(context: Context, requestId: String) {
    context.startActivity(
        Intent(context, AndroidShareUploadActivity::class.java)
            .putExtra(AndroidShareUploadActivity.KEY_REQUEST_ID, requestId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
}

internal val TERMINAL_INCOMING_SHARE_STATES = setOf(
    AndroidIncomingShareState.Completed,
    AndroidIncomingShareState.Failed,
    AndroidIncomingShareState.OutcomeUnknown,
    AndroidIncomingShareState.Canceled,
)
