package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DurableUploadEnqueueResult
import dev.obiente.nextcloudnative.app.DurableUploadState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
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

internal class AndroidDurableUploadSchedulingRecoverySignal {
    private val monitor = Any()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var immediatePending = false
    private val workIdsToAwait = linkedSetOf<UUID>()

    fun request() {
        synchronized(monitor) {
            immediatePending = true
        }
        wakeups.trySend(Unit)
    }

    fun requestAfterWorkStopsRunning(workId: UUID) {
        synchronized(monitor) {
            workIdsToAwait += workId
        }
        wakeups.trySend(Unit)
    }

    suspend fun await(): AndroidDurableUploadSchedulingRecoveryBatch {
        wakeups.receive()
        return synchronized(monitor) {
            AndroidDurableUploadSchedulingRecoveryBatch(
                immediate = immediatePending,
                workIdsToAwait = workIdsToAwait.toList(),
            ).also {
                immediatePending = false
                workIdsToAwait.clear()
            }
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
    while (true) {
        val requests = recoverySignal.await()
        if (requests.workIdsToAwait.isNotEmpty()) {
            requests.workIdsToAwait.forEach { workId -> awaitWorkStopsRunning(workId) }
            wait(workerFailureFollowUpDelayMillis)
        }
        if (requests.immediate || requests.workIdsToAwait.isNotEmpty()) recover()
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
    schedulerOwns: suspend (AndroidDurableMultipartUploadJob) -> Boolean = { false },
    schedule: suspend (AndroidDurableMultipartUploadJob) -> Unit,
): Boolean {
    var allScheduled = true
    jobs.filter { job -> job.state == DurableUploadState.Queued }.forEach { job ->
        try {
            if (!schedulerOwns(job)) schedule(job)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            allScheduled = false
        }
    }
    return allScheduled
}

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
        } catch (failure: AndroidDurableMultipartUploadRecoveryException) {
            if (failure.disposition == DurableUploadQueueRecoveryDisposition.Quarantine) {
                if (!recoveryFailureReported) runCatching(recordRecoveryFailure)
                return
            }
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
