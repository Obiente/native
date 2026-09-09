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
    val workIdsToAwait: Map<String, UUID>,
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
    private val workIdsToAwait = linkedMapOf<String, UUID>()
    private val backedOffWorkIds = mutableMapOf<String, UUID>()

    fun request() {
        synchronized(monitor) {
            immediatePending = true
            wakeups.trySend(Unit)
        }
    }

    fun requestAfterWorkStopsRunning(jobId: String, workId: UUID) {
        require(jobId.isNotBlank())
        synchronized(monitor) {
            workIdsToAwait[jobId] = workId
            backedOffWorkIds[jobId] = workId
            wakeups.trySend(Unit)
        }
    }

    fun <Result> scheduleUnlessBackedOff(jobId: String, schedule: () -> Result): Result? {
        require(jobId.isNotBlank())
        return synchronized(monitor) {
            if (jobId in backedOffWorkIds) null else schedule()
        }
    }

    fun retireBackoff(jobId: String, workId: UUID): Boolean = synchronized(monitor) {
        if (jobId in workIdsToAwait) false else backedOffWorkIds.remove(jobId, workId)
    }

    suspend fun await(): AndroidDurableUploadSchedulingRecoveryBatch {
        wakeups.receive()
        beforeBatchClaim()
        return takeBatch()
    }

    fun tryTakePending(): AndroidDurableUploadSchedulingRecoveryBatch? = synchronized(monitor) {
        if (!immediatePending && workIdsToAwait.isEmpty()) null else takeBatchLocked()
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
        takeBatchLocked()
    }

    private fun takeBatchLocked(): AndroidDurableUploadSchedulingRecoveryBatch {
        while (wakeups.tryReceive().isSuccess) {
            // Every request represented by a drained token is included in the pending state below.
        }
        return AndroidDurableUploadSchedulingRecoveryBatch(
            immediate = immediatePending,
            workIdsToAwait = workIdsToAwait.toMap(),
        ).also {
            immediatePending = false
            workIdsToAwait.clear()
        }
    }
}

internal val ANDROID_DURABLE_UPLOAD_SCHEDULING_RECOVERY_SIGNAL =
    AndroidDurableUploadSchedulingRecoverySignal()

internal fun requestQueuedDurableUploadSchedulingRecovery() {
    ANDROID_DURABLE_UPLOAD_SCHEDULING_RECOVERY_SIGNAL.request()
}

internal fun requestQueuedDurableUploadSchedulingRecoveryAfterWorkStopsRunning(jobId: String, workId: UUID) {
    ANDROID_DURABLE_UPLOAD_SCHEDULING_RECOVERY_SIGNAL.requestAfterWorkStopsRunning(jobId, workId)
}

