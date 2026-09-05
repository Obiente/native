package dev.obiente.nextcloudnative

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDurableUploadSchedulingRecoveryTest {
    @Test
    fun `request crossing wakeup consumption is claimed without a stale token`() = runBlocking {
        val wakeupConsumed = CompletableDeferred<Unit>()
        val releaseBatchClaim = CompletableDeferred<Unit>()
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal {
            wakeupConsumed.complete(Unit)
            releaseBatchClaim.await()
        }
        val workId = UUID.randomUUID()
        recoverySignal.requestAfterWorkStopsRunning(workId)
        val firstBatch = async { recoverySignal.await() }
        wakeupConsumed.await()

        recoverySignal.request()
        releaseBatchClaim.complete(Unit)

        assertEquals(
            AndroidDurableUploadSchedulingRecoveryBatch(
                immediate = true,
                workIdsToAwait = listOf(workId),
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

        recoverySignal.requestAfterWorkStopsRunning(workId)
        val monitoring = async {
            assertFailsWith<CancellationException> {
                monitorQueuedDurableUploadScheduling(
                    recover = {
                        recoveryRuns += 1
                        if (recoveryRuns == 2) throw expected
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
    }
}
