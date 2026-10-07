package dev.obiente.nextcloudnative.app

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopFileMutationFollowUpsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diagnostics = Collections.synchronizedList(mutableListOf<Pair<String, SupportDiagnosticEventDraft>>())
    private val releases = mutableListOf<CountDownLatch>()

    @AfterTest
    fun tearDown() {
        releases.forEach(CountDownLatch::countDown)
        scope.cancel()
    }

    @Test
    fun `completed local bookkeeping is reported as completed`(): Unit = runBlocking {
        val followUps = followUps()

        assertEquals(FileMutationLocalFollowUp.Completed, followUps.afterConfirmedMutation("account") { null })
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `recovered or thrown local failure is reported separately from the server result`(): Unit = runBlocking {
        val followUps = followUps()

        val recovered = followUps.afterConfirmedMutation("account") { IllegalStateException("cache unavailable") }
        val thrown = followUps.afterConfirmedMutation("account") { throw IllegalStateException("index locked") }

        assertEquals(FileMutationLocalFollowUp.Failed, recovered)
        assertEquals(FileMutationLocalFollowUp.Failed, thrown)
        assertEquals(listOf("failed", "failed"), diagnostics.map { it.second.outcome })
        assertTrue(diagnostics.all { (account, event) -> account == "account" && event.code == "FILE_MUTATION_LOCAL_FOLLOW_UP_FAILED" })
    }

    @Test
    fun `stalled local bookkeeping cannot hold a server-confirmed result`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 50L)
        val release = latch()
        val finished = CountDownLatch(1)

        val followUp = withTimeout(5_000L) {
            followUps.afterConfirmedMutation("account") {
                // Stands in for a monitor wait that neither cancellation nor interruption can end.
                release.awaitUninterruptibly()
                finished.countDown()
                null
            }
        }

        assertEquals(FileMutationLocalFollowUp.StillRunning, followUp)
        assertEquals("still-running", diagnostics.single().second.outcome)
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS), "The bookkeeping still finishes in the background.")
    }

    @Test
    fun `work stays ordered behind a stalled update and unknown results never wait`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 50L)
        val release = latch()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val laterRan = CountDownLatch(1)

        followUps.afterConfirmedMutation("account") {
            release.awaitUninterruptibly()
            order += "first"
            null
        }
        withTimeout(1_000L) {
            followUps.afterUnknownResult("account") {
                order += "second"
                laterRan.countDown()
                null
            }
        }

        assertTrue(order.isEmpty(), "Later invalidations must not overtake an earlier one.")
        release.countDown()
        assertTrue(laterRan.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("first", "second"), order.toList())
    }

    @Test
    fun `caller cancellation propagates promptly while bookkeeping completes`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 60_000L)
        val release = latch()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)

        val caller = launch(Dispatchers.Default) {
            followUps.afterConfirmedMutation("account") {
                started.countDown()
                release.awaitUninterruptibly()
                finished.countDown()
                null
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        withTimeout(2_000L) { caller.cancelAndJoin() }

        assertTrue(caller.isCancelled)
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `closed service scope reports a local failure instead of cancelling an active caller`(): Unit =
        runBlocking {
            val followUps = followUps()
            scope.cancel()

            assertEquals(FileMutationLocalFollowUp.Failed, followUps.afterConfirmedMutation("account") { null })
        }

    private fun followUps(waitMillis: Long = 5_000L) = DesktopFileMutationFollowUps(
        scope = scope,
        completionWaitMillis = waitMillis,
        recordDiagnostic = { account, event -> diagnostics += account to event },
    )

    private fun latch() = CountDownLatch(1).also(releases::add)

    private fun CountDownLatch.awaitUninterruptibly() {
        var interrupted = false
        while (true) {
            try {
                await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