internal suspend fun monitorQueuedDurableUploadScheduling(
    recover: suspend () -> Boolean,
    awaitWorkStopsRunning: suspend (UUID) -> Unit = {},
    wait: suspend (Long) -> Unit,
    workerFailureFollowUpDelayMillis: Long =
        ANDROID_DURABLE_UPLOAD_SCHEDULING_FOLLOW_UP_DELAY_MILLIS,
    monotonicTimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    afterEmptyPendingBatchClaim: () -> Unit = {},
    recoverySignal: AndroidDurableUploadSchedulingRecoverySignal =
        ANDROID_DURABLE_UPLOAD_SCHEDULING_RECOVERY_SIGNAL,
) {
    require(workerFailureFollowUpDelayMillis > 0L)
    var immediatePending = false
    val workIdsToAwait = linkedMapOf<String, UUID>()
    val followUpDeadlinesMillis = mutableMapOf<String, Long>()
    val stoppedWorkIds = mutableMapOf<String, UUID>()
    var recoveryRetryDeadlineMillis: Long? = null

    fun addRequests(batch: AndroidDurableUploadSchedulingRecoveryBatch) {
        immediatePending = immediatePending || batch.immediate
        batch.workIdsToAwait.forEach { (jobId, workId) ->
            if (workIdsToAwait.put(jobId, workId) != workId) {
                followUpDeadlinesMillis[jobId] =
                    monotonicTimeMillis() + workerFailureFollowUpDelayMillis
                stoppedWorkIds.remove(jobId)
            }
        }
    }

    suspend fun recoverOnce() {
        val recovered = recover()
        // Cleanup can request recovery itself. Coalesce signals raised during this pass into
        // a timed retry instead of letting the same failure bypass every worker deadline.
        val pending = recoverySignal.tryTakePending()
        if (pending != null) addRequests(pending.copy(immediate = false))
        recoveryRetryDeadlineMillis = if (recovered && pending?.immediate != true) {
            null
        } else {
            monotonicTimeMillis() + workerFailureFollowUpDelayMillis
        }
    }

    recoverySignal.tryTakePending()?.let(::addRequests)
    recoverOnce()

    while (true) {
        if (!immediatePending && workIdsToAwait.isEmpty()) {
            val retryDeadline = recoveryRetryDeadlineMillis
            if (retryDeadline == null) {
                addRequests(recoverySignal.await())
            } else {
                val retryDelay = (retryDeadline - monotonicTimeMillis()).coerceAtLeast(0L)
                val step = recoverySignal.runUntilRequested { if (retryDelay > 0L) wait(retryDelay) }
                if (step is AndroidDurableUploadSchedulingRecoveryStep.Interrupted) {
                    addRequests(step.batch)
                    continue
                }
                recoverOnce()
                continue
            }
        }
        if (!immediatePending && workIdsToAwait.isEmpty()) continue
        if (immediatePending) {
            immediatePending = false
            recoverOnce()
            continue
        }

        val (jobId, workId) = workIdsToAwait.entries.first()
        if (stoppedWorkIds[jobId] != workId) {
            when (val step = recoverySignal.runUntilRequested { awaitWorkStopsRunning(workId) }) {
                AndroidDurableUploadSchedulingRecoveryStep.Completed -> {
                    if (workIdsToAwait[jobId] != workId) continue
                    stoppedWorkIds[jobId] = workId
                }
                is AndroidDurableUploadSchedulingRecoveryStep.Interrupted -> {
                    addRequests(step.batch)
                    continue
                }
            }
        }

        val remainingDelayMillis =
            (followUpDeadlinesMillis.getValue(jobId) - monotonicTimeMillis()).coerceAtLeast(0L)
        if (remainingDelayMillis > 0L) {
            val recoveryRetryDelayMillis = recoveryRetryDeadlineMillis
                ?.let { deadline -> (deadline - monotonicTimeMillis()).coerceAtLeast(0L) }
            if (recoveryRetryDelayMillis == 0L) {
                recoverOnce()
                continue
            }
            val recoveryRetryFirst =
                recoveryRetryDelayMillis != null && recoveryRetryDelayMillis < remainingDelayMillis
            when (
                val step = recoverySignal.runUntilRequested {
                    wait(if (recoveryRetryFirst) requireNotNull(recoveryRetryDelayMillis) else remainingDelayMillis)
                }
            ) {
                AndroidDurableUploadSchedulingRecoveryStep.Completed -> if (recoveryRetryFirst) {
                    recoverOnce()
                    continue
                }
                is AndroidDurableUploadSchedulingRecoveryStep.Interrupted -> {
                    addRequests(step.batch)
                    continue
                }
            }
        }
        val pendingBatch = recoverySignal.tryTakePending()
        if (pendingBatch != null) {
            addRequests(pendingBatch)
            continue
        }
        afterEmptyPendingBatchClaim()
        followUpDeadlinesMillis.remove(jobId)
        stoppedWorkIds.remove(jobId)
        workIdsToAwait.remove(jobId, workId)
        recoverySignal.retireBackoff(jobId, workId)
        recoverOnce()
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
