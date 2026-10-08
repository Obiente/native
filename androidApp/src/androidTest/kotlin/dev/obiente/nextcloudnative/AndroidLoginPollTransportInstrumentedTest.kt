package dev.obiente.nextcloudnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.obiente.nextcloudnative.app.JvmNetworkFailureDiagnostic
import dev.obiente.nextcloudnative.app.JvmNetworkRequestAttempt
import dev.obiente.nextcloudnative.app.LoginChallenge
import dev.obiente.nextcloudnative.app.LoginPollHttpResponse
import dev.obiente.nextcloudnative.app.LoginPollResult
import dev.obiente.nextcloudnative.app.executeLoginPollHttp
import dev.obiente.nextcloudnative.app.toJvmNetworkFailureDiagnostic
import dev.obiente.nextcloudnative.app.trackJvmNetworkFailures
import dev.obiente.nextcloudnative.app.withLoginPollHttpClient
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs Login Flow v2 poll isolation and cancellation on the Android runtime and its TLS stack.
 *
 * All servers are local synthetic fixtures; no request leaves the device.
 */
@RunWith(AndroidJUnit4::class)
class AndroidLoginPollTransportInstrumentedTest {
    private val certificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .build()

    @Test
    fun everyPollUsesANewTlsConnectionUntilApproval() = runBlocking {
        val connections = CopyOnWriteArrayList<Int>()
        withServer(
            dispatcher = { request, server ->
                connections += request.connectionIndex
                if (connections.size < 3) pending() else server.approved()
            },
        ) { server, base ->
            val challenge = server.challenge()
            val results = List(3) {
                withLoginPollHttpClient(base) { client -> poll(client, challenge) }
            }

            assertEquals(LoginPollResult.Pending, results[0])
            assertEquals(LoginPollResult.Pending, results[1])
            assertTrue(results[2] is LoginPollResult.Approved)
            assertEquals(3, connections.distinct().size)
        }
    }

    @Test
    fun cancellingTheAttemptCancelsAPollHeldByTheServer() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        withServer(
            dispatcher = { _, _ ->
                arrived.countDown()
                release.await(30, TimeUnit.SECONDS)
                pending()
            },
            onClose = release::countDown,
        ) { server, base ->
            val attempt = AtomicReference<JvmNetworkRequestAttempt?>(null)
            var completed = false
            val job = launch(Dispatchers.IO) {
                withLoginPollHttpClient(base) { client ->
                    poll(client, server.challenge(), onAttempt = attempt::set)
                }
                completed = true
            }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))

            job.cancel()
            assertTrue(requireNotNull(attempt.get()).cancelled)
            withTimeout(5_000) { job.join() }

            assertTrue(job.isCancelled)
            assertFalse(completed)
        }
    }

    @Test
    fun refusedConnectionBeforeTheExchangeIsRetryable() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val pollEndpoint = "https://localhost:$closedPort/login/v2/poll"
        val challenge = LoginChallenge(
            enteredServerUrl = "https://localhost:$closedPort",
            pollEndpoint = pollEndpoint,
            pollFallbackEndpoint = null,
            token = "synthetic-one-time-token",
            loginUrl = "https://localhost:$closedPort/login/v2/flow/synthetic",
        )

        val result = withLoginPollHttpClient(trustingClient()) { client -> poll(client, challenge) }

        assertTrue(result is LoginPollResult.RetryablePreExchangeFailure)
        assertEquals("NETWORK_CONNECT_FAILED", (result as LoginPollResult.RetryablePreExchangeFailure).code)
    }

    private suspend fun withServer(
        dispatcher: (RecordedRequest, MockWebServer) -> MockResponse,
        onClose: () -> Unit = {},
        test: suspend (MockWebServer, OkHttpClient) -> Unit,
    ) {
        MockWebServer().use { server ->
            server.useHttps(
                HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = dispatcher(request, server)
            }
            server.start()
            try {
                test(server, trustingClient())
            } finally {
                onClose()
            }
        }
    }

    private fun trustingClient(): OkHttpClient {
        val trusted = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        return OkHttpClient.Builder()
            .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .trackJvmNetworkFailures()
            .build()
    }

    private suspend fun poll(
        client: OkHttpClient,
        challenge: LoginChallenge,
        onAttempt: (JvmNetworkRequestAttempt) -> Unit = {},
    ): LoginPollResult {
        var networkFailure: JvmNetworkFailureDiagnostic? = null
        return executeLoginPollHttp(
            challenge = challenge,
            fallbackAlreadySelected = false,
            poll = { endpoint ->
                networkFailure = null
                val attempt = JvmNetworkRequestAttempt().also(onAttempt)
                val request = Request.Builder()
                    .url(endpoint)
                    .post("token=synthetic-one-time-token".toRequestBody(FORM))
                    .tag(JvmNetworkRequestAttempt::class.java, attempt)
                    .build()
                try {
                    client.newCall(request).execute().use { response ->
                        LoginPollHttpResponse(response.code, response.body.string())
                    }
                } catch (failure: IOException) {
                    networkFailure = failure.toJvmNetworkFailureDiagnostic(
                        attempt = attempt,
                        readOnlyRequest = false,
                        replayableRequest = false,
                    )
                    throw failure
                }
            },
            networkFailure = { networkFailure },
        ).interpretation.result
    }

    private fun MockWebServer.challenge(): LoginChallenge {
        val origin = url("/").toString().trimEnd('/')
        return LoginChallenge(
            enteredServerUrl = origin,
            pollEndpoint = "$origin/login/v2/poll",
            pollFallbackEndpoint = null,
            token = "synthetic-one-time-token",
            loginUrl = "$origin/login/v2/flow/synthetic",
        )
    }

    private fun pending() = MockResponse.Builder().code(404).body("[]").build()

    private fun MockWebServer.approved() = MockResponse.Builder()
        .code(200)
        .body(
            """{"server":"${url("/").toString().trimEnd('/')}","loginName":"person",""" +
                """"appPassword":"synthetic-app-password"}""",
        )
        .build()

    private companion object {
        val FORM = "application/x-www-form-urlencoded".toMediaType()
    }
}
