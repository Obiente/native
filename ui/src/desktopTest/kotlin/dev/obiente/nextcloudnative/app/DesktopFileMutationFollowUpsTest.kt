package dev.obiente.nextcloudnative.app

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
    fun `completed local bookkeeping refreshes every affected path`(): Unit = runBlocking {
        val followUps = followUps()
        val refreshed = Collections.synchronizedList(mutableListOf<String>())

        val followUp = followUps.afterConfirmedMutation("account", listOf("a.txt", "b/a.txt")) { path ->
            refreshed += path
            null
        }

        assertEquals(FileMutationLocalFollowUp.Completed, followUp)
        assertEquals(listOf("a.txt", "b/a.txt"), refreshed.toList())
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `recovered or thrown local failure is reported separately from the server result`(): Unit = runBlocking {
        val followUps = followUps()

        val recovered = followUps.afterConfirmedMutation("account", listOf("a")) { IllegalStateException("cache") }
        val thrown = followUps.afterConfirmedMutation("account", listOf("a")) { throw IllegalStateException("index") }

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
            followUps.afterConfirmedMutation("account", listOf("a")) {
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
    fun `many mutations behind a stall stay coalesced and run once the stall clears`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 20L)
        val release = latch()
        val started = CountDownLatch(1)
        followUps.afterUnknownResult("account", listOf("stalled")) {
            started.countDown()
            release.awaitUninterruptibly()
            null
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val runs = Collections.synchronizedList(mutableListOf<String>())

        repeat(1_000) { mutation ->
            val path = "folder/${mutation % 10}"
            followUps.afterUnknownResult("account", listOf(path)) {
                runs += "$path#$mutation"
                null
            }
        }
        repeat(20) { mutation ->
            val followUp = followUps.afterConfirmedMutation("account", listOf("folder/${mutation % 10}")) { path ->
                runs += "$path#confirmed$mutation"
                null
            }
            assertEquals(FileMutationLocalFollowUp.StillRunning, followUp)
        }

        assertEquals(10, followUps.pendingCount, "Repeated paths must not grow the queue.")
        assertTrue(runs.isEmpty(), "Nothing may overtake the stalled refresh.")
        release.countDown()
        withTimeout(5_000L) { while (followUps.pendingCount > 0 || runs.size < 10) delay(10L) }
        assertEquals(
            (0 until 10).map { index -> "folder/$index#confirmed${10 + index}" },
            runs.toList(),
            "Each path refreshes once, with its newest refresh.",
        )
    }

    @Test
    fun `pending paths behind a stall are bounded and overflow is reported as a local failure`(): Unit =
        runBlocking {
            val followUps = followUps(waitMillis = 20L, maximumPending = 4)
            val release = latch()
            val started = CountDownLatch(1)
            followUps.afterUnknownResult("account", listOf("stalled")) {
                started.countDown()
                release.awaitUninterruptibly()
                null
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            val results = (0 until 10).map { index ->
                followUps.afterConfirmedMutation("account", listOf("path/$index")) { null }
            }

            assertEquals(4, followUps.pendingCount)
            assertEquals(List(4) { FileMutationLocalFollowUp.StillRunning }, results.take(4))
            assertEquals(List(6) { FileMutationLocalFollowUp.Failed }, results.drop(4))
            assertEquals(6, diagnostics.count { it.second.outcome == "dropped" })
        }

    @Test
    fun `caller cancellation propagates promptly while bookkeeping completes`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 60_000L)
        val release = latch()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)

        val caller = launch(Dispatchers.Default) {
            followUps.afterConfirmedMutation("account", listOf("a")) {
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

            assertEquals(
                FileMutationLocalFollowUp.Failed,
                followUps.afterConfirmedMutation("account", listOf("a")) { null },
            )
        }

    private fun followUps(waitMillis: Long = 5_000L, maximumPending: Int = MAX_PENDING_FILE_MUTATION_FOLLOW_UPS) =
        DesktopFileMutationFollowUps(
            scope = scope,
            completionWaitMillis = waitMillis,
            maximumPending = maximumPending,
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
