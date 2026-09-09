package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DurableUploadScope
import dev.obiente.nextcloudnative.app.DurableUploadState
import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudMultipartUploadRequest
import dev.obiente.nextcloudnative.app.localUploadFile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDurableUploadSchedulingRecoveryTest {
    @Test
    fun `a recovery request wakes the idle scheduling monitor without polling`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        var recoveryRuns = 0
        recoverySignal.request()

        assertFailsWith<CancellationException> {
            monitorQueuedDurableUploadScheduling(
                recover = {
                    recoveryRuns += 1
                    if (recoveryRuns == 2) throw CancellationException("Lifecycle stopped")
                    true
                },
                wait = { error("an immediate wake must not wait") },
                recoverySignal = recoverySignal,
            )
        }

        assertEquals(2, recoveryRuns)
    }

    @Test
    fun `coalesced immediate recovery preempts worker ownership and follow up waits`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val jobId = "job-1"
        val workId = UUID.randomUUID()
        val expectedCancellation = CancellationException("recovery owner stopped")
        var recoveryRuns = 0
        var ownershipWaits = 0
        var delayRuns = 0
        recoverySignal.request()
        recoverySignal.requestAfterWorkStopsRunning(jobId, workId)

        val actual = assertFailsWith<CancellationException> {
            monitorQueuedDurableUploadScheduling(
                recover = {
                    recoveryRuns += 1
                    if (recoveryRuns == 2) throw expectedCancellation
                    true
                },
                awaitWorkStopsRunning = { requestedWorkId ->
                    assertEquals(workId, requestedWorkId)
                    ownershipWaits += 1
                },
                wait = { delayMillis ->
                    assertEquals(ANDROID_DURABLE_UPLOAD_SCHEDULING_FOLLOW_UP_DELAY_MILLIS, delayMillis)
                    delayRuns += 1
                },
                recoverySignal = recoverySignal,
            )
        }

        assertTrue(actual === expectedCancellation)
        assertEquals(2, recoveryRuns)
        assertEquals(0, ownershipWaits)
        assertEquals(0, delayRuns)
    }

    @Test
    fun `failed idle reconciliation retries without a new signal and success stops polling`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val recovered = CompletableDeferred<Unit>()
        val waits = mutableListOf<Long>()
        var recoveryRuns = 0
        var nowMillis = 1_000L
        val monitor = async {
            monitorQueuedDurableUploadScheduling(
                recover = {
                    recoveryRuns += 1
                    if (recoveryRuns == 2) recovered.complete(Unit)
                    recoveryRuns == 2
                },
                wait = { delayMillis ->
                    waits += delayMillis
                    nowMillis += delayMillis
                },
                monotonicTimeMillis = { nowMillis },
                recoverySignal = recoverySignal,
            )
        }
        try {
            recovered.await()
            yield()
            assertEquals(2, recoveryRuns)
            assertEquals(listOf(60_000L), waits)
        } finally {
            monitor.cancelAndJoin()
        }
    }

    @Test
    fun `request crossing wakeup consumption is claimed without a stale token`() = runBlocking {
        val wakeupConsumed = CompletableDeferred<Unit>()
        val releaseBatchClaim = CompletableDeferred<Unit>()
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal {
            wakeupConsumed.complete(Unit)
            releaseBatchClaim.await()
        }
        val workId = UUID.randomUUID()
        recoverySignal.requestAfterWorkStopsRunning("job-1", workId)
        val firstBatch = async { recoverySignal.await() }
        wakeupConsumed.await()

        recoverySignal.request()
        releaseBatchClaim.complete(Unit)

        assertEquals(
            AndroidDurableUploadSchedulingRecoveryBatch(
                immediate = true,
                workIdsToAwait = mapOf("job-1" to workId),
            ),
            firstBatch.await(),
        )
        val nextBatch = async { recoverySignal.await() }
        yield()
        assertFalse(nextBatch.isCompleted)
        nextBatch.cancel()
    }

    @Test
    fun `immediate recovery interrupts an unrelated worker follow up delay`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val workId = UUID.randomUUID()
        val delayEntered = CompletableDeferred<Unit>()
        val expected = CancellationException("monitor stopped after immediate recovery")
        var recoveryRuns = 0
        val scheduledJobIds = mutableListOf<String>()

        recoverySignal.requestAfterWorkStopsRunning("job-1", workId)
        val monitoring = async {
            assertFailsWith<CancellationException> {
                monitorQueuedDurableUploadScheduling(
                    recover = {
                        recoveryRuns += 1
                        recoverySignal.scheduleUnlessBackedOff("job-1") {
                            scheduledJobIds += "job-1"
                        }
                        recoverySignal.scheduleUnlessBackedOff("job-2") {
                            scheduledJobIds += "job-2"
                        }
                        if (recoveryRuns == 2) throw expected
                        true
                    },
                    awaitWorkStopsRunning = { requestedWorkId ->
                        assertEquals(workId, requestedWorkId)
                    },
                    wait = {
                        delayEntered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    },
                    recoverySignal = recoverySignal,
                )
            }
        }

        delayEntered.await()
        recoverySignal.request()

        assertTrue(monitoring.await() === expected)
        assertEquals(2, recoveryRuns)
        assertEquals(listOf("job-2", "job-2"), scheduledJobIds)
    }

    @Test
    fun `persistent cleanup failure yields through immediate recovery to worker backoff expiry`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val workId = UUID.randomUUID()
        val initialRetryWaitEntered = CompletableDeferred<Unit>()
        val backoffWaitEntered = CompletableDeferred<Unit>()
        val expected = CancellationException("monitor stopped after backed off upload recovered")
        val scheduledJobIds = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        var recoveryRuns = 0
        var secondJobQueued = false
        var nowMillis = 1_000L

        val monitoring = async {
            assertFailsWith<CancellationException> {
                monitorQueuedDurableUploadScheduling(
                    recover = {
                        recoveryRuns += 1
                        if (secondJobQueued) {
                            recoverySignal.scheduleUnlessBackedOff("job-2") {
                                scheduledJobIds += "job-2"
                            }
                        }
                        if (scheduledJobIds.isNotEmpty()) throw expected
                        false
                    },
                    awaitWorkStopsRunning = { requestedWorkId -> assertEquals(workId, requestedWorkId) },
                    wait = { delayMillis ->
                        waits += delayMillis
                        when (waits.size) {
                            1 -> {
                                initialRetryWaitEntered.complete(Unit)
                                CompletableDeferred<Unit>().await()
                            }
                            2 -> {
                                backoffWaitEntered.complete(Unit)
                                CompletableDeferred<Unit>().await()
                            }
                            else -> nowMillis += delayMillis
                        }
                    },
                    workerFailureFollowUpDelayMillis = 60_000L,
                    monotonicTimeMillis = { nowMillis },
                    recoverySignal = recoverySignal,
                )
            }
        }

        initialRetryWaitEntered.await()
        secondJobQueued = true
        recoverySignal.requestAfterWorkStopsRunning("job-2", workId)
        backoffWaitEntered.await()
        recoverySignal.request()

        assertTrue(monitoring.await() === expected)
        assertEquals(3, recoveryRuns)
        assertEquals(listOf(60_000L, 60_000L, 60_000L), waits)
        assertEquals(listOf("job-2"), scheduledJobIds)
    }

    @Test
    fun `immediate recovery preserves the failed job deadline`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val workId = UUID.randomUUID()
        val firstDelayEntered = CompletableDeferred<Unit>()
        val expected = CancellationException("monitor stopped after failed job recovery")
        val waits = mutableListOf<Long>()
        val scheduledJobIds = mutableListOf<String>()
        var recoveryRuns = 0
        var nowMillis = 1_000L

        recoverySignal.requestAfterWorkStopsRunning("job-1", workId)
        val monitoring = async {
            assertFailsWith<CancellationException> {
                monitorQueuedDurableUploadScheduling(
                    recover = {
                        recoveryRuns += 1
                        recoverySignal.scheduleUnlessBackedOff("job-1") {
                            scheduledJobIds += "job-1"
                        }
                        if (recoveryRuns == 3) throw expected
                        true
                    },
                    awaitWorkStopsRunning = { requestedWorkId ->
                        assertEquals(workId, requestedWorkId)
                    },
                    wait = { delayMillis ->
                        waits += delayMillis
                        if (waits.size == 1) {
                            firstDelayEntered.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        }
                    },
                    workerFailureFollowUpDelayMillis = 60_000L,
                    monotonicTimeMillis = { nowMillis },
                    recoverySignal = recoverySignal,
                )
            }
        }

        firstDelayEntered.await()
        nowMillis += 25_000L
        recoverySignal.request()

        assertTrue(monitoring.await() === expected)
        assertEquals(listOf(60_000L, 35_000L), waits)
        assertEquals(listOf("job-1"), scheduledJobIds)
    }

    @Test
    fun `replacement work resets and coalesces the failed job deadline`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val replacedWorkId = UUID.randomUUID()
        val supersededWorkId = UUID.randomUUID()
        val replacementWorkId = UUID.randomUUID()
        val expected = CancellationException("monitor stopped after replacement recovery")
        val awaitedWorkIds = mutableListOf<UUID>()
        val waits = mutableListOf<Long>()
        val scheduledJobIds = mutableListOf<String>()
        var recoveryRuns = 0

        recoverySignal.requestAfterWorkStopsRunning("job-1", replacedWorkId)
        val actual = assertFailsWith<CancellationException> {
            monitorQueuedDurableUploadScheduling(
                recover = {
                    recoveryRuns += 1
                    recoverySignal.scheduleUnlessBackedOff("job-1") {
                        scheduledJobIds += "job-1"
                    }
                    if (recoveryRuns == 2) throw expected
                    true
                },
                awaitWorkStopsRunning = { workId -> awaitedWorkIds += workId },
                wait = { delayMillis ->
                    waits += delayMillis
                    if (waits.size == 1) {
                        recoverySignal.requestAfterWorkStopsRunning("job-1", supersededWorkId)
                        repeat(100) {
                            recoverySignal.requestAfterWorkStopsRunning("job-1", replacementWorkId)
                        }
                    }
                },
                workerFailureFollowUpDelayMillis = 60_000L,
                monotonicTimeMillis = { 1_000L },
                recoverySignal = recoverySignal,
            )
        }

        assertTrue(actual === expected)
        assertEquals(listOf(replacedWorkId, replacementWorkId), awaitedWorkIds)
        assertEquals(listOf(60_000L, 60_000L), waits)
        assertEquals(listOf("job-1"), scheduledJobIds)
    }

    @Test
    fun `immediate reconciliation skips backed off upload and schedules unrelated work`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val backedOff = fixtureQueuedJob(index = 1)
        val unrelated = fixtureQueuedJob(index = 2)
        val attempted = mutableListOf<String>()
        recoverySignal.requestAfterWorkStopsRunning(backedOff.id, UUID.randomUUID())

        val allScheduled = reconcileQueuedDurableUploads(
            jobs = listOf(backedOff, unrelated),
            cleanupCapability = { error("Queued uploads must not enter local cleanup.") },
            schedule = { job ->
                recoverySignal.scheduleUnlessBackedOff(job.id) { attempted += job.id }
            },
        )

        assertTrue(allScheduled)
        assertEquals(listOf(unrelated.id), attempted)
    }

    @Test
    fun `backoff exclusion does not delay terminal capability cleanup`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val terminalCleanup = fixtureQueuedJob(index = 1).copy(
            state = DurableUploadState.Failed,
            capabilityCleanupPending = true,
        )
        var cleaned = false
        recoverySignal.requestAfterWorkStopsRunning(terminalCleanup.id, UUID.randomUUID())

        val allScheduled = reconcileQueuedDurableUploads(
            jobs = listOf(terminalCleanup),
            cleanupCapability = { cleaned = true },
            schedule = { job ->
                recoverySignal.scheduleUnlessBackedOff(job.id) {
                    error("Terminal cleanup must not schedule upload work.")
                }
            },
        )

        assertTrue(allScheduled)
        assertTrue(cleaned)
    }

    @Test
    fun `replacement request in the post drain gap remains excluded`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val replacedWorkId = UUID.randomUUID()
        val replacementWorkId = UUID.randomUUID()
        val expected = CancellationException("monitor stopped after replacement recovery")
        val awaitedWorkIds = mutableListOf<UUID>()
        val scheduledJobIds = mutableListOf<String>()
        var recoveryRuns = 0
        var gapRuns = 0

        recoverySignal.requestAfterWorkStopsRunning("job-1", replacedWorkId)
        val actual = assertFailsWith<CancellationException> {
            monitorQueuedDurableUploadScheduling(
                recover = {
                    recoveryRuns += 1
                    recoverySignal.scheduleUnlessBackedOff("job-1") {
                        scheduledJobIds += "job-1"
                    }
                    if (recoveryRuns == 3) throw expected
                    true
                },
                awaitWorkStopsRunning = { workId -> awaitedWorkIds += workId },
                wait = {},
                workerFailureFollowUpDelayMillis = 60_000L,
                monotonicTimeMillis = { 1_000L },
                afterEmptyPendingBatchClaim = {
                    if (gapRuns++ == 0) {
                        recoverySignal.requestAfterWorkStopsRunning("job-1", replacementWorkId)
                    }
                },
                recoverySignal = recoverySignal,
            )
        }

        assertTrue(actual === expected)
        assertEquals(listOf(replacedWorkId, replacementWorkId), awaitedWorkIds)
        assertEquals(listOf("job-1"), scheduledJobIds)
    }

    @Test
    fun `same work request in the post drain gap starts a fresh backoff`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val workId = UUID.randomUUID()
        val expected = CancellationException("monitor stopped after repeated work recovery")
        val awaitedWorkIds = mutableListOf<UUID>()
        val waits = mutableListOf<Long>()
        val scheduledJobIds = mutableListOf<String>()
        var recoveryRuns = 0
        var gapRuns = 0

        recoverySignal.requestAfterWorkStopsRunning("job-1", workId)
        val actual = assertFailsWith<CancellationException> {
            monitorQueuedDurableUploadScheduling(
                recover = {
                    recoveryRuns += 1
                    recoverySignal.scheduleUnlessBackedOff("job-1") {
                        scheduledJobIds += "job-1"
                    }
                    if (recoveryRuns == 3) throw expected
                    true
                },
                awaitWorkStopsRunning = { requestedWorkId -> awaitedWorkIds += requestedWorkId },
                wait = { delayMillis -> waits += delayMillis },
                workerFailureFollowUpDelayMillis = 60_000L,
                monotonicTimeMillis = { 1_000L },
                afterEmptyPendingBatchClaim = {
                    if (gapRuns++ == 0) {
                        recoverySignal.requestAfterWorkStopsRunning("job-1", workId)
                    }
                },
                recoverySignal = recoverySignal,
            )
        }

        assertTrue(actual === expected)
        assertEquals(listOf(workId, workId), awaitedWorkIds)
        assertEquals(listOf(60_000L, 60_000L), waits)
        assertEquals(listOf("job-1"), scheduledJobIds)
    }

    @Test
    fun `coalesced failed jobs age through one follow up interval`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val firstWorkId = UUID.randomUUID()
        val secondWorkId = UUID.randomUUID()
        val expected = CancellationException("monitor stopped after both jobs recovered")
        val awaitedWorkIds = mutableListOf<UUID>()
        val waits = mutableListOf<Long>()
        var recoveryRuns = 0
        var nowMillis = 1_000L

        recoverySignal.requestAfterWorkStopsRunning("job-1", firstWorkId)
        recoverySignal.requestAfterWorkStopsRunning("job-2", secondWorkId)
        val actual = assertFailsWith<CancellationException> {
            monitorQueuedDurableUploadScheduling(
                recover = {
                    recoveryRuns += 1
                    if (recoveryRuns == 3) throw expected
                    true
                },
                awaitWorkStopsRunning = { workId -> awaitedWorkIds += workId },
                wait = { delayMillis ->
                    waits += delayMillis
                    nowMillis += delayMillis
                },
                workerFailureFollowUpDelayMillis = 60_000L,
                monotonicTimeMillis = { nowMillis },
                recoverySignal = recoverySignal,
            )
        }

        assertTrue(actual === expected)
        assertEquals(listOf(firstWorkId, secondWorkId), awaitedWorkIds)
        assertEquals(listOf(60_000L), waits)
    }

    private fun fixtureQueuedJob(index: Int): AndroidDurableMultipartUploadJob {
        val scope = DurableUploadScope("deck-attachment", index.toString())
        val request = NextcloudMultipartUploadRequest(
            method = NextcloudApiMethod.POST,
            relativePath = "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/$index/attachments",
            file = localUploadFile(
                selectionId = "selection-${index.toString().padStart(16, '0')}",
                displayName = "fixture-$index.txt",
                mimeType = "text/plain",
                sizeBytes = 16L,
            ),
            maximumFileBytes = 1_024L,
        )
        return AndroidDurableMultipartUploadJob(
            id = "upload-${index.toString().padStart(16, '0')}",
            accountId = index.toString(16).padStart(32, '0'),
            scope = scope,
            resource = resolveDurableUploadResource(scope, request),
            request = request,
            state = DurableUploadState.Queued,
            message = null,
            updatedAtEpochMillis = index.toLong(),
        )
    }
}
