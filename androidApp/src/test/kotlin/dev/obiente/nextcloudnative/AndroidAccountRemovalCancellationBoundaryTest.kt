package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class AndroidAccountRemovalCancellationBoundaryTest {
    @Test
    fun parentCancellationAtPersistenceResultDeliveryDoesNotRollBackCommittedCredentials() = runBlocking {
        for (active in listOf(false, true)) {
            val events = mutableListOf<String>()
            val owner = launch {
                val job = currentCoroutineContext().job
                suspend fun commit(markCommitted: () -> Unit) = withContext(Dispatchers.IO) {
                    events += "commit"
                    markCommitted()
                    job.cancel(CancellationException("synthetic result-delivery cancellation"))
                }
                removeAndroidAccountCredentialData(
                    active = active,
                    prepareAccountRemoval = { events += "prepare" },
                    clearActiveAccount = { commit(it) },
                    persistInactiveRemoval = { commit(it) },
                    rollbackActiveRemoval = { events += "rollback" },
                    rollbackInactiveRemoval = { events += "rollback" },
                    removeQueuedUploads = { events += "cleanup" },
                    completeCommittedCleanup = { events += "complete" },
                )
                error("Parent cancellation must still propagate after the committed transition")
            }
            owner.join()
            assertTrue(owner.isCancelled)
            assertEquals(listOf("prepare", "commit"), events)
        }
    }

    @Test
    fun cancellationAfterPreparationDoesNotStrandTheRetirement() = runBlocking {
        val events = mutableListOf<String>()
        val owner = launch {
            val job = currentCoroutineContext().job
            removeAndroidAccountCredentialData(
                active = true,
                prepareAccountRemoval = { events += "prepare"; job.cancel() },
                clearActiveAccount = { markCommitted -> events += "commit"; markCommitted() },
                persistInactiveRemoval = {}, rollbackInactiveRemoval = {},
                rollbackActiveRemoval = { events += "rollback" },
                removeQueuedUploads = { events += "cleanup" },
            )
            error("Parent cancellation must propagate")
        }
        owner.join()
        assertTrue(owner.isCancelled)
        assertEquals(listOf("prepare", "rollback"), events)
    }

    @Test
    fun alreadyCancelledRemovalDoesNotPrepareOrCommit() = runBlocking {
        var prepared = false
        val owner = launch {
            currentCoroutineContext().job.cancel()
            removeAndroidAccountCredentialData(
                active = true,
                prepareAccountRemoval = { prepared = true },
                clearActiveAccount = { error("Must not commit") },
                persistInactiveRemoval = {}, rollbackInactiveRemoval = {}, rollbackActiveRemoval = {},
                removeQueuedUploads = {},
            )
        }
        owner.join()
        assertEquals(false, prepared)
    }

    @Test
    fun explicitPostCommitCancellationKeepsDurableRemovalWhilePrecommitCancellationRollsBack() = runBlocking {
        for (active in listOf(false, true)) for (commitFirst in listOf(false, true)) {
            val events = mutableListOf<String>()
            val cancelled = CancellationException("synthetic callback cancellation")
            fun persist(markCommitted: () -> Unit) {
                if (commitFirst) { events += "commit"; markCommitted() }
                throw cancelled
            }
            assertSame(cancelled, assertFailsWith<CancellationException> {
                removeAndroidAccountCredentialData(
                    active = active, clearActiveAccount = { persist(it) }, persistInactiveRemoval = { persist(it) },
                    rollbackActiveRemoval = { events += "rollback" }, rollbackInactiveRemoval = { events += "rollback" },
                    removeQueuedUploads = { error("Cancellation leaves durable cleanup for recovery") },
                )
            })
            assertEquals(if (commitFirst) listOf("commit") else listOf("rollback"), events)
        }
    }

}
