package dev.obiente.nextcloudnative

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NextcloudNativeApplication : Application() {
    private val startupRecoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var accountCleanupListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        installAndroidUncaughtDiagnosticHandler(base)
    }

    override fun onCreate() {
        super.onCreate()
        accountCleanupListener = installAndroidAccountRemovalCleanupRecovery(this)
        startupRecoveryScope.launch {
            val recordRecoveryFailure = {
                AndroidSupportDiagnostics.get(this@NextcloudNativeApplication).record(
                    SupportDiagnosticEventDraft(
                        severity = SupportDiagnosticSeverity.Error,
                        component = SupportDiagnosticComponent.Media,
                        operation = "media.durable-upload-startup",
                        outcome = "recovery-blocked",
                        code = "DURABLE_UPLOAD_QUEUE_RECOVERY_FAILED",
                    ),
                )
            }
            runAndroidDurableUploadStartupRecovery(
                recover = {
                    var uploads: AndroidDurableMultipartUploads? = null
                    monitorQueuedDurableUploadScheduling(
                        recover = {
                            keepRetryingQueuedDurableUploadScheduling(
                                reconcile = {
                                    constructAndReconcileQueuedDurableUploads {
                                        val accountResolutionAvailable = getSharedPreferences(
                                            ANDROID_ACCOUNT_PREFERENCES_NAME,
                                            Context.MODE_PRIVATE,
                                        ).durableUploadAccountResolutionAvailable()
                                        val available = uploads ?: AndroidDurableMultipartUploads(
                                            this@NextcloudNativeApplication,
                                        ).also { uploads = it }
                                        suspend {
                                            available.reconcileQueuedUploads(
                                                allowQueuedScheduling = accountResolutionAvailable,
                                            )
                                        }
                                    }
                                },
                                wait = { delayMillis -> delay(delayMillis) },
                                recordRecoveryFailure = recordRecoveryFailure,
                            )
                        },
                        awaitWorkStopsRunning = { workId ->
                            awaitDurableUploadWorkToStopRunning(
                                workId = workId,
                                awaitWorkStopsRunning = { requestedWorkId ->
                                    WorkManager.getInstance(this@NextcloudNativeApplication)
                                        .getWorkInfoByIdFlow(requestedWorkId)
                                        .first { work -> work == null || work.state != WorkInfo.State.RUNNING }
                                },
                                wait = { retryDelayMillis -> delay(retryDelayMillis) },
                            )
                        },
                        wait = { retryDelayMillis -> delay(retryDelayMillis) },
                    )
                },
                recordRecoveryFailure = recordRecoveryFailure,
            )
        }
    }
}

internal suspend fun runAndroidDurableUploadStartupRecovery(
    recover: suspend () -> Unit,
    recordRecoveryFailure: () -> Unit,
) {
    try {
        recover()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AndroidDurableMultipartUploadRecoveryException) {
        runCatching(recordRecoveryFailure)
    }
}
