package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

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
}
