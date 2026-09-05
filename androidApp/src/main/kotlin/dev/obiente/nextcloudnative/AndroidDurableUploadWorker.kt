package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.obiente.nextcloudnative.app.DurableUploadState
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticFieldDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.SupportDiagnosticValuePrivacy
import dev.obiente.nextcloudnative.app.afterProcessRecovery
import dev.obiente.nextcloudnative.app.toSupportDiagnosticExceptionDraft
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DeckAttachmentUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runDurableUploadWorkerWithRecoverySignal(
        requestRecovery = {
            requestQueuedDurableUploadSchedulingRecoveryAfterWorkStopsRunning(id)
        },
    ) {
        withContext(Dispatchers.IO) {
            executeDurableUploadWork()
        }
    }

    private suspend fun executeDurableUploadWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val store = AndroidDurableMultipartUploadStore(applicationContext)
        val initial = store.find(jobId) ?: return Result.success()
        val picker = AndroidLocalUploadPicker(applicationContext)
        if (initial.state.afterProcessRecovery() != initial.state) {
            store.transition(
                jobId,
                expected = DurableUploadState.Uploading,
                target = DurableUploadState.OutcomeUnknown,
                message = "The app restarted while this upload was in progress. Check the card before uploading again.",
            )
            recordUploadDiagnostic(
                severity = SupportDiagnosticSeverity.Warning,
                outcome = "process-recovery",
                accountId = initial.accountId,
                jobId = jobId,
            )
            return resultAfterDurableUploadCapabilityRelease(
                releaseCapability = { picker.release(initial.request.file) },
                completeCapabilityCleanup = { store.completeCapabilityCleanup(jobId) },
                onCleanupRetained = ::requestQueuedDurableUploadSchedulingRecovery,
                releasedResult = Result.success(),
                retainedResult = Result.retry(),
            )
        }
        if (initial.state != DurableUploadState.Queued) {
            return resultAfterDurableUploadCapabilityRelease(
                releaseCapability = { picker.release(initial.request.file) },
                completeCapabilityCleanup = { store.completeCapabilityCleanup(jobId) },
                onCleanupRetained = ::requestQueuedDurableUploadSchedulingRecovery,
                releasedResult = Result.success(),
                retainedResult = Result.retry(),
            )
        }

        return uploadQueuedJob(store, initial, picker, jobId)
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
        val accountResolution = resolveDurableUploadSessionWithRegistryRecovery(
            expectedAccountId = initial.accountId,
            readRegistry = services::durableUploadAccountRegistry,
            recoverRegistry = { services.loadSession() },
            loadSession = services::loadSession,
        )
        val session = when (accountResolution) {
            is DurableUploadAccountResolution.Available -> accountResolution.session
            DurableUploadAccountResolution.RegistryUnavailable -> {
                recordUploadDiagnostic(
                    severity = SupportDiagnosticSeverity.Warning,
                    outcome = "account-registry-unavailable",
                    accountId = initial.accountId,
                    jobId = jobId,
                )
                return Result.retry()
            }
            DurableUploadAccountResolution.CredentialUnavailable -> {
                recordUploadDiagnostic(
                    severity = SupportDiagnosticSeverity.Warning,
                    outcome = "account-resolution-deferred",
                    accountId = initial.accountId,
                    jobId = jobId,
                )
                return Result.retry()
            }
            DurableUploadAccountResolution.AccountUnavailable -> {
                return failQueuedDurableUploadForUnavailableAccount(
                    transitionToFailed = {
                        store.transition(
                            jobId,
                            expected = DurableUploadState.Queued,
                            target = DurableUploadState.Failed,
                            message = "The account used for this upload is no longer available.",
                        )
                    },
                    releaseSelection = { picker.release(initial.request.file) },
                    completeCapabilityCleanup = { store.completeCapabilityCleanup(jobId) },
                    onCleanupRetained = ::requestQueuedDurableUploadSchedulingRecovery,
                    recordFailure = {
                        recordUploadDiagnostic(
                            severity = SupportDiagnosticSeverity.Warning,
                            outcome = "account-unavailable",
                            accountId = initial.accountId,
                            jobId = jobId,
                        )
                    },
                    failureResult = Result.failure(),
                    retryResult = Result.retry(),
                )
            }
        }
        return processQueuedDurableUploadSource(
            requireCapability = { picker.requirePersisted(initial.request.file) },
            openSource = { picker.open(initial.request.file).use { } },
            onCapabilityUnavailable = {
                store.transition(
                    jobId,
                    expected = DurableUploadState.Queued,
                    target = DurableUploadState.Failed,
                    message = "The selected file is no longer available. Select it again to retry.",
                )
                recordUploadDiagnostic(
                    severity = SupportDiagnosticSeverity.Warning,
                    outcome = "source-unavailable",
                    accountId = initial.accountId,
                    jobId = jobId,
                )
                resultAfterDurableUploadCapabilityRelease(
                    releaseCapability = { picker.release(initial.request.file) },
                    completeCapabilityCleanup = { store.completeCapabilityCleanup(jobId) },
                    onCleanupRetained = ::requestQueuedDurableUploadSchedulingRecovery,
                    releasedResult = Result.failure(),
                    retainedResult = Result.retry(),
                )
            },
            onProviderUnavailable = { failure ->
                recordUploadDiagnostic(
                    severity = SupportDiagnosticSeverity.Warning,
                    outcome = "source-open-deferred",
                    accountId = initial.accountId,
                    jobId = jobId,
                    failure = failure,
                )
                Result.retry()
            },
            onReady = {
                uploadReadyQueuedJob(store, initial, picker, jobId, session)
            },
        )
    }

    private suspend fun uploadReadyQueuedJob(
        store: AndroidDurableMultipartUploadStore,
        initial: AndroidDurableMultipartUploadJob,
        picker: AndroidLocalUploadPicker,
        jobId: String,
        session: NextcloudSession,
    ): Result {
        val started = claimQueuedDurableUploadForExecution(jobId) {
            store.transition(
                jobId,
                expected = DurableUploadState.Queued,
                target = DurableUploadState.Uploading,
                message = null,
            )
        } ?: return Result.success()
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
        }
        return resultAfterDurableUploadCapabilityRelease(
            releaseCapability = { picker.release(started.request.file) },
            completeCapabilityCleanup = { store.completeCapabilityCleanup(jobId) },
            onCleanupRetained = ::requestQueuedDurableUploadSchedulingRecovery,
            releasedResult = Result.success(),
            retainedResult = Result.retry(),
        )
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

