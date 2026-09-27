package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AndroidExternalHandoffRegistrationTest {
    private val record = AndroidExternalFileHandoffRecord(
        "handoff-test", "synthetic-account",
        NextcloudFile("Notes.txt", "Notes.txt", false, "text/plain", 4L, null, null, false, "v1"),
        1L, 100L,
    )

    @Test
    fun cancellationBeforeRegistrationStillDiscardsTheStagedCopy(): Unit = runBlocking {
        val cancelled = CancellationException("fixture")
        var discarded = false
        val observed = assertFailsWith<CancellationException> {
            registerAndroidExternalFileHandoff(
                register = { throw cancelled },
                revoke = { error("No capability was registered") },
                discardStagedCopy = { discarded = true },
            )
        }
        assertCancellationPreserved(cancelled, observed)
        assertTrue(discarded)
    }

    @Test
    fun promptCancellationAfterPersistenceRevokesTheOtherwiseLostRecord(): Unit = runBlocking {
        val cancelled = CancellationException("fixture")
        var persisted: AndroidExternalFileHandoffRecord? = null
        var discarded = false
        var observed: CancellationException? = null
        val task = launch {
            val owner = currentCoroutineContext().job
            try {
                registerAndroidExternalFileHandoff(
                    register = { persisted = record; owner.cancel(cancelled); record },
                    revoke = { assertSame(record, it); persisted = null },
                    discardStagedCopy = { discarded = true },
                )
                error("A cancelled registration must not be delivered")
            } catch (failure: CancellationException) {
                observed = failure
            }
        }
        task.join()
        assertCancellationPreserved(cancelled, observed)
        assertEquals(null, persisted)
        assertTrue(discarded)
        assertFalse(task.isActive)
    }

    @Test
    fun failedRevocationDoesNotReplaceCancellationOrSkipStagedCleanup(): Unit = runBlocking {
        val cancelled = CancellationException("fixture")
        val cleanup = java.io.IOException("revocation fixture")
        val stagedCleanup = java.io.IOException("staging fixture")
        var discarded = false
        var observed: CancellationException? = null
        val task = launch {
            val owner = currentCoroutineContext().job
            try {
                registerAndroidExternalFileHandoff(
                    register = { owner.cancel(cancelled); record },
                    revoke = { throw cleanup },
                    discardStagedCopy = { discarded = true; throw stagedCleanup },
                )
            } catch (failure: CancellationException) {
                observed = failure
            }
        }
        task.join()
        assertCancellationPreserved(cancelled, observed)
        assertTrue(cancellationChain(observed).flatMap { it.suppressed.toList() }.any { it === cleanup })
        assertTrue(cancellationChain(observed).flatMap { it.suppressed.toList() }.any { it === stagedCleanup })
        assertTrue(cancelled.suppressed.any { it === cleanup })
        assertTrue(cancelled.suppressed.any { it === stagedCleanup })
        assertTrue(discarded)
    }

    private fun assertCancellationPreserved(expected: CancellationException, observed: CancellationException?) {
        // Coroutine stack recovery may copy an exception while retaining its original cause.
        val chain = cancellationChain(observed)
        assertTrue(chain.any { it === expected })
        assertTrue(chain.all { it is CancellationException })
    }

    private fun cancellationChain(observed: CancellationException?): List<Throwable> =
        generateSequence<Throwable>(observed) { it.cause }.take(8).toList()

}
