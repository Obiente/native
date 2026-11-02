package dev.obiente.nextcloudnative.app

import okhttp3.OkHttpClient
import kotlin.test.Test
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
}
