package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class NextcloudDocumentWebDavRedirectTest {
    @Test
    fun `document read manually follows an account-bound 307 with credentials`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(307)
                    .addHeader("Location", "/cloud/remote.php/dav/files/alice/redirected.txt")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(200).body("document").build())
            server.start()
            val session = NextcloudSession(server.url("/cloud").toString(), "alice", "secret")
            val output = ByteArrayOutputStream()

            NextcloudDocumentWebDav(OkHttpClient()).readFile(
                session = session,
                userId = "alice",
                path = "source.txt",
                destination = output,
                maximumBytes = 1024,
            )

            assertEquals("document", output.toString(Charsets.UTF_8.name()))
            val initial = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val redirected = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", initial.method)
            assertEquals("GET", redirected.method)
            assertEquals("Basic YWxpY2U6c2VjcmV0", initial.headers["Authorization"])
            assertEquals(initial.headers["Authorization"], redirected.headers["Authorization"])
            assertEquals("/cloud/remote.php/dav/files/alice/redirected.txt", redirected.url.encodedPath)
        }
    }

    @Test
    fun `bodyless document GET follows an account-bound 302`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(302)
                    .addHeader("Location", "/cloud/remote.php/dav/files/alice/redirected.txt")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(200).body("document").build())
            server.start()
            val output = ByteArrayOutputStream()

            NextcloudDocumentWebDav(OkHttpClient()).readFile(
                session = NextcloudSession(server.url("/cloud").toString(), "alice", "secret"),
                userId = "alice",
                path = "source.txt",
                destination = output,
                maximumBytes = 1024,
            )

            assertEquals("document", output.toString(Charsets.UTF_8.name()))
            assertEquals(
                listOf("GET", "GET"),
                List(2) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).method },
            )
        }
    }

    @Test
    fun `unsafe redirect becomes a document protocol error with diagnostics`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(307)
                    .addHeader("Location", "/outside/remote.php/dav/files/alice/source.txt")
                    .build(),
            )
            server.start()

            val failure = assertFailsWith<DocumentWebDavException> {
                NextcloudDocumentWebDav(OkHttpClient()).readFile(
                    session = NextcloudSession(server.url("/cloud").toString(), "alice", "secret"),
                    userId = "alice",
                    path = "source.txt",
                    destination = ByteArrayOutputStream(),
                    maximumBytes = 1024,
                )
            }

            assertEquals(DocumentWebDavError.RedirectRejected, failure.error)
            assertEquals(307, failure.status)
            assertEquals("unsafe_target", failure.redirectReason)
            assertIs<dev.obiente.nextcloudnative.app.NextcloudAuthenticatedRedirectException>(failure.cause)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `302 mutation rejection becomes a document protocol error`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(302)
                    .addHeader("Location", "/cloud/remote.php/dav/files/alice/redirected.txt")
                    .build(),
            )
            server.start()
            val session = NextcloudSession(server.url("/cloud").toString(), "alice", "secret")
            val webDav = NextcloudDocumentWebDav(OkHttpClient())
            val request = webDav.requestBuilder(session, server.url("/cloud/source.txt").toString())
                .put("payload".toRequestBody())
                .build()

            val failure = assertFailsWith<DocumentWebDavException> {
                webDav.execute(request, "write document")
            }

            assertEquals(DocumentWebDavError.RedirectRejected, failure.error)
            assertEquals(302, failure.status)
            assertEquals("method_may_change", failure.redirectReason)
            assertEquals(1, server.requestCount)
        }
    }
}
