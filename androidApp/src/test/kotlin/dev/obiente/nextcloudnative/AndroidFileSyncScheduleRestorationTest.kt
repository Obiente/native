package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class AndroidFileSyncScheduleRestorationTest {
    private val session = NextcloudSession("https://cloud.example.test", "alice", "password")

    @Test
    fun accountRemovalWaitsForRestorationDiscovery() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var current: NextcloudSession? = session
        val restore = async {
            restoreAndroidFileSyncScheduleWhileCurrent(session, { current }, 0, guard) {
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        val remove = async { guard.withAccount(NextcloudDocumentIds.accountKey(session)) { current = null } }
        yield()
        assertFalse(remove.isCompleted)
        finish.complete(Unit)
        withTimeout(5_000) { restore.await(); remove.await() }
    }

    @Test
    fun changedCredentialsCannotStartDiscovery() = runBlocking {
        var called = false
        val result = restoreAndroidFileSyncScheduleWhileCurrent(
            session, { session.copy(appPassword = "replacement") }, 0, AndroidAccountOperationGuard(),
        ) { called = true }
        assertFalse(called)
        assertEquals(BackgroundSyncWorkerDisposition.Complete, result)
    }

    @Test
    fun permanentFailuresStopAndTransientFailuresHaveABoundedBudget() {
        for (status in listOf(400, 401, 403, 404)) {
            assertEquals(BackgroundSyncWorkerDisposition.Complete, scheduleRestorationFailureDisposition(0, AndroidOcsRequestFailure(status)))
        }
        assertEquals(BackgroundSyncWorkerDisposition.Complete, scheduleRestorationFailureDisposition(0, IllegalStateException("malformed state")))
        for (failure in listOf(IOException(), IllegalStateException("storage unavailable", IOException()), AndroidOcsRequestFailure(429), AndroidOcsRequestFailure(503))) {
            assertEquals(BackgroundSyncWorkerDisposition.Retry, scheduleRestorationFailureDisposition(0, failure))
            assertEquals(BackgroundSyncWorkerDisposition.Complete, scheduleRestorationFailureDisposition(2, failure))
        }
        assertEquals(BackgroundSyncWorkerDisposition.Complete, scheduleRestorationFailureDisposition(20))
        assertEquals(BackgroundSyncWorkerDisposition.Complete, scheduleRestorationFailureDisposition(0, IllegalStateException("truncated", java.io.EOFException())))
        assertFailsWith<CancellationException> { scheduleRestorationFailureDisposition(0, IllegalStateException("wrapped", CancellationException())) }
    }

    @Test
    fun cancellationRemainsControlFlow(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            restoreAndroidFileSyncScheduleWhileCurrent(session, { session }, 0, AndroidAccountOperationGuard()) {
                throw CancellationException()
            }
        }
    }
}
