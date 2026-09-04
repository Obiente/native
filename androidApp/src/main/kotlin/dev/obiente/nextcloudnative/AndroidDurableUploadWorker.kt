package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.obiente.nextcloudnative.app.DurableUploadState
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticFieldDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.SupportDiagnosticValuePrivacy
import dev.obiente.nextcloudnative.app.afterProcessRecovery
import dev.obiente.nextcloudnative.app.toSupportDiagnosticExceptionDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DeckAttachmentUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID)?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure()
        val store = AndroidDurableMultipartUploadStore(applicationContext)
        val initial = store.find(jobId) ?: return@withContext Result.success()
        val picker = AndroidLocalUploadPicker(applicationContext)
        if (initial.state.afterProcessRecovery() != initial.state) {
            store.transition(
                jobId,
                expected = DurableUploadState.Uploading,
                target = DurableUploadState.OutcomeUnknown,
                message = "The app restarted while this upload was in progress. Check the card before uploading again.",
            )
            picker.release(initial.request.file)
            recordUploadDiagnostic(
                severity = SupportDiagnosticSeverity.Warning,
                outcome = "process-recovery",
                accountId = initial.accountId,
                jobId = jobId,
            )
            return@withContext Result.success()
        }
        if (initial.state != DurableUploadState.Queued) return@withContext Result.success()

        return@withContext uploadQueuedJob(store, initial, picker, jobId)
    }

    private suspend fun uploadQueuedJob(
        store: AndroidDurableMultipartUploadStore,
        initial: AndroidDurableMultipartUploadJob,
        picker: AndroidLocalUploadPicker,
        jobId: String,
    ): Result = ANDROID_ACCOUNT_OPERATION_GUARD.withAccount(initial.accountId) {
        performQueuedUpload(store, initial, picker, jobId)
    }

    private suspend fun performQueuedUpload(
        store: AndroidDurableMultipartUploadStore,
        initial: AndroidDurableMultipartUploadJob,
        picker: AndroidLocalUploadPicker,
        jobId: String,
    ): Result {
        val services = AndroidNextcloudServices(applicationContext)
        if (!services.isDurableUploadAccountResolutionAvailable()) return Result.retry()
        val session = resolveDurableUploadSessionWithRegistryRecovery(
            expectedAccountId = initial.accountId,
            listAccounts = services::listAccounts,
            recoverRegistry = { services.loadSession() },
            loadSession = services::loadSession,
        )
        if (session == null) {
            recordUploadDiagnostic(
                severity = SupportDiagnosticSeverity.Warning,
                outcome = "account-resolution-deferred",
                accountId = initial.accountId,
                jobId = jobId,
            )
            return Result.retry()
        }
        val capabilityReady = runCatching {
            picker.requirePersisted(initial.request.file)
            picker.open(initial.request.file).use { }
        }.isSuccess
        if (!capabilityReady) {
            store.transition(
                jobId,
                expected = DurableUploadState.Queued,
                target = DurableUploadState.Failed,
                message = "The selected file is no longer available. Select it again to retry.",
            )
            picker.release(initial.request.file)
            recordUploadDiagnostic(
                severity = SupportDiagnosticSeverity.Warning,
                outcome = "source-unavailable",
                accountId = initial.accountId,
                jobId = jobId,
            )
            return Result.failure()
        }
        val started = store.transition(
            jobId,
            expected = DurableUploadState.Queued,
            target = DurableUploadState.Uploading,
            message = null,
        ) ?: return Result.success()
        val uploadServices = AndroidNextcloudServices(
            applicationContext,
            localUploadPicker = picker,
            accountMutationLeaseHeld = true,
        )
        val outcome = captureDurableUploadRequestOutcome {
            uploadServices.executeNextcloudMultipartUpload(session, started.request)
        }
        outcome.onSuccess { response ->
            val state = durableUploadStateForHttpResponse(response.status)
            val message = when (state) {
                DurableUploadState.Completed -> null
                DurableUploadState.Failed ->
                    "The server rejected this upload (HTTP ${response.status})."
                DurableUploadState.OutcomeUnknown ->
                    "The server returned HTTP ${response.status}, but the upload result is unknown. " +
                        "Check the card before uploading again."
                DurableUploadState.Queued,
                DurableUploadState.Uploading,
                -> error("The upload response state is invalid.")
            }
            store.transition(
                jobId,
                expected = DurableUploadState.Uploading,
                target = state,
                message = message,
            )
            if (state != DurableUploadState.Completed) {
                recordUploadDiagnostic(
                    severity = SupportDiagnosticSeverity.Warning,
                    outcome = when (state) {
                        DurableUploadState.Failed -> "rejected"
                        DurableUploadState.OutcomeUnknown -> "outcome-unknown"
                        DurableUploadState.Completed,
                        DurableUploadState.Queued,
                        DurableUploadState.Uploading,
                        -> error("Only failed upload states are diagnosed here.")
                    },
                    accountId = initial.accountId,
                    jobId = jobId,
                    code = "HTTP:${response.status}",
                )
            }
            picker.release(started.request.file)
        }.onFailure { failure ->
            // Once the request body starts, a transport exception cannot prove whether the server
            // created the attachment. Never replay it automatically and risk a duplicate.
            store.transition(
                jobId,
                expected = DurableUploadState.Uploading,
                target = DurableUploadState.OutcomeUnknown,
                message = "The upload result is unknown. Check the card before uploading again.",
            )
            recordUploadDiagnostic(
                severity = SupportDiagnosticSeverity.Error,
                outcome = "outcome-unknown",
                accountId = initial.accountId,
                jobId = jobId,
                failure = failure,
            )
            picker.release(started.request.file)
        }
        return Result.success()
    }

    private fun recordUploadDiagnostic(
        severity: SupportDiagnosticSeverity,
        outcome: String,
        accountId: String,
        jobId: String,
        code: String? = null,
        failure: Throwable? = null,
    ) {
        AndroidSupportDiagnostics.get(applicationContext).recordForAccountIdentity(
            accountId,
            SupportDiagnosticEventDraft(
                severity = severity,
                component = SupportDiagnosticComponent.Media,
                operation = "media.durable-upload",
                outcome = outcome,
                code = code,
                fields = listOf(
                    SupportDiagnosticFieldDraft("job", jobId, SupportDiagnosticValuePrivacy.Identifier),
                ),
                exception = failure?.toSupportDiagnosticExceptionDraft(),
            ),
        )
    }

    internal companion object {
        const val KEY_JOB_ID = "job_id"
    }
}

internal suspend fun <Result> captureDurableUploadRequestOutcome(
    request: suspend () -> Result,
): kotlin.Result<Result> = try {
    kotlin.Result.success(request())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    kotlin.Result.failure(failure)
}
