package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticFieldDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.SupportDiagnosticValuePrivacy
import dev.obiente.nextcloudnative.app.toSupportDiagnosticExceptionDraft
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** Executes one persisted offline queue job. Queue state remains authoritative across retries. */
internal class NextcloudOfflineWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val userId = inputData.getString(KEY_USER_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val jobId = inputData.getLong(KEY_JOB_ID, -1L).takeIf { it > 0L }
            ?: return Result.failure()
        val coroutineJob = currentCoroutineContext()[Job]
        val cancellation = object : DocumentRequestCancellation {
            private var cancelAction: (() -> Unit)? = null

            override fun throwIfCancelled() {
                if (isStopped || coroutineJob?.isCancelled == true) {
                    throw java.io.InterruptedIOException("Offline work was cancelled.")
                }
            }

            override fun setOnCancelAction(action: (() -> Unit)?) {
                cancelAction = action
                if (action != null && (isStopped || coroutineJob?.isCancelled == true)) action()
            }

            init {
                coroutineJob?.invokeOnCompletion { cancelAction?.invoke() }
            }
        }
        val diagnostics = AndroidSupportDiagnostics.get(applicationContext)
        return try {
            when (AndroidFileOfflineRepository(applicationContext).execute(accountId, userId, jobId, cancellation)) {
                AndroidOfflineExecutionOutcome.Complete -> Result.success()
                AndroidOfflineExecutionOutcome.Retry -> {
                    diagnostics.recordForAccountIdentity(
                        accountId,
                        SupportDiagnosticEventDraft(
                            severity = SupportDiagnosticSeverity.Warning,
                            component = SupportDiagnosticComponent.Storage,
                            operation = "offline.background-job",
                            outcome = "retry-scheduled",
                            fields = listOf(
                                SupportDiagnosticFieldDraft(
                                    "account",
                                    accountId,
                                    SupportDiagnosticValuePrivacy.Identifier,
                                ),
                                SupportDiagnosticFieldDraft(
                                    "job",
                                    jobId.toString(),
                                    SupportDiagnosticValuePrivacy.Identifier,
                                ),
                            ),
                        ),
                    )
                    Result.retry()
                }
            }
        } catch (failure: Throwable) {
            if (isStopped || coroutineJob?.isCancelled == true) throw failure
            diagnostics.recordForAccountIdentity(
                accountId,
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Error,
                    component = SupportDiagnosticComponent.Storage,
                    operation = "offline.background-job",
                    outcome = "failed",
                    fields = listOf(
                        SupportDiagnosticFieldDraft("account", accountId, SupportDiagnosticValuePrivacy.Identifier),
                        SupportDiagnosticFieldDraft(
                            "job",
                            jobId.toString(),
                            SupportDiagnosticValuePrivacy.Identifier,
                        ),
                    ),
                    exception = failure.toSupportDiagnosticExceptionDraft(),
                ),
            )
            throw failure
        }
    }

    internal companion object {
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_JOB_ID = "job_id"
    }
}
