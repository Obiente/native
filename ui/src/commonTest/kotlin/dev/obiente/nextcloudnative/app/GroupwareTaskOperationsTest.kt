package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GroupwareTaskOperationsTest {
    @Test
    fun delayedPutAndDeleteKeepRecoveryBehindTheActiveRequest() = runBlocking {
        listOf("PUT", "DELETE").forEach { method ->
            val operations = GroupwareTaskOperations()
            val response = CompletableDeferred<Unit>()
            var recoveryRecord: String? = method
            var serverChanged = false
            var verified = false
            val write = launch(start = CoroutineStart.UNDISPATCHED) {
                operations.mutate {
                    response.await()
                    serverChanged = true
                }
            }
            val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
                operations.recover {
                    assertFalse(operations.mutationRunning)
                    assertTrue(serverChanged)
                    verified = true
                    recoveryRecord = null
                }
            }
            assertTrue(operations.busy)
            assertTrue(operations.mutationRunning)
            assertEquals(method, recoveryRecord)
            assertFalse(verified)
            operations.mutate { error("A duplicate write must not be queued") }
            response.complete(Unit)
            write.join()
            refresh.join()
            assertTrue(verified)
            assertEquals(null, recoveryRecord)
            assertFalse(operations.busy)
        }
    }

    @Test
    fun failedOrCancelledRequestReleasesTheGateWithoutClearingRecovery() = runBlocking {
        val operations = GroupwareTaskOperations()
        val durableRecord = "pending-task-change"
        val response = CompletableDeferred<Unit>()
        val write = launch(start = CoroutineStart.UNDISPATCHED) { operations.mutate { response.await() } }
        write.cancelAndJoin()
        assertFalse(operations.busy)
        assertFalse(operations.mutationRunning)
        operations.recover { assertEquals("pending-task-change", durableRecord) }
        assertFailsWith<IllegalStateException> { operations.mutate { error("response lost") } }
        assertFalse(operations.busy)
        assertEquals("pending-task-change", durableRecord)
    }

    @Test
    fun refreshOwnsRecoveryUntilItCompletesAndCancellationDoesNotUnlockAnotherOperation() = runBlocking {
        val operations = GroupwareTaskOperations()
        val read = CompletableDeferred<Unit>()
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) { operations.recover { read.await() } }
        operations.mutate { error("No write during recovery verification") }
        val secondRefresh = async(start = CoroutineStart.UNDISPATCHED) { operations.recover { error("cancelled") } }
        secondRefresh.cancel()
        assertFailsWith<CancellationException> { secondRefresh.await() }
        assertTrue(operations.busy)
        refresh.cancelAndJoin()
        assertFalse(operations.busy)
        var wrote = false
        operations.mutate { wrote = true }
        assertTrue(wrote)
    }
}
