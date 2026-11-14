package dev.obiente.nextcloudnative.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Headers.Companion.headersOf
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JvmDynamicApiClientsTest {
    private val base = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    @Test
    fun `every non-GET dynamic request uses a client that never retries a written body`() {
        val clients = JvmDynamicApiClients(base)
        NextcloudApiMethod.entries.filter { it != NextcloudApiMethod.GET }.forEach { method ->
            val client = clients.forMethod(method)
            assertFalse(client.retryOnConnectionFailure, "$method must not be resent on a stale connection.")
            assertFalse(client.followRedirects || client.followSslRedirects, "$method never follows redirects.")
        }
        assertFalse(clients.write.retryOnConnectionFailure)
        assertSame(clients.write.connectionPool, base.connectionPool, "Writes share the account's connection pool.")
    }

    @Test
    fun `reads keep the base client`() {
        val clients = JvmDynamicApiClients(base)
        assertSame(base, clients.forMethod(NextcloudApiMethod.GET))
        assertTrue(clients.read.retryOnConnectionFailure)
    }

    @Test
    fun `a redirect-following client is refused`() {
        assertFailsWith<IllegalArgumentException> { JvmDynamicApiClients(OkHttpClient()) }
    }

    @Test
    fun `503 retry-after zero never repeats a write including a body-free delete`() {
        for (method in listOf("POST", "PUT", "DELETE")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(code = 503, headers = headersOf("Retry-After", "0")))
                server.enqueue(MockResponse(code = 200))
                val body = if (method == "DELETE") null else "synthetic".toRequestBody()
                val request = Request.Builder().url(server.url("/items/7")).method(method, body).build()
                val client = JvmDynamicApiClients(base).write
                assertFailsWith<IOException> { client.newCall(request).execute().use { } }
                assertEquals(1, server.requestCount, "$method must reach the server only once.")
                // A new explicit call has its own attempt gate.
                client.newCall(request).execute().use { assertEquals(200, it.code) }
                assertEquals(2, server.requestCount)
            }
        }
    }
}
