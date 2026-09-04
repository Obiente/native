package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class DesktopFileSyncChunkUploadTransportTest {
    @Test
    fun `chunk put follows an account-bound 307 without changing its body`() {
        MockWebServer().use { server ->
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            server.enqueue(
                MockResponse.Builder().code(307)
                    .addHeader(
                        "Location",
                        "/cloud/remote.php/dav/uploads/alice/$uploadId/00001?redirected=1",
                    )
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(201).build())
            server.start()
            val source = File.createTempFile("desktop-chunk-redirect-", ".bin")
            source.writeText("chunk")
            try {
                val remote = remote(server, OkHttpClient())

                remote.uploadChunk(uploadId, "large.bin", source, NextcloudUploadChunk(1, 0, source.length()))

                val initial = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val redirected = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals(listOf("PUT", "PUT"), listOf(initial.method, redirected.method))
                assertEquals("chunk", initial.body?.utf8())
                assertEquals("chunk", redirected.body?.utf8())
                assertEquals("Basic YWxpY2U6c2VjcmV0", initial.headers["Authorization"])
                assertEquals(initial.headers["Authorization"], redirected.headers["Authorization"])
                assertEquals("1", redirected.url.queryParameter("redirected"))
            } finally {
                assertTrue(source.delete())
            }
        }
    }

    @Test
    fun `chunk collection read follows an account-bound 308 with its DAV method`() {
        MockWebServer().use { server ->
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            server.enqueue(
                MockResponse.Builder().code(308)
                    .addHeader("Location", "/cloud/remote.php/dav/uploads/alice/$uploadId?redirected=1")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder().code(207)
                    .body("<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>")
                    .build(),
            )
            server.start()

            assertEquals(emptyMap(), remote(server, OkHttpClient()).listChunkCollection(uploadId))

            val initial = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val redirected = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(listOf("PROPFIND", "PROPFIND"), listOf(initial.method, redirected.method))
            assertEquals(initial.body?.utf8(), redirected.body?.utf8())
            assertEquals(initial.headers["Authorization"], redirected.headers["Authorization"])
        }
    }

    @Test
    fun `chunk collection redirect rejection becomes a desktop file sync error`() {
        MockWebServer().use { server ->
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            server.enqueue(
                MockResponse.Builder().code(302)
                    .addHeader("Location", "/cloud/remote.php/dav/uploads/alice/$uploadId?redirected=1")
                    .build(),
            )
            server.start()

            val failure = assertFailsWith<DesktopFileSyncHttpStatusException> {
                remote(server, OkHttpClient()).listChunkCollection(uploadId)
            }

            assertEquals(302, failure.statusCode)
            assertEquals("method_may_change", failure.redirectReason)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `lost assembly response is classified as ambiguous after exchange`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(201).addHeader("ETag", "stage-etag").build())
            server.start()
            val ambiguous = mutableListOf<String>()
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).close()
                throw IOException("response lost")
            }.build()
            val remote = remote(server, client, ambiguous::add)

            assertFailsWith<DesktopFileSyncAmbiguousMutationException> {
                remote.commitChunksToOwnedStage(
                    "01234567-89ab-cdef-0123-456789abcdef",
                    "large.bin",
                    25L * 1024L * 1024L,
                )
            }

            assertEquals(listOf("large.bin"), ambiguous)
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        }
    }

    private fun remote(
        server: MockWebServer,
        client: OkHttpClient,
        onAmbiguous: (String) -> Unit = {},
    ): JvmResumableNextcloudUploadRemote {
        val session = NextcloudSession(server.url("/cloud").toString(), "alice", "secret")
        return DesktopFileSyncRemoteTree(
            session = session,
            userId = "alice",
            remoteRootPath = "Vault",
            httpClient = client,
            onAmbiguousMutationResult = onAmbiguous,
        ).resumableUploadRemote()
    }
}
