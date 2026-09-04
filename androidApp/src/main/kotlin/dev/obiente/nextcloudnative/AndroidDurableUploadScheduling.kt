package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DurableUploadEnqueueResult
import dev.obiente.nextcloudnative.app.DurableUploadState
import kotlinx.coroutines.CancellationException

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
    schedule: suspend (AndroidDurableMultipartUploadJob) -> Unit,
): Boolean {
    var allScheduled = true
    jobs.filter { job -> job.state == DurableUploadState.Queued }.forEach { job ->
        try {
            schedule(job)
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
    followUpDelayMillis: Long = 60_000L,
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
): DurableUploadEnqueueResult.Queued {
    persist(job)
    try {
        schedule(job)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The scheduler may already own this work. Keep the journal and retry scheduling later.
    }
    return DurableUploadEnqueueResult.Queued(job.status())
}
