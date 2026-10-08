package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Covers a poll that is still in flight when its sign-in attempt is cancelled or replaced.
 *
 * The server holds the first poll open, so the second poll starts while the first one's HTTP/2
 * connection is still active and therefore not subject to idle-connection eviction.
 */
class JvmLoginPollIsolationTest {
    @Test
    fun `a shared poll client multiplexes a replacement poll onto the active connection`() = runBlocking {
        withHeldFirstPoll { server, gate, base ->
            val shared = base.newLoginPollHttpClient()
            val first = async(Dispatchers.IO) { poll(shared, server.challenge()) }
            assertTrue(gate.firstArrived.await(10, TimeUnit.SECONDS))

            val second = withContext(Dispatchers.IO) { poll(shared, server.challenge()) }
            gate.release.countDown()

            assertEquals(LoginPollResult.Pending, second)
            assertEquals(LoginPollResult.Pending, first.await())
            assertEquals(1, gate.connectionIndexes.distinct().size)
        }
    }

    @Test
    fun `a replacement poll never shares the connection of a poll still in flight`() = runBlocking {
        withHeldFirstPoll { server, gate, base ->
            val first = async(Dispatchers.IO) {
                withLoginPollHttpClient(base) { client -> poll(client, server.challenge()) }
            }
            assertTrue(gate.firstArrived.await(10, TimeUnit.SECONDS))

            val second = withContext(Dispatchers.IO) {
                withLoginPollHttpClient(base) { client -> poll(client, server.challenge()) }
            }
            gate.release.countDown()

            assertEquals(LoginPollResult.Pending, second)
            assertEquals(LoginPollResult.Pending, first.await())
            assertEquals(2, gate.connectionIndexes.size)
            assertNotEquals(gate.connectionIndexes[0], gate.connectionIndexes[1])
        }
    }

    @Test
    fun `cancelling the poll coroutine cancels its blocked call`() = runBlocking {
        withHeldFirstPoll { server, gate, base ->
            val attempt = AtomicReference<JvmNetworkRequestAttempt?>(null)
            var completed = false
            val job = launch(Dispatchers.IO) {
                withLoginPollHttpClient(base) { client ->
                    poll(client, server.challenge(), onAttempt = attempt::set)
                }
                completed = true
            }
            assertTrue(gate.firstArrived.await(10, TimeUnit.SECONDS))

            job.cancel()
            // The cancelling thread cancels the call itself, without waiting for a dispatcher thread.
            assertTrue(requireNotNull(attempt.get()).cancelled)
            // The server holds the response far longer than this, so only a cancelled call ends early.
            withTimeout(5_000) { job.join() }

            assertTrue(job.isCancelled)
            assertFalse(completed)
            assertTrue(requireNotNull(attempt.get()).cancelled)
        }
    }

    @Test
    fun `a call that starts after cancellation is never sent`() = runBlocking {
        withHeldFirstPoll { server, gate, base ->
            val calls = LoginPollCallCancellation()
            val client = base.newBuilder().addInterceptor(calls).build()
            calls.cancel()

            val attempt = JvmNetworkRequestAttempt()
            assertFailsWith<IOException> {
                client.newCall(pollRequest(server.challenge().pollEndpoint, attempt)).execute()
            }

            assertTrue(attempt.cancelled)
            assertFalse(attempt.exchangeStarted)
            assertTrue(gate.connectionIndexes.isEmpty())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `a call registered before cancellation is cancelled while blocked`() = runBlocking {
        withHeldFirstPoll { server, gate, base ->
            val calls = LoginPollCallCancellation()
            val client = base.newBuilder().addInterceptor(calls).build()
            val attempt = JvmNetworkRequestAttempt()
            val blocked = async(Dispatchers.IO) {
                runCatching {
                    client.newCall(pollRequest(server.challenge().pollEndpoint, attempt)).execute().close()
                }
            }
            assertTrue(gate.firstArrived.await(10, TimeUnit.SECONDS))

            calls.cancel()
            val outcome = withTimeout(5_000) { blocked.await() }

            assertIs<IOException>(outcome.exceptionOrNull())
            assertTrue(attempt.cancelled)
        }
    }

    @Test
    fun `cancellation just before a poll is sent stops it in either order`() = runBlocking {
        withHeldFirstPoll { server, gate, base ->
            val attempt = AtomicReference<JvmNetworkRequestAttempt?>(null)
            var completed = false
            val job = launch(Dispatchers.IO) {
                val attemptJob = coroutineContext.job
                withLoginPollHttpClient(base) { client ->
                    // The cancellation sweep races the call's registration from here on.
                    attemptJob.cancel()
                    poll(client, server.challenge(), onAttempt = attempt::set)
                }
                completed = true
            }

            // The server would hold a sent poll far longer than this.
            withTimeout(5_000) { job.join() }

            assertTrue(job.isCancelled)
            assertFalse(completed)
            val sent = gate.connectionIndexes.isNotEmpty()
            assertTrue(!sent || requireNotNull(attempt.get()).cancelled)
        }
    }

    private fun pollRequest(endpoint: String, attempt: JvmNetworkRequestAttempt): Request = Request.Builder()
        .url(endpoint)
        .post("token=synthetic-one-time-token".toRequestBody(FORM))
        .tag(JvmNetworkRequestAttempt::class.java, attempt)
        .build()

    private suspend fun withHeldFirstPoll(
        test: suspend (MockWebServer, HeldFirstPoll, OkHttpClient) -> Unit,
    ) {
        val gate = HeldFirstPoll()
        MockWebServer().use { server ->
            server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
            server.dispatcher = gate
            server.start()
            val base = OkHttpClient.Builder()
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                .trackJvmNetworkFailures()
                .build()
            try {
                test(server, gate, base)
            } finally {
                gate.release.countDown()
            }
        }
    }

    private suspend fun poll(
        client: OkHttpClient,
        challenge: LoginChallenge,
        onAttempt: (JvmNetworkRequestAttempt) -> Unit = {},
    ): LoginPollResult = executeLoginPollHttp(
        challenge = challenge,
        fallbackAlreadySelected = false,
        poll = { endpoint ->
            val attempt = JvmNetworkRequestAttempt().also(onAttempt)
            client.newCall(pollRequest(endpoint, attempt)).execute().use { response ->
                LoginPollHttpResponse(response.code, response.body.string())
            }
        },
        networkFailure = { null },
    ).interpretation.result

    private fun MockWebServer.challenge(): LoginChallenge {
        val pollEndpoint = url("/login/v2/poll").toString()
        return LoginChallenge(
            enteredServerUrl = pollEndpoint.substringBefore("/login/v2/poll"),
            pollEndpoint = pollEndpoint,
            pollFallbackEndpoint = null,
            token = "synthetic-one-time-token",
            loginUrl = pollEndpoint.replace("/poll", "/flow/synthetic"),
            transportSecurity = LoginTransportSecurity.PlainHttp,
        )
    }

    private companion object {
        val FORM = "application/x-www-form-urlencoded".toMediaType()
    }
}

/** Answers every poll as pending, but holds the first one until the test releases it. */
private class HeldFirstPoll : mockwebserver3.Dispatcher() {
    val firstArrived = CountDownLatch(1)
    val release = CountDownLatch(1)
    val connectionIndexes = CopyOnWriteArrayList<Int>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val isFirst = synchronized(connectionIndexes) {
            connectionIndexes += request.connectionIndex
            connectionIndexes.size == 1
        }
        if (isFirst) {
            firstArrived.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        return MockResponse.Builder().code(404).body("[]").build()
    }
}
