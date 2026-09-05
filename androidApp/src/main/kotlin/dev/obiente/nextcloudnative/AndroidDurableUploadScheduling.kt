package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DurableUploadEnqueueResult
import dev.obiente.nextcloudnative.app.DurableUploadState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidDurableUploadStartCoordinator {
    private val monitor = Any()
    private val jobLeases = mutableMapOf<String, JobLease>()

    suspend fun <Result> withJob(jobId: String, action: suspend () -> Result): Result {
        require(jobId.isNotBlank())
        val lease = synchronized(monitor) {
            jobLeases.getOrPut(jobId) { JobLease() }.also { it.references += 1 }
        }
        return try {
            lease.mutex.withLock { action() }
        } finally {
            synchronized(monitor) {
                lease.references -= 1
                if (lease.references == 0) jobLeases.remove(jobId, lease)
            }
        }
    }

    private class JobLease(
        val mutex: Mutex = Mutex(),
        var references: Int = 0,
    )
}

private val ANDROID_DURABLE_UPLOAD_START_COORDINATOR = AndroidDurableUploadStartCoordinator()

internal const val ANDROID_DURABLE_UPLOAD_SCHEDULING_FOLLOW_UP_DELAY_MILLIS = 60_000L

internal data class AndroidDurableUploadSchedulingRecoveryBatch(
    val immediate: Boolean,
    val workIdsToAwait: List<UUID>,
)

internal sealed interface AndroidDurableUploadSchedulingRecoveryStep {
    data object Completed : AndroidDurableUploadSchedulingRecoveryStep

    data class Interrupted(
        val batch: AndroidDurableUploadSchedulingRecoveryBatch,
    ) : AndroidDurableUploadSchedulingRecoveryStep
}

internal class AndroidDurableUploadSchedulingRecoverySignal(
    private val beforeBatchClaim: suspend () -> Unit = {},
) {
    private val monitor = Any()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var immediatePending = false
    private val workIdsToAwait = linkedSetOf<UUID>()

    fun request() {
        synchronized(monitor) {
            immediatePending = true
            wakeups.trySend(Unit)
        }
    }

    fun requestAfterWorkStopsRunning(workId: UUID) {
        synchronized(monitor) {
            workIdsToAwait += workId
            wakeups.trySend(Unit)
        }
    }

    suspend fun await(): AndroidDurableUploadSchedulingRecoveryBatch {
        wakeups.receive()
        beforeBatchClaim()
        return takeBatch()
    }

    suspend fun runUntilRequested(
        action: suspend () -> Unit,
    ): AndroidDurableUploadSchedulingRecoveryStep = coroutineScope {
        val running = async(start = CoroutineStart.UNDISPATCHED) { action() }
        try {
            select {
                running.onAwait { AndroidDurableUploadSchedulingRecoveryStep.Completed }
                wakeups.onReceive {
                    beforeBatchClaim()
                    AndroidDurableUploadSchedulingRecoveryStep.Interrupted(takeBatch())
                }
            }
        } finally {
            running.cancel()
        }
    }

    private fun takeBatch(): AndroidDurableUploadSchedulingRecoveryBatch = synchronized(monitor) {
        while (wakeups.tryReceive().isSuccess) {
            // Every request represented by a drained token is included in the pending state below.
        }
        AndroidDurableUploadSchedulingRecoveryBatch(
            immediate = immediatePending,
            workIdsToAwait = workIdsToAwait.toList(),
        ).also {
            immediatePending = false
            workIdsToAwait.clear()
        }
    }
}

private val ANDROID_DURABLE_UPLOAD_SCHEDULING_RECOVERY_SIGNAL =
    AndroidDurableUploadSchedulingRecoverySignal()

internal fun requestQueuedDurableUploadSchedulingRecovery() {
    ANDROID_DURABLE_UPLOAD_SCHEDULING_RECOVERY_SIGNAL.request()
}

internal fun requestQueuedDurableUploadSchedulingRecoveryAfterWorkStopsRunning(workId: UUID) {
    ANDROID_DURABLE_UPLOAD_SCHEDULING_RECOVERY_SIGNAL.requestAfterWorkStopsRunning(workId)
}

internal suspend fun monitorQueuedDurableUploadScheduling(
    recover: suspend () -> Unit,
    awaitWorkStopsRunning: suspend (UUID) -> Unit = {},
    wait: suspend (Long) -> Unit,
    workerFailureFollowUpDelayMillis: Long =
        ANDROID_DURABLE_UPLOAD_SCHEDULING_FOLLOW_UP_DELAY_MILLIS,
    recoverySignal: AndroidDurableUploadSchedulingRecoverySignal =
        ANDROID_DURABLE_UPLOAD_SCHEDULING_RECOVERY_SIGNAL,
) {
    require(workerFailureFollowUpDelayMillis > 0L)
    recover()
    var immediatePending = false
    val workIdsToAwait = linkedSetOf<UUID>()

    fun addRequests(batch: AndroidDurableUploadSchedulingRecoveryBatch) {
        immediatePending = immediatePending || batch.immediate
        workIdsToAwait += batch.workIdsToAwait
    }

    while (true) {
        if (!immediatePending && workIdsToAwait.isEmpty()) addRequests(recoverySignal.await())
        if (!immediatePending && workIdsToAwait.isEmpty()) continue
        if (immediatePending) {
            immediatePending = false
            recover()
            continue
        }

        val workId = workIdsToAwait.first()
        when (val step = recoverySignal.runUntilRequested { awaitWorkStopsRunning(workId) }) {
            AndroidDurableUploadSchedulingRecoveryStep.Completed -> Unit
            is AndroidDurableUploadSchedulingRecoveryStep.Interrupted -> {
                addRequests(step.batch)
                continue
            }
        }
        when (
            val step = recoverySignal.runUntilRequested {
                wait(workerFailureFollowUpDelayMillis)
            }
        ) {
            AndroidDurableUploadSchedulingRecoveryStep.Completed -> {
                workIdsToAwait.remove(workId)
                recover()
            }
            is AndroidDurableUploadSchedulingRecoveryStep.Interrupted -> addRequests(step.batch)
        }
    }
}

