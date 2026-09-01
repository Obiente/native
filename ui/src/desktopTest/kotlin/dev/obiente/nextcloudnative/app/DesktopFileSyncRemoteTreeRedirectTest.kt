package dev.obiente.nextcloudnative.app

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

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
