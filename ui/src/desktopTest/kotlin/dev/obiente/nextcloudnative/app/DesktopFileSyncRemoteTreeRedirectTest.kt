package dev.obiente.nextcloudnative.app

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class DesktopFileSyncRemoteTreeRedirectTest {
    @Test
    fun `desktop DAV listing manually follows an account-bound 308 with credentials`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(308)
                    .addHeader("Location", "/cloud/remote.php/dav/files/alice/Vault/?redirected=1")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(207).body(listing()).build())
            server.start()
            val tree = DesktopFileSyncRemoteTree(
                session = NextcloudSession(server.url("/cloud").toString(), "alice", "secret"),
                userId = "alice",
                remoteRootPath = "Vault",
                httpClient = OkHttpClient(),
            )

            val result = tree.scan()

            assertEquals(listOf("note.txt"), result.map { it.entry.relativePath })
            val initial = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val redirected = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("PROPFIND", initial.method)
            assertEquals("PROPFIND", redirected.method)
            assertEquals("Basic YWxpY2U6c2VjcmV0", initial.headers["Authorization"])
            assertEquals(initial.headers["Authorization"], redirected.headers["Authorization"])
            assertEquals("1", redirected.url.queryParameter("redirected"))
        }
    }

    @Test
    fun `302 DAV method rejection becomes a desktop file sync error`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(302)
                    .addHeader("Location", "/cloud/remote.php/dav/files/alice/Vault/?redirected=1")
                    .build(),
            )
            server.start()
            val tree = DesktopFileSyncRemoteTree(
                session = NextcloudSession(server.url("/cloud").toString(), "alice", "secret"),
                userId = "alice",
                remoteRootPath = "Vault",
                httpClient = OkHttpClient(),
            )

            val failure = assertFailsWith<DesktopFileSyncHttpStatusException> { tree.scan() }

            assertEquals(302, failure.statusCode)
            assertEquals("method_may_change", failure.redirectReason)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `mutation executor translates redirect rejection into file sync error`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(302)
                    .addHeader("Location", "/cloud/remote.php/dav/files/alice/target.bin")
                    .build(),
            )
            server.start()
            val policy = NextcloudAuthenticatedRequestPolicy(
                NextcloudSession(server.url("/cloud").toString(), "alice", "secret"),
                "redirect-test",
            )
            val request = policy.requestBuilder(server.url("/cloud/source.bin").toString())
                .put("payload".toRequestBody())
                .build()
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

            val failure = assertFailsWith<DesktopFileSyncHttpStatusException> {
                DesktopHttpMutationExecutor(client).execute(request, onAmbiguousNetworkResult = {}) { Unit }
            }

            assertEquals(302, failure.statusCode)
            assertEquals("method_may_change", failure.redirectReason)
            assertEquals(1, server.requestCount)
        }
    }

    private fun listing(): String =
        """
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/cloud/remote.php/dav/files/alice/Vault/</d:href>
            <d:propstat><d:prop><d:getetag>folder-etag</d:getetag>
              <d:resourcetype><d:collection/></d:resourcetype>
            </d:prop></d:propstat></d:response>
          <d:response><d:href>/cloud/remote.php/dav/files/alice/Vault/note.txt</d:href>
            <d:propstat><d:prop><d:getetag>file-etag</d:getetag>
              <d:getcontentlength>4</d:getcontentlength><d:resourcetype/>
            </d:prop></d:propstat></d:response>
        </d:multistatus>
        """.trimIndent()
}
