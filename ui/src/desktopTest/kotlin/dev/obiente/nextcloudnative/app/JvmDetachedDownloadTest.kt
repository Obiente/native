package dev.obiente.nextcloudnative.app

import java.io.FileOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class JvmDetachedDownloadTest {
    @Test
    fun `OC Etag fallback remains an explicit platform choice`() = runBlocking {
        for (acceptOcEtag in listOf(false, true)) {
            withDownload(
                MockResponse.Builder().body("body").addHeader("OC-Etag", "fallback").build(),
            ) { server, output ->
                var validated: String? = null
                val result = downloadJvmDetachedFile(
                    OkHttpClient(), NextcloudSession(server.url("/").toString(), "alice", "secret"),
                    server.url("/file").toString(), output, 100, "test", { "HTTP $it" }, "Too large",
                    handoffEtag = "handoff", validateResponseEtag = { validated = it },
                    acceptOcEtag = acceptOcEtag, onNetworkFailure = { _, _, _ -> },
                )
                assertEquals(if (acceptOcEtag) "fallback" else null, validated)
                assertEquals("handoff", result.etag)
                assertEquals(4L, result.byteCount)
            }
        }
    }

    @Test
    fun `standard Etag takes precedence and MIME is retained`() = runBlocking {
        withDownload(
            MockResponse.Builder().body("body").addHeader("ETag", "primary")
                .addHeader("OC-Etag", "fallback").addHeader("Content-Type", "text/plain").build(),
        ) { server, output ->
            val result = downloadJvmDetachedFile(
                OkHttpClient(), NextcloudSession(server.url("/").toString(), "alice", "secret"),
                server.url("/file").toString(), output, 100, "test", { "HTTP $it" }, "Too large",
                acceptOcEtag = true, onNetworkFailure = { _, _, _ -> },
            )
            assertEquals("primary", result.etag)
            assertEquals("text/plain", result.mimeType)
        }
    }

    @Test
    fun `partial responses and declared or streamed overflow are rejected`() = runBlocking {
        val responses = listOf(
            MockResponse.Builder().code(206).body("a").build(),
            MockResponse.Builder().body("too large").build(),
            MockResponse.Builder().chunkedBody("too large", 1).build(),
        )
        for (response in responses) {
            withDownload(response) { server, output ->
                assertFailsWith<IllegalStateException> {
                    downloadJvmDetachedFile(
                        OkHttpClient(), NextcloudSession(server.url("/").toString(), "alice", "secret"),
                        server.url("/file").toString(), output, 2, "test", { "HTTP $it" }, "Too large",
                        acceptOcEtag = false, onNetworkFailure = { _, _, _ -> },
                    )
                }
            }
        }
    }

    private suspend fun withDownload(
        response: MockResponse,
        block: suspend (MockWebServer, FileOutputStream) -> Unit,
    ) {
        MockWebServer().use { server ->
            server.enqueue(response)
            server.start()
            val file = Files.createTempFile("detached-contract-", ".tmp").toFile()
            try {
                FileOutputStream(file).use { block(server, it) }
            } finally {
                file.delete()
            }
        }
    }
}
