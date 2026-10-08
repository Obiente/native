package dev.obiente.nextcloudnative.app

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Reproduces a connection that dies while the app is suspended between Login Flow v2 polls.
 *
 * A destroyed or silently dropped socket still passes OkHttp's pooled-connection health check,
 * so the next request is written before the failure surfaces. That failure is correctly
 * ambiguous, so the poll client must never reuse a connection across the wait between polls.
 */
class JvmLoginPollConnectionTest {
    @Test
    fun `a poll written to a reused dead connection stays ambiguous`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(challengeResponse())
            server.enqueue(pendingResponse())
            server.enqueue(server.approvedResponse())
            val sockets = SuspendableSocketFactory()
            val base = baseClient(sockets)
            val sharedPoolClient = base.newBuilder().retryOnConnectionFailure(false).build()
            val challenge = challenge(server.url("/login/v2/poll").toString())

            beginLogin(base, server.url("/index.php/login/v2").toString())
            assertEquals(LoginPollResult.Pending, poll(sharedPoolClient, challenge))
            assertEquals(1, sockets.created.size)
            sockets.suspendAll()

            val failure = assertIs<LoginPollResult.AmbiguousAfterExchangeFailure>(
                poll(sharedPoolClient, challenge),
            )
            assertEquals("NETWORK_SOCKET_FAILED", failure.code)
            assertTrue(failure.message.contains("could not be confirmed safely"))
        }
    }

    @Test
    fun `login polls survive connections that died while the app was suspended`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(challengeResponse())
            server.enqueue(pendingResponse())
            server.enqueue(pendingResponse())
            server.enqueue(server.approvedResponse())
            val sockets = SuspendableSocketFactory()
            val base = baseClient(sockets)
            val challenge = challenge(server.url("/login/v2/poll").toString())
            suspend fun pollOnce() = withLoginPollHttpClient(base) { client -> poll(client, challenge) }

            beginLogin(base, server.url("/index.php/login/v2").toString())
            sockets.suspendAll()
            assertEquals(LoginPollResult.Pending, pollOnce())
            sockets.suspendAll()
            assertEquals(LoginPollResult.Pending, pollOnce())
            sockets.suspendAll()
            val approved = assertIs<LoginPollResult.Approved>(pollOnce())

            assertEquals("person", approved.session.loginName)
            assertEquals(4, sockets.created.size)
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `login poll client never replays a request and keeps no idle connection`() {
        val base = OkHttpClient.Builder().trackJvmNetworkFailures().build()
        val pollClient = base.newLoginPollHttpClient()

        assertFalse(pollClient.retryOnConnectionFailure)
        assertNotSame(base.connectionPool, pollClient.connectionPool)
        assertNotSame(pollClient.connectionPool, base.newLoginPollHttpClient().connectionPool)
        assertEquals(0, pollClient.connectionPool.idleConnectionCount())
        assertEquals(base.eventListenerFactory, pollClient.eventListenerFactory)
        assertEquals(base.socketFactory, pollClient.socketFactory)
    }

    private fun baseClient(sockets: SuspendableSocketFactory): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(sockets)
        // Racing IPv4 and IPv6 loopback addresses would create a losing socket per connection.
        .fastFallback(false)
        .trackJvmNetworkFailures()
        .build()

    private fun beginLogin(client: OkHttpClient, url: String) {
        val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()
        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            response.body.string()
        }
    }

    private suspend fun poll(client: OkHttpClient, challenge: LoginChallenge): LoginPollResult {
        var networkFailure: JvmNetworkFailureDiagnostic? = null
        return executeLoginPollHttp(
            challenge = challenge,
            fallbackAlreadySelected = false,
            poll = { endpoint ->
                networkFailure = null
                val attempt = JvmNetworkRequestAttempt()
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

    private fun challenge(pollEndpoint: String) = LoginChallenge(
        enteredServerUrl = pollEndpoint.substringBefore("/login/v2/poll"),
        pollEndpoint = pollEndpoint,
        pollFallbackEndpoint = null,
        token = "synthetic-one-time-token",
        loginUrl = pollEndpoint.replace("/poll", "/flow/synthetic"),
        transportSecurity = LoginTransportSecurity.PlainHttp,
    )

    private fun challengeResponse() = MockResponse.Builder().code(200).body("{}").build()

    private fun pendingResponse() = MockResponse.Builder().code(404).body("[]").build()

    private fun MockWebServer.approvedResponse() = MockResponse.Builder()
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

/**
 * Creates sockets that can be marked dead without the client observing a close, like a socket
 * destroyed while its process was frozen or a connection whose network path was dropped.
 */
private class SuspendableSocketFactory : SocketFactory() {
    val created = CopyOnWriteArrayList<SuspendableSocket>()

    fun suspendAll() = created.forEach(SuspendableSocket::markDead)

    override fun createSocket(): Socket = SuspendableSocket().also(created::add)

    override fun createSocket(host: String, port: Int): Socket = unsupported()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        unsupported()

    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("OkHttp connects unconnected sockets")
}

private class SuspendableSocket : Socket() {
    @Volatile
    private var dead = false

    fun markDead() {
        dead = true
    }

    override fun getInputStream(): InputStream = object : FilterInputStream(super.getInputStream()) {
        override fun read(): Int {
            if (dead) throw SocketTimeoutException("no data arrived")
            return super.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (dead) throw SocketTimeoutException("no data arrived")
            return super.read(buffer, offset, length)
        }
    }

    override fun getOutputStream(): OutputStream = object : FilterOutputStream(super.getOutputStream()) {
        override fun write(value: Int) {
            if (dead) throw SocketException("Software caused connection abort")
            out.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (dead) throw SocketException("Software caused connection abort")
            out.write(buffer, offset, length)
        }

        override fun flush() {
            if (dead) throw SocketException("Software caused connection abort")
            out.flush()
        }
    }
}
