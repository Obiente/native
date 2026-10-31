package dev.obiente.nextcloudnative.app

import java.net.ServerSocket
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class JvmAccountPasswordConfirmedTransportTest {
    private val transport = JvmAccountPasswordConfirmedTransport(OkHttpClient(), "confirmation-test")
    private val request = confirmedOcsFormRequest(
        NextcloudApiMethod.PUT,
        "/ocs/v2.php/cloud/users/alice",
        listOf("key" to "phone", "value" to "+1 555 0100"),
    )

    @Test
    fun `sends the typed password once as Basic credentials without cookies`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Set-Cookie", "oc_session=synthetic; Path=/")
                    .body("""{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[]}}""")
                    .build(),
            )
            server.start()
            val session = NextcloudSession(server.url("/cloud").toString(), "alice", "stored-app-token")
            val secret = AccountPasswordConfirmationSecret("typed password".toCharArray())

            val result = transport.execute(session, request, secret)

            val delivered = assertIs<PasswordConfirmedApiResult.Delivered>(result)
            assertEquals(200, delivered.response.status)
            val recorded = server.takeRequest()
            val expected = "Basic " + Base64.getEncoder().encodeToString("alice:typed password".toByteArray())
            assertEquals(expected, recorded.headers["Authorization"])
            assertEquals("true", recorded.headers["OCS-APIRequest"])
            assertNull(recorded.headers["Cookie"])
            assertEquals("PUT", recorded.method)
            assertEquals("key=phone&value=%2B1%20555%200100", recorded.body?.utf8())
            assertEquals("/cloud/ocs/v2.php/cloud/users/alice", recorded.url.encodedPath)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a rejected password is delivered for classification and not retried`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(401).body("").build())
            server.start()
            val session = NextcloudSession(server.url("/").toString(), "alice", "stored-app-token")
            val result = transport.execute(session, request, AccountPasswordConfirmationSecret("wrong".toCharArray()))
            assertEquals(401, assertIs<PasswordConfirmedApiResult.Delivered>(result).response.status)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `cross-origin redirects are not followed with the password`() = runBlocking {
        MockWebServer().use { other ->
            other.start()
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(307).addHeader("Location", other.url("/steal").toString()).build())
                server.start()
                val session = NextcloudSession(server.url("/").toString(), "alice", "stored-app-token")
                val result = transport.execute(session, request, AccountPasswordConfirmationSecret("pw".toCharArray()))
                // The redirect is refused, and the original write is treated as possibly applied.
                assertTrue(
                    result == PasswordConfirmedApiResult.Ambiguous ||
                        (result as? PasswordConfirmedApiResult.Delivered)?.response?.status == 307,
                )
                assertEquals(0, other.requestCount)
            }
        }
    }

    @Test
    fun `an unreachable server reports that nothing was sent`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val session = NextcloudSession("http://127.0.0.1:$port/", "alice", "stored-app-token")
        val result = transport.execute(session, request, AccountPasswordConfirmationSecret("pw".toCharArray()))
        assertEquals(PasswordConfirmedApiResult.NotSent, result)
    }
}