internal fun <Result> failQueuedDurableUploadForUnavailableAccount(
    transitionToFailed: () -> Unit,
    releaseSelection: () -> Boolean,
    completeCapabilityCleanup: () -> Unit = {},
    onCleanupRetained: () -> Unit = {},
    recordFailure: () -> Unit,
    failureResult: Result,
    retryResult: Result,
): Result {
    transitionToFailed()
    val result = resultAfterDurableUploadCapabilityRelease(
        releaseCapability = releaseSelection,
        completeCapabilityCleanup = completeCapabilityCleanup,
        onCleanupRetained = onCleanupRetained,
        releasedResult = failureResult,
        retainedResult = retryResult,
    )
    recordFailure()
    return result
}

internal fun <Result> resultAfterDurableUploadCapabilityRelease(
    releaseCapability: () -> Boolean,
    completeCapabilityCleanup: () -> Unit = {},
    onCleanupRetained: () -> Unit = {},
    releasedResult: Result,
    retainedResult: Result,
): Result = try {
    if (releaseCapability()) {
        completeCapabilityCleanup()
        releasedResult
    } else {
        runCatching(onCleanupRetained)
        retainedResult
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    runCatching(onCleanupRetained)
    retainedResult
}

internal suspend fun <Result> processQueuedDurableUploadSource(
    requireCapability: () -> Unit,
    openSource: () -> Unit,
    onCapabilityUnavailable: suspend () -> Result,
    onProviderUnavailable: suspend (Exception) -> Result,
    onReady: suspend () -> Result,
): Result {
    try {
        requireCapability()
        openSource()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AndroidLocalUploadCapabilityUnavailableException) {
        return onCapabilityUnavailable()
    } catch (_: FileNotFoundException) {
        return onCapabilityUnavailable()
    } catch (_: SecurityException) {
        return onCapabilityUnavailable()
    } catch (failure: Exception) {
        return onProviderUnavailable(failure)
    }
    return onReady()
}

internal suspend fun <WorkResult> runDurableUploadWorkerWithRecoverySignal(
    requestRecovery: () -> Unit = ::requestQueuedDurableUploadSchedulingRecovery,
    work: suspend () -> WorkResult,
): WorkResult = try {
    work()
} catch (cancelled: CancellationException) {
    runCatching(requestRecovery)
    throw cancelled
} catch (failure: Exception) {
    runCatching(requestRecovery)
    throw failure
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
