package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class AndroidDetachedDownloadTransportTest {
    @Test
    fun `detached download exposes and validates the response etag`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", "\"version-2\"")
                    .body("historical")
                    .build(),
            )
            server.start()
            val destination = Files.createTempFile("ncn-detached-etag-", ".tmp").toFile()
            try {
                val result = FileOutputStream(destination).use { output ->
                    downloadAndroidDetachedFile(
                        client = OkHttpClient(),
                        session = NextcloudSession(server.url("/").toString(), "alice", "secret"),
                        url = server.url("/version.bin").toString(),
                        output = output,
                        maximumBytes = Long.MAX_VALUE,
                        userAgent = "test",
                        failureMessage = { status -> "HTTP $status" },
                        limitMessage = "Too large",
                        handoffEtag = "\"listed-version\"",
                        validateResponseEtag = { returned -> assertEquals("\"version-2\"", returned) },
                        onNetworkFailure = { _, _, _ -> },
                    )
                }

                assertEquals("\"listed-version\"", result.etag)
                assertEquals("historical", destination.readText())
            } finally {
                destination.delete()
            }
        }
    }

    @Test
    fun `coroutine cancellation cancels an in-flight detached download`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("eventually")
                    .bodyDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            server.start()
            val destination = Files.createTempFile("ncn-detached-cancel-", ".tmp").toFile()
            try {
                val job = launch(Dispatchers.Default) {
                    FileOutputStream(destination).use { output ->
                        downloadAndroidDetachedFile(
                            client = OkHttpClient(),
                            session = NextcloudSession(server.url("/").toString(), "alïce", "pässword"),
                            url = server.url("/large.bin").toString(),
                            output = output,
                            maximumBytes = Long.MAX_VALUE,
                            userAgent = "test",
                            failureMessage = { status -> "HTTP $status" },
                            limitMessage = "Too large",
                            onNetworkFailure = { _, _, _ -> },
                        )
                    }
                }
                val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val expected = Base64.getEncoder().encodeToString(
                    "alïce:pässword".toByteArray(StandardCharsets.UTF_8),
                )
                assertEquals("Basic $expected", request.headers["Authorization"])

                withTimeout(2_000L) { job.cancelAndJoin() }

                assertTrue(job.isCancelled)
            } finally {
                destination.delete()
            }
        }
    }
}
