package dev.obiente.nextcloudnative

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.obiente.nextcloudnative.app.useAndroidNextcloudCertificateTrust
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal class AndroidIncomingShareUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return@withContext Result.failure()
        val store = AndroidIncomingShareStore(applicationContext)
        var request = when (val loaded = store.loadResult(requestId)) {
            is AndroidIncomingShareLoadResult.Available -> loaded.request
            is AndroidIncomingShareLoadResult.Corrupt -> {
                publishCorruptIncomingShareNotification(applicationContext, loaded.requestId)
                scheduleIncomingShareCleanup(applicationContext, loaded.requestId)
                return@withContext Result.failure()
            }
            AndroidIncomingShareLoadResult.Missing -> return@withContext Result.success()
        }
        if (request.state == AndroidIncomingShareState.Uploading) {
            if (request.isFullyJournaledIncomingShareUpload()) {
                scheduleIncomingShareCleanup(applicationContext, request.id)
                request = store.transition(
                    id = requestId,
                    expected = setOf(AndroidIncomingShareState.Uploading),
                    target = AndroidIncomingShareState.Completed,
                ) ?: return@withContext Result.success()
                runCatching { store.removeStagedFiles(request) }
                publishTerminalNotification(request)
                return@withContext Result.success()
            }
            if (request.canSafelyResumeAfterWorkerRestart()) {
                request = store.transition(
                    id = requestId,
                    expected = setOf(AndroidIncomingShareState.Uploading),
                    target = AndroidIncomingShareState.Queued,
                    message = if (request.chunkSession == null) {
                        "Resuming after the destination check was interrupted."
                    } else {
                        "Resuming the large file from its last saved chunk."
                    },
                ) ?: return@withContext Result.success()
            } else {
                val recovered = store.transition(
                    id = requestId,
                    expected = setOf(AndroidIncomingShareState.Uploading),
                    target = AndroidIncomingShareState.OutcomeUnknown,
                    message = "Android restarted during an upload. Check Files before trying again.",
                )
                recovered?.let {
                    publishTerminalNotification(it)
                    scheduleIncomingShareCleanup(applicationContext, it.id)
                }
                return@withContext Result.success()
            }
        }
        if (request.state != AndroidIncomingShareState.Queued) return@withContext Result.success()
        if ((request.retryNotBeforeEpochMillis ?: 0L) > System.currentTimeMillis()) {
            scheduleIncomingShareRetry(applicationContext, request)
            return@withContext Result.success()
        }
        val session = AndroidNextcloudServices(applicationContext).loadSession()
        if (session == null || NextcloudDocumentIds.accountKey(session) != request.accountId) {
            val failed = store.transition(
                id = requestId,
                expected = setOf(AndroidIncomingShareState.Queued),
                target = AndroidIncomingShareState.Failed,
                message = "The upload account is not active.",
            )
            failed?.let {
                publishTerminalNotification(it)
                scheduleIncomingShareCleanup(applicationContext, it.id)
            }
            return@withContext Result.failure()
        }
        AndroidNotificationCoordinator(applicationContext).ensureChannels()
        runCatching { setForeground(foregroundInfo(request)) }
            .onFailure { failure ->
                if (failure !is IllegalStateException || !isForegroundStartUnavailable(failure)) throw failure
            }
        request = store.beginUpload(requestId) ?: return@withContext Result.success()
        val remote = AndroidFileSyncRemoteTree(
            session = session,
            userId = requireNotNull(request.userId),
            remoteRootPath = requireNotNull(request.destinationPath),
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
        val requestCancellation = CoroutineDocumentRequestCancellation(currentCoroutineContext().job)
        var mutationInFlight = false
        try {
            val occupiedNames = remote.rootChildNames().names.toMutableSet().apply {
                addAll(request.uploadedNames)
            }
            val transfer = AndroidIncomingShareFileTransfer(store, remote, requestCancellation)
            for (index in request.completedFiles until request.files.size) {
                ensureNotCanceled(requestId, store)
                request = transfer.upload(requestId, request, index, occupiedNames) { inFlight ->
                    if (store.setVisibleMutationInFlight(requestId, inFlight) == null) {
                        throw CancellationException("Incoming share upload canceled")
                    }
                    mutationInFlight = inFlight
                }
                setForeground(foregroundInfo(request))
            }
            scheduleIncomingShareCleanup(applicationContext, request.id)
            request = store.transition(
                id = requestId,
                expected = setOf(AndroidIncomingShareState.Uploading),
                target = AndroidIncomingShareState.Completed,
            ) ?: throw CancellationException("Incoming share upload canceled")
            runCatching { store.removeStagedFiles(request) }
            publishTerminalNotification(request)
            Result.success()
        } catch (cancelled: CancellationException) {
            if (store.load(requestId)?.state != AndroidIncomingShareState.Canceled) {
                val transitioned = store.transition(
                    id = requestId,
                    expected = setOf(AndroidIncomingShareState.Uploading),
                    target = if (mutationInFlight) {
                        AndroidIncomingShareState.OutcomeUnknown
                    } else {
                        AndroidIncomingShareState.Queued
                    },
                    message = if (mutationInFlight) {
                        "Android stopped during an upload. Check Files before trying again."
                    } else {
                        "Upload paused. It will continue when Android allows background work."
                    },
                )
                transitioned?.takeIf { it.state == AndroidIncomingShareState.OutcomeUnknown }?.let {
                    publishTerminalNotification(it)
                    scheduleIncomingShareCleanup(applicationContext, it.id)
                }
            }
            throw cancelled
        } catch (failure: Throwable) {
            val definitelyRejected = mutationInFlight && !incomingShareMutationOutcomeUnknown(failure, true)
            if (definitelyRejected) {
                store.load(requestId)?.chunkSession?.takeIf { it.commitInFlight }?.let {
                    store.clearChunkCommitInFlight(requestId)
                }
                store.setVisibleMutationInFlight(requestId, false)
                mutationInFlight = false
            }
            if (shouldRetryIncomingShareTransfer(failure, mutationInFlight, request.automaticTransferAttempts)) {
                val retryNotBefore = failure.incomingShareRetryNotBeforeEpochMillis(System.currentTimeMillis())
                val queued = store.queueAutomaticRetry(
                    id = requestId,
                    message = if (retryNotBefore == null) {
                        "Upload paused and will retry with backoff."
                    } else {
                        "Nextcloud asked this upload to wait before retrying."
                    },
                    retryNotBeforeEpochMillis = retryNotBefore,
                ) ?: return@withContext Result.success()
                if (retryNotBefore != null) {
                    scheduleIncomingShareRetry(applicationContext, queued)
                    return@withContext Result.success()
                }
                return@withContext Result.retry()
            }
            // A transport failure after a conditional PUT starts cannot prove whether the server
            // committed it. Do not replay automatically and risk a duplicate.
            val outcomeUnknown = incomingShareMutationOutcomeUnknown(failure, mutationInFlight)
            val target = if (outcomeUnknown) {
                AndroidIncomingShareState.OutcomeUnknown
            } else {
                AndroidIncomingShareState.Failed
            }
            val transitioned = store.transition(
                id = requestId,
                expected = setOf(AndroidIncomingShareState.Uploading),
                target = target,
                message = failure.message?.take(240) ?: if (outcomeUnknown) {
                    "The upload result is unknown."
                } else {
                    "The upload could not continue."
                },
            )
            transitioned?.let {
                publishTerminalNotification(it)
                scheduleIncomingShareCleanup(applicationContext, it.id)
            }
            Result.failure()
        } finally {
            requestCancellation.close()
        }
    }

    private fun ensureNotCanceled(requestId: String, store: AndroidIncomingShareStore) {
        if (store.load(requestId)?.state == AndroidIncomingShareState.Canceled) {
            throw CancellationException("Incoming share upload canceled")
        }
    }

    private fun foregroundInfo(request: AndroidIncomingShareRequest): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF8F5EAD.toInt())
            .setContentTitle("Uploading shared files")
            .setContentText("${request.completedFiles} of ${request.files.size} uploaded")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(request.files.size, request.completedFiles, false)
            .setContentIntent(incomingShareRecoveryPendingIntent(applicationContext, request.id))
            .build()
        val id = incomingShareForegroundNotificationId(request.id)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun isForegroundStartUnavailable(error: IllegalStateException): Boolean =
        Build.VERSION.SDK_INT >= 31 && isForegroundStartUnavailableApi31(error)

    @RequiresApi(31)
    private fun isForegroundStartUnavailableApi31(error: IllegalStateException): Boolean =
        error is ForegroundServiceStartNotAllowedException

    private fun publishTerminalNotification(request: AndroidIncomingShareRequest) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        AndroidNotificationCoordinator(applicationContext).ensureChannels()
        val completed = request.state == AndroidIncomingShareState.Completed
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF8F5EAD.toInt())
            .setContentTitle(if (completed) "Shared files uploaded" else "Shared upload needs attention")
            .setContentText(
                if (completed) {
                    "${request.completedFiles} files uploaded to Nextcloud"
                } else {
                    "${request.completedFiles} of ${request.files.size} uploaded. Tap to review."
                },
            )
            .setCategory(if (completed) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(incomingShareRecoveryPendingIntent(applicationContext, request.id))
            .build()
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(incomingShareNotificationId(request.id), notification)
        } catch (_: SecurityException) {
            // The permission can still be revoked between the explicit check and delivery.
        }
    }

    internal companion object {
        const val KEY_REQUEST_ID = "request_id"
    }
}

internal fun Throwable.incomingShareRetryNotBeforeEpochMillis(nowEpochMillis: Long): Long? {
    require(nowEpochMillis >= 0L)
    val retryAfterSeconds = (this as? DocumentWebDavException)
        ?.takeIf { it.error == DocumentWebDavError.Throttled }
        ?.retryAfterSeconds
        ?.takeIf { it > INCOMING_SHARE_WORK_BACKOFF_SECONDS }
        ?: return null
    return nowEpochMillis + retryAfterSeconds * 1_000L
}
