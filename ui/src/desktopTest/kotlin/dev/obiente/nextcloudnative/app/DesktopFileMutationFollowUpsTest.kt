package dev.obiente.nextcloudnative.app

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
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
    fun `a dropped path is reported as failed even while another path is still running`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 20L, maximumPending = 1)
        val release = latch()
        val started = CountDownLatch(1)
        followUps.afterUnknownResult("account", listOf("stalled")) {
            started.countDown()
            release.awaitUninterruptibly()
            null
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val followUp = followUps.afterConfirmedMutation("account", listOf("source", "destination")) { null }

        assertEquals(FileMutationLocalFollowUp.Failed, followUp, "The dropped destination will never refresh.")
        assertEquals(1, followUps.pendingCount)
    }

    @Test
    fun `service teardown abandons queued refreshes once the blocked one returns`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 60_000L)
        val release = latch()
        val started = CountDownLatch(1)
        val stalledReturned = CountDownLatch(1)
        followUps.afterUnknownResult("account", listOf("stalled")) {
            started.countDown()
            release.awaitUninterruptibly()
            stalledReturned.countDown()
            null
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val runs = Collections.synchronizedList(mutableListOf<String>())
        (0 until 3).forEach { index ->
            followUps.afterUnknownResult("account", listOf("queued/$index")) {
                runs += "queued/$index"
                null
            }
        }
        val caller = async(Dispatchers.Default) {
            followUps.afterConfirmedMutation("account", listOf("confirmed")) {
                runs += "confirmed"
                null
            }
        }
        withTimeout(5_000L) { while (followUps.pendingCount < 4) delay(10L) }

        scope.cancel()
        release.countDown()

        assertTrue(stalledReturned.await(5, TimeUnit.SECONDS))
        assertEquals(FileMutationLocalFollowUp.Failed, withTimeout(5_000L) { caller.await() })
        withTimeout(5_000L) { while (followUps.pendingCount > 0) delay(10L) }
        delay(100L)
        assertTrue(runs.isEmpty(), "No queued refresh may run after teardown: $runs")
    }

    @Test
    fun `account teardown drops only that account's queued refreshes`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 60_000L)
        val release = latch()
        val started = CountDownLatch(1)
        val stalledReturned = CountDownLatch(1)
        followUps.afterUnknownResult("retired", listOf("stalled")) {
            started.countDown()
            release.awaitUninterruptibly()
            stalledReturned.countDown()
            null
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val runs = Collections.synchronizedList(mutableListOf<String>())
        followUps.afterUnknownResult("retired", listOf("queued")) { runs += "retired/$it"; null }
        followUps.afterUnknownResult("active", listOf("queued")) { runs += "active/$it"; null }
        val retiredCaller = async(Dispatchers.Default) {
            followUps.afterConfirmedMutation("retired", listOf("confirmed")) { runs += "retired/$it"; null }
        }
        withTimeout(5_000L) { while (followUps.pendingCount < 3) delay(10L) }

        followUps.abandonAccount("retired")

        assertEquals(FileMutationLocalFollowUp.Failed, withTimeout(5_000L) { retiredCaller.await() })
        assertEquals(1, followUps.pendingCount, "The other account's refresh stays queued.")
        release.countDown()
        assertTrue(stalledReturned.await(5, TimeUnit.SECONDS), "A running refresh cannot be interrupted.")
        withTimeout(5_000L) { while (runs.isEmpty()) delay(10L) }
        delay(100L)
        assertEquals(listOf("active/queued"), runs.toList(), "No retired refresh may run after teardown.")
    }

    @Test
    fun `a running refresh completing after account teardown cannot change the reported failure`(): Unit =
        runBlocking {
            val followUps = followUps(waitMillis = 60_000L)
            val release = latch()
            val started = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val caller = async(Dispatchers.Default) {
                followUps.afterConfirmedMutation("retired", listOf("stalled")) {
                    started.countDown()
                    release.awaitUninterruptibly()
                    finished.countDown()
                    null
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            followUps.abandonAccount("retired")

            assertEquals(
                FileMutationLocalFollowUp.Failed,
                withTimeout(5_000L) { caller.await() },
                "The caller learns of the teardown without waiting for the blocked refresh.",
            )
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(FileMutationLocalFollowUp.Failed, caller.await())
        }

    @Test
    fun `an unknown result whose reconciliation cannot be queued says so`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 20L, maximumPending = 1)
        val release = latch()
        val started = CountDownLatch(1)
        followUps.afterUnknownResult("account", listOf("stalled")) {
            started.countDown()
            release.awaitUninterruptibly()
            null
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val interrupted = DesktopFileSyncAmbiguousMutationException(java.io.IOException("reset"))

        val queued = assertFailsWith<DesktopFileSyncAmbiguousMutationException> {
            followUps.mutation("account", listOf("source")) { null }.reconcilingUnknownResult { onUnknown ->
                onUnknown()
                throw interrupted
            }
        }
        val dropped = assertFailsWith<DesktopFileMutationReconciliationDroppedException> {
            followUps.mutation("account", listOf("source", "destination")) { null }.reconcilingUnknownResult { onUnknown ->
                onUnknown()
                throw interrupted
            }
        }

        assertSame(interrupted, queued, "A queued reconciliation keeps the original unknown result.")
        assertSame(interrupted, dropped.cause)
        assertFalse(followUps.afterUnknownResult("account", listOf("other")) { null })
        assertEquals("ok", followUps.mutation("account", listOf("other")) { null }.reconcilingUnknownResult { "ok" })
    }

    @Test
    fun `confirmation attaches to the accepted-response refresh while it runs`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 60_000L)
        val release = latch()
        val started = CountDownLatch(1)
        val runs = Collections.synchronizedList(mutableListOf<String>())
        val mutation = followUps.mutation("account", listOf("a")) { path ->
            runs += path
            started.countDown()
            release.awaitUninterruptibly()
            null
        }

        assertTrue(mutation.onAcceptedOrUnknownResult())
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val confirmed = async(Dispatchers.Default) { mutation.afterConfirmedMutation() }
        delay(100L)
        assertEquals(0, followUps.pendingCount, "The running refresh is not queued again.")
        release.countDown()

        assertEquals(FileMutationLocalFollowUp.Completed, withTimeout(5_000L) { confirmed.await() })
        assertEquals(listOf("a"), runs.toList())
    }

    @Test
    fun `confirmation attaches to the accepted-response refresh while it is queued or after it finished`(): Unit =
        runBlocking {
            val followUps = followUps(waitMillis = 60_000L)
            val release = latch()
            val started = CountDownLatch(1)
            followUps.afterUnknownResult("account", listOf("stalled")) {
                started.countDown()
                release.awaitUninterruptibly()
                null
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val runs = Collections.synchronizedList(mutableListOf<String>())
            val queued = followUps.mutation("account", listOf("queued")) { path -> runs += path; null }

            assertTrue(queued.onAcceptedOrUnknownResult())
            val confirmed = async(Dispatchers.Default) { queued.afterConfirmedMutation() }
            delay(100L)
            assertEquals(1, followUps.pendingCount)
            release.countDown()
            assertEquals(FileMutationLocalFollowUp.Completed, withTimeout(5_000L) { confirmed.await() })

            val finished = followUps.mutation("account", listOf("finished")) { path -> runs += path; null }
            assertTrue(finished.onAcceptedOrUnknownResult())
            withTimeout(5_000L) { while ("finished" !in runs) delay(10L) }
            delay(50L)
            assertEquals(FileMutationLocalFollowUp.Completed, finished.afterConfirmedMutation())
            delay(100L)
            assertEquals(listOf("queued", "finished"), runs.toList(), "Each path refreshes once.")
        }

    @Test
    fun `a full queue does not fail a path whose refresh for this mutation is already running`(): Unit =
        runBlocking {
            val followUps = followUps(waitMillis = 60_000L, maximumPending = 1)
            val release = latch()
            val started = CountDownLatch(1)
            val mutation = followUps.mutation("account", listOf("a")) {
                started.countDown()
                release.awaitUninterruptibly()
                null
            }
            assertTrue(mutation.onAcceptedOrUnknownResult())
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(followUps.afterUnknownResult("account", listOf("b")) { null })
            assertEquals(1, followUps.pendingCount, "The queue is full.")

            val confirmed = async(Dispatchers.Default) { mutation.afterConfirmedMutation() }
            delay(100L)
            release.countDown()

            assertEquals(FileMutationLocalFollowUp.Completed, withTimeout(5_000L) { confirmed.await() })
            assertTrue(diagnostics.none { it.second.outcome == "dropped" })
        }

    @Test
    fun `another mutation's running refresh is never reused for a dropped path`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 20L, maximumPending = 1)
        val release = latch()
        val started = CountDownLatch(1)
        followUps.afterUnknownResult("account", listOf("a")) {
            started.countDown()
            release.awaitUninterruptibly()
            null
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(followUps.afterUnknownResult("account", listOf("b")) { null })
        val later = followUps.mutation("account", listOf("a")) { null }

        assertFalse(later.onAcceptedOrUnknownResult(), "The queue is full, so its own refresh was dropped.")
        assertEquals(
            FileMutationLocalFollowUp.Failed,
            later.afterConfirmedMutation(),
            "The running refresh started before this mutation was accepted.",
        )
    }

    @Test
    fun `confirmation after account teardown reports the abandoned refresh as failed`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 60_000L)
        val release = latch()
        val started = CountDownLatch(1)
        val mutation = followUps.mutation("retired", listOf("a")) {
            started.countDown()
            release.awaitUninterruptibly()
            null
        }
        assertTrue(mutation.onAcceptedOrUnknownResult())
        assertTrue(started.await(5, TimeUnit.SECONDS))

        followUps.abandonAccount("retired")

        assertEquals(FileMutationLocalFollowUp.Failed, withTimeout(5_000L) { mutation.afterConfirmedMutation() })
        release.countDown()
    }

    @Test
    fun `a refused connection is reported as not sent and queues no reconciliation`(): Unit = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val executor = DesktopHttpMutationExecutor(okhttp3.OkHttpClient())
        val request = okhttp3.Request.Builder().url("http://127.0.0.1:$port/remote.php/dav/files/alice/a.txt")
            .method("DELETE", null).build()
        val runs = Collections.synchronizedList(mutableListOf<String>())
        val mutation = followUps().mutation("account", listOf("a.txt")) { path -> runs += path; null }

        val unsent = assertFailsWith<FileMutationNotSentException> {
            mutation.reconcilingUnknownResult { onUnknown -> executor.execute(request, onUnknown) { it.code } }
        }

        assertIs<java.io.IOException>(unsent.cause)
        delay(100L)
        assertTrue(runs.isEmpty(), "Nothing was sent, so there is nothing to reconcile.")
    }

    @Test
    fun `a failure after the request left the device stays ambiguous`(): Unit = runBlocking {
        mockwebserver3.MockWebServer().use { server ->
            server.enqueue(mockwebserver3.MockResponse.Builder().code(204).headersDelay(5, TimeUnit.SECONDS).build())
            server.start()
            val executor = DesktopHttpMutationExecutor(
                okhttp3.OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build(),
            )
            val request = okhttp3.Request.Builder().url(server.url("/remote.php/dav/files/alice/a.txt"))
                .method("DELETE", null).build()
            val mutation = followUps().mutation("account", listOf("a.txt")) { null }

            assertFailsWith<DesktopFileSyncAmbiguousMutationException> {
                mutation.reconcilingUnknownResult { onUnknown -> executor.execute(request, onUnknown) { it.code } }
            }
        }
    }

    @Test
    fun `an account change before sending is reported as not sent`(): Unit = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val expected = NextcloudSession("https://guard.invalid", "alice", "synthetic")
        val other = NextcloudSession("https://guard.invalid", "bob", "synthetic")
        var sent = false

        assertFailsWith<FileMutationNotSentException> {
            guard.withAuthenticatedMutationSession(expected, { other }) { sent = true }
        }
        assertFalse(sent)
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
