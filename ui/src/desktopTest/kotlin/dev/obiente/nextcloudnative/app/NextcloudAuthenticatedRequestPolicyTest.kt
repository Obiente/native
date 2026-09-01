package dev.obiente.nextcloudnative.app

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

class NextcloudAuthenticatedRequestPolicyTest {
    private val session = NextcloudSession("https://cloud.example.test/cloud", "alice", "secret")
    private val policy = NextcloudAuthenticatedRequestPolicy(session, "policy-test")

    @Test
    fun `targets use the exact account origin including effective default ports`() {
        val implicitPort = policy.requestBuilder("https://cloud.example.test/cloud/dav").build()
        val explicitPort = policy.requestBuilder("https://cloud.example.test:443/cloud/dav").build()

        assertEquals(443, implicitPort.url.port)
        assertEquals(443, explicitPort.url.port)
        assertFailsWith<IllegalArgumentException> {
            policy.requestBuilder("https://cloud.example.test:444/cloud/dav")
        }
        assertFailsWith<IllegalArgumentException> {
            policy.requestBuilder("http://cloud.example.test/cloud/dav")
        }
    }

    @Test
    fun `target must stay inside the configured account path segment boundary`() {
        policy.requestBuilder("https://cloud.example.test/cloud/remote.php/dav")
        policy.requestBuilder("https://cloud.example.test/cloud")

        assertFailsWith<IllegalArgumentException> {
            policy.requestBuilder("https://cloud.example.test/cloud2/remote.php/dav")
        }
        assertFailsWith<IllegalArgumentException> {
            policy.requestBuilder("https://cloud.example.test/remote.php/dav")
        }
    }

    @Test
    fun `userinfo fragments and encoded traversal are rejected before credentials are attached`() {
        listOf(
            "https://mallory@cloud.example.test/cloud/dav",
            "https://cloud.example.test/cloud/dav#private",
            "https://cloud.example.test/cloud/%2e%2e/admin",
            "https://cloud.example.test/cloud/%252e%252e/admin",
            "https://cloud.example.test/cloud/safe%252f%252e%252e%252fadmin",
            "https://cloud.example.test/cloud/safe%255c%252e%252e%255cadmin",
        ).forEach { target ->
            assertFailsWith<IllegalArgumentException>(target) { policy.requestBuilder(target) }
        }

        val accepted = policy.requestBuilder("https://cloud.example.test/cloud/dav").build()
        assertEquals("Basic YWxpY2U6c2VjcmV0", accepted.header("Authorization"))
    }

    @Test
    fun `forged tagged request is revalidated before execution`() {
        val valid = policy.requestBuilder("https://cloud.example.test/cloud/dav").build()
        val forged = valid.newBuilder()
            .url("https://cloud.example.test/cloud/%252e%252e/admin")
            .build()
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        var executed = false

        assertFailsWith<IllegalArgumentException> {
            executeNextcloudAuthenticatedRequest(
                client,
                forged,
                executeCall = {
                    executed = true
                    error("The invalid target reached the HTTP client.")
                },
            ) { Unit }
        }
        assertFalse(executed)
    }

    @Test
    fun `method-changing redirects are rejected while 307 and 308 preserve request content`() {
        val body = "payload".toRequestBody()
        val request = policy.requestBuilder("https://cloud.example.test/cloud/dav/source")
            .method("PROPPATCH", body)
            .build()

        listOf(301, 302, 303).forEach { status ->
            val rejected = assertIs<NextcloudAuthenticatedRedirectDecision.Reject>(
                policy.redirectDecision(request, status, "/cloud/dav/target"),
            )
            assertEquals(NextcloudAuthenticatedRedirectRejection.MethodMayChange, rejected.reason)
        }
        listOf(307, 308).forEach { status ->
            val followed = assertIs<NextcloudAuthenticatedRedirectDecision.Follow>(
                policy.redirectDecision(request, status, "/cloud/dav/target"),
            )
            assertEquals("PROPPATCH", followed.request.method)
            assertSame(body, followed.request.body)
            assertEquals(request.header("Authorization"), followed.request.header("Authorization"))
        }
    }

    @Test
    fun `307 and 308 reject one-shot and duplex bodies`() {
        val bodies = listOf(
            nonReplayableBody(oneShot = true),
            nonReplayableBody(duplex = true),
        )

        bodies.forEach { body ->
            val request = policy.requestBuilder("https://cloud.example.test/cloud/dav/source")
                .put(body)
                .build()
            listOf(307, 308).forEach { status ->
                val rejected = assertIs<NextcloudAuthenticatedRedirectDecision.Reject>(
                    policy.redirectDecision(request, status, "/cloud/dav/target"),
                )
                assertEquals(NextcloudAuthenticatedRedirectRejection.NonReplayableBody, rejected.reason)
            }
        }
    }

    @Test
    fun `redirect rejects absent invalid and unsafe locations`() {
        val request = policy.requestBuilder("https://cloud.example.test/cloud/dav/source").get().build()
        val cases = listOf(
            null to NextcloudAuthenticatedRedirectRejection.MissingLocation,
            "%ZZ" to NextcloudAuthenticatedRedirectRejection.InvalidLocation,
            "https://other.example.test/cloud/dav" to NextcloudAuthenticatedRedirectRejection.UnsafeTarget,
            "http://cloud.example.test/cloud/dav" to NextcloudAuthenticatedRedirectRejection.UnsafeTarget,
            "https://cloud.example.test:444/cloud/dav" to NextcloudAuthenticatedRedirectRejection.UnsafeTarget,
            "/cloud2/dav" to NextcloudAuthenticatedRedirectRejection.UnsafeTarget,
            "https://user@cloud.example.test/cloud/dav" to NextcloudAuthenticatedRedirectRejection.UnsafeTarget,
            "/cloud/%252e%252e/admin" to NextcloudAuthenticatedRedirectRejection.InvalidLocation,
        )

        cases.forEach { (location, reason) ->
            val rejected = assertIs<NextcloudAuthenticatedRedirectDecision.Reject>(
                policy.redirectDecision(request, 307, location),
            )
            assertEquals(reason, rejected.reason, location)
        }
    }

    @Test
    fun `executor caps authenticated redirect hops`() {
        MockWebServer().use { server ->
            repeat(4) {
                server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "/cloud/loop").build())
            }
            server.start()
            val loopPolicy = NextcloudAuthenticatedRequestPolicy(
                NextcloudSession(server.url("/cloud").toString(), "alice", "secret"),
                "policy-test",
            )
            val request = loopPolicy.requestBuilder(server.url("/cloud/start").toString()).get().build()
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

            val failure = assertFailsWith<NextcloudAuthenticatedRedirectException> {
                executeNextcloudAuthenticatedRequest(client, request) { Unit }
            }

            assertEquals(NextcloudAuthenticatedRedirectRejection.TooManyHops, failure.reason)
            repeat(4) { assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null) }
        }
    }

    @Test
    fun `executor refuses clients with automatic redirects`() {
        val request = policy.requestBuilder("https://cloud.example.test/cloud/dav").get().build()

        assertFailsWith<IllegalArgumentException> {
            executeNextcloudAuthenticatedRequest(OkHttpClient(), request) { Unit }
        }
    }

    private fun nonReplayableBody(oneShot: Boolean = false, duplex: Boolean = false) =
        object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun writeTo(sink: BufferedSink) = Unit
            override fun isOneShot(): Boolean = oneShot
            override fun isDuplex(): Boolean = duplex
        }
}
