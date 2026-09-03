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
import dev.obiente.nextcloudnative.app.FileSyncRejectionScope
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticFieldDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.SupportDiagnosticValuePrivacy
import dev.obiente.nextcloudnative.app.toSupportDiagnosticExceptionDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Periodic account-scoped folder sync. Persisted coordinator state remains authoritative. */
internal class NextcloudFileSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pairId = inputData.getString(KEY_PAIR_ID)?.takeIf(String::isNotBlank)
            ?: return@withContext invalidWorkInput("pair_id")
        val accountId = inputData.getString(KEY_ACCOUNT_ID)?.takeIf(String::isNotBlank)
            ?: return@withContext invalidWorkInput("account_id")
        val userId = inputData.getString(KEY_USER_ID)?.takeIf(String::isNotBlank)
            ?: return@withContext invalidWorkInput("user_id")
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
            .getOrElse { failure ->
                rethrowAndroidFileSyncCancellation(failure)
                val disposition = backgroundSyncFailureDisposition(runAttemptCount)
                services.recordSupportDiagnosticForAccountIdentity(
                    accountId,
                    SupportDiagnosticEventDraft(
                        severity = SupportDiagnosticSeverity.Error,
                        component = SupportDiagnosticComponent.Sync,
                        operation = "sync.background-run",
                        outcome = "failed",
                        fields = listOf(
                            SupportDiagnosticFieldDraft(
                                "pair",
                                pairId,
                                SupportDiagnosticValuePrivacy.Identifier,
                            ),
                            SupportDiagnosticFieldDraft("failure_scope", "run"),
                            SupportDiagnosticFieldDraft("work_attempt", runAttemptCount.toString()),
                            SupportDiagnosticFieldDraft(
                                "retry_scheduled",
                                (disposition == BackgroundSyncWorkerDisposition.Retry).toString(),
                            ),
                        ),
                        exception = failure.toSupportDiagnosticExceptionDraft(),
                    ),
                )
                return@withContext disposition.toWorkerResult()
            }
        val pair = engine.loadCenter(session, userId).pairs.firstOrNull { it.id == pairId }
            ?: return@withContext Result.success()
        pair.conflicts.firstOrNull()?.let { conflict ->
            AndroidNotificationCoordinator(applicationContext).post(
                NextcloudNotificationEvent.SyncConflict(
                    id = stableNotificationId(pairId),
                    accountKey = accountId,
                    path = conflict.relativePath,
                    detail = syncConflictNotificationDetail(pair.conflictCount),
                ),
            )
        }
        val completionDisposition = backgroundSyncCompletionDisposition(
            failedCount = pair.failedCount,
            resultRejected = result is FileSyncCenterActionResult.Rejected,
        )
        if (completionDisposition == BackgroundSyncWorkerDisposition.WaitForNextPeriod) {
            services.recordSupportDiagnosticForAccountIdentity(
                accountId,
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Warning,
                    component = SupportDiagnosticComponent.Sync,
                    operation = "sync.background-run",
                    outcome = "needs-attention",
                    fields = backgroundSyncCompletionDiagnosticFields(
                        pairId = pairId,
                        failedCount = pair.failedCount,
                        conflictCount = pair.conflictCount,
                        result = result,
                    ),
                ),
            )
            // Per-item failures and attempt counts are durable coordinator state. An immediate
            // WorkManager retry bypasses the periodic cadence and re-executes known failed work.
            completionDisposition.toWorkerResult()
        } else {
            completionDisposition.toWorkerResult()
        }
    }

    private fun invalidWorkInput(field: String): Result {
        AndroidSupportDiagnostics.get(applicationContext).record(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Error,
                component = SupportDiagnosticComponent.Sync,
                operation = "sync.background-run",
                outcome = "invalid-input",
                fields = listOf(SupportDiagnosticFieldDraft("missing_field", field)),
            ),
        )
        return Result.failure()
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

internal class AndroidFileSyncScheduleRestorationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val expectedAccountId = inputData.getString(KEY_ACCOUNT_ID)?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure()
        val services = AndroidNextcloudServices(applicationContext)
        val session = services.loadSession()
            ?.takeIf { restored -> isAndroidFileSyncScheduleRestorationCurrent(expectedAccountId, restored) }
            ?: return@withContext Result.success()
        runCatching {
            val userId = services.loadServerInfo(session).userId
            services.loadFileSyncCenter(session, userId)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { failure ->
                rethrowAndroidFileSyncCancellation(failure)
                scheduleRestorationFailureDisposition(runAttemptCount).toWorkerResult()
            },
        )
    }

    internal companion object {
        const val KEY_ACCOUNT_ID = "account_id"
    }
}

internal fun isAndroidFileSyncScheduleRestorationCurrent(
    expectedAccountId: String,
    session: NextcloudSession,
): Boolean = NextcloudDocumentIds.accountKey(session) == expectedAccountId

internal fun scheduleRestorationFailureDisposition(runAttemptCount: Int): BackgroundSyncWorkerDisposition =
    backgroundSyncFailureDisposition(runAttemptCount)

internal fun syncConflictNotificationDetail(conflictCount: Int): String {
    require(conflictCount > 0)
    return "$conflictCount sync conflict" +
        if (conflictCount == 1) " needs review." else "s need review."
}

internal enum class BackgroundSyncWorkerDisposition {
    Retry,
    WaitForNextPeriod,
    Complete,
}

internal fun backgroundSyncCompletionDisposition(
    failedCount: Int,
    resultRejected: Boolean,
): BackgroundSyncWorkerDisposition {
    require(failedCount >= 0)
    return if (failedCount > 0 || resultRejected) {
        BackgroundSyncWorkerDisposition.WaitForNextPeriod
    } else {
        BackgroundSyncWorkerDisposition.Complete
    }
}

internal fun backgroundSyncFailureDisposition(runAttemptCount: Int): BackgroundSyncWorkerDisposition {
    require(runAttemptCount >= 0)
    return if (runAttemptCount < MAX_BACKGROUND_SYNC_IMMEDIATE_RETRIES) {
        BackgroundSyncWorkerDisposition.Retry
    } else {
        BackgroundSyncWorkerDisposition.WaitForNextPeriod
    }
}

internal fun backgroundSyncCompletionDiagnosticFields(
    pairId: String,
    failedCount: Int,
    conflictCount: Int,
    result: FileSyncCenterActionResult,
): List<SupportDiagnosticFieldDraft> {
    require(failedCount >= 0)
    require(conflictCount >= 0)
    val rejection = result as? FileSyncCenterActionResult.Rejected
    val preflightRejected = rejection?.scope == FileSyncRejectionScope.Preflight
    return buildList {
        add(SupportDiagnosticFieldDraft("pair", pairId, SupportDiagnosticValuePrivacy.Identifier))
        add(SupportDiagnosticFieldDraft("failure_scope", if (preflightRejected) "preflight" else "items"))
        add(SupportDiagnosticFieldDraft("failed_count", failedCount.toString()))
        add(SupportDiagnosticFieldDraft("conflict_count", conflictCount.toString()))
        add(SupportDiagnosticFieldDraft("result", if (rejection != null) "rejected" else "completed"))
        if (preflightRejected) {
            add(SupportDiagnosticFieldDraft("rejection_reason", rejection.reason))
        }
        add(SupportDiagnosticFieldDraft("retry_scheduled", "false"))
    }
}

private fun BackgroundSyncWorkerDisposition.toWorkerResult(): androidx.work.ListenableWorker.Result = when (this) {
    BackgroundSyncWorkerDisposition.Retry -> androidx.work.ListenableWorker.Result.retry()
    BackgroundSyncWorkerDisposition.WaitForNextPeriod,
    BackgroundSyncWorkerDisposition.Complete,
    -> androidx.work.ListenableWorker.Result.success()
}

private const val MAX_BACKGROUND_SYNC_IMMEDIATE_RETRIES = 2