internal suspend fun awaitDurableUploadWorkToStopRunning(
    workId: UUID,
    retryDelayMillis: Long = 1_000L,
    awaitWorkStopsRunning: suspend (UUID) -> Unit,
    wait: suspend (Long) -> Unit,
) {
    require(retryDelayMillis > 0L)
    while (true) {
        try {
            awaitWorkStopsRunning(workId)
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            wait(retryDelayMillis)
        }
    }
}

internal suspend fun claimQueuedDurableUploadForExecution(
    jobId: String,
    coordinator: AndroidDurableUploadStartCoordinator = ANDROID_DURABLE_UPLOAD_START_COORDINATOR,
    claim: suspend () -> AndroidDurableMultipartUploadJob?,
): AndroidDurableMultipartUploadJob? = coordinator.withJob(jobId, claim)

internal suspend fun replaceDeferredDurableUploadWork(
    expected: AndroidDurableMultipartUploadJob,
    load: (String) -> AndroidDurableMultipartUploadJob?,
    replace: suspend (AndroidDurableMultipartUploadJob) -> Unit,
    coordinator: AndroidDurableUploadStartCoordinator = ANDROID_DURABLE_UPLOAD_START_COORDINATOR,
): Boolean = coordinator.withJob(expected.id) {
    val current = load(expected.id)
    if (
        current == null ||
        current.accountId != expected.accountId ||
        current.state != DurableUploadState.Queued
    ) {
        return@withJob false
    }
    replace(current)
    true
}

internal suspend fun constructAndReconcileQueuedDurableUploads(
    createReconciler: () -> suspend () -> Boolean,
): Boolean {
    val reconcile = try {
        createReconciler()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw AndroidDurableMultipartUploadRecoveryException(failure)
    }
    return reconcile()
}

internal suspend fun reconcileQueuedDurableUploads(
    jobs: List<AndroidDurableMultipartUploadJob>,
    allowQueuedScheduling: Boolean = true,
    schedulerOwns: suspend (AndroidDurableMultipartUploadJob) -> Boolean = { false },
    cleanupCapability: suspend (AndroidDurableMultipartUploadJob) -> Unit,
    schedule: suspend (AndroidDurableMultipartUploadJob) -> Unit,
): Boolean {
    var allScheduled = true
    jobs.filter { job -> job.requiresSchedulingRecovery(allowQueuedScheduling) }.forEach { job ->
        try {
            if (job.capabilityCleanupPending) {
                cleanupCapability(job)
            } else if (!schedulerOwns(job)) {
                schedule(job)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            allScheduled = false
        }
    }
    return allScheduled
}

private fun AndroidDurableMultipartUploadJob.requiresSchedulingRecovery(
    allowQueuedScheduling: Boolean,
): Boolean = capabilityCleanupPending || (allowQueuedScheduling && state == DurableUploadState.Queued)

internal suspend fun retryQueuedDurableUploadScheduling(
    retryDelaysMillis: List<Long> = listOf(1_000L, 5_000L),
    reconcile: suspend () -> Boolean,
    wait: suspend (Long) -> Unit,
): Boolean {
    if (reconcile()) return true
    retryDelaysMillis.forEach { delayMillis ->
        require(delayMillis >= 0L)
        wait(delayMillis)
        if (reconcile()) return true
    }
    return false
}

internal suspend fun keepRetryingQueuedDurableUploadScheduling(
    retryDelaysMillis: List<Long> = listOf(1_000L, 5_000L),
    followUpDelayMillis: Long = ANDROID_DURABLE_UPLOAD_SCHEDULING_FOLLOW_UP_DELAY_MILLIS,
    reconcile: suspend () -> Boolean,
    wait: suspend (Long) -> Unit,
    recordRecoveryFailure: () -> Unit = {},
) {
    require(followUpDelayMillis > 0L)
    var recoveryFailureReported = false
    while (true) {
        val recovered = try {
            retryQueuedDurableUploadScheduling(retryDelaysMillis, reconcile, wait)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AndroidDurableMultipartUploadRecoveryException) {
            false
        }
        if (recovered) return
        if (!recoveryFailureReported) {
            runCatching(recordRecoveryFailure)
            recoveryFailureReported = true
        }
        wait(followUpDelayMillis)
    }
}

/**
 * Persists the upload before asking WorkManager to schedule it. WorkManager acceptance and its
 * completion signal are not atomic, so a scheduling failure after persistence is ambiguous: the
 * durable queued job must remain authoritative and can be scheduled again after process restart.
 */
internal suspend fun persistAndScheduleDurableUpload(
    job: AndroidDurableMultipartUploadJob,
    persist: (AndroidDurableMultipartUploadJob) -> Unit,
    schedule: suspend (AndroidDurableMultipartUploadJob) -> Unit,
    requestRecovery: () -> Unit = ::requestQueuedDurableUploadSchedulingRecovery,
): DurableUploadEnqueueResult.Queued {
    persist(job)
    try {
        schedule(job)
    } catch (cancelled: CancellationException) {
        runCatching(requestRecovery)
        throw cancelled
    } catch (_: Exception) {
        runCatching(requestRecovery)
    }
    return DurableUploadEnqueueResult.Queued(job.status())
}
