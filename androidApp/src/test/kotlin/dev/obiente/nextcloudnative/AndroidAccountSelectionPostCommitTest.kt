package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidAccountSelectionPostCommitTest {
    @Test
    fun cancellationAfterCommitFinishesMaintenanceBeforePropagating() = runBlocking {
        val events = mutableListOf<String>()
        val cancellation = CancellationException("selection owner stopped after commit")
        val selection = async {
            val owner = currentCoroutineContext()
            completeAndroidAccountSelectionTransition(
                transitionDispatcher = Dispatchers.Default,
                commitTransition = { markCommitted ->
                    events += "commit"
                    markCommitted()
                    owner.cancel(cancellation)
                },
                finishMaintenance = {
                    yield()
                    assertTrue(currentCoroutineContext().isActive)
                    events += "maintain"
                },
            )
        }

        assertFailsWith<CancellationException> { selection.await() }
        assertEquals(listOf("commit", "maintain"), events)
    }

    @Test
    fun cancellationBeforeCommitDoesNotRunTransitionOrMaintenance() = runBlocking {
        val events = mutableListOf<String>()
        val selection = async {
            currentCoroutineContext().cancel(CancellationException("selection stopped before commit"))
            completeAndroidAccountSelectionTransition(
                transitionDispatcher = Dispatchers.Default,
                commitTransition = {
                    events += "commit"
                    it()
                },
                finishMaintenance = { events += "maintain" },
            )
        }

        assertFailsWith<CancellationException> { selection.await() }
        assertTrue(events.isEmpty())
    }
}
