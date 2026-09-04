package dev.obiente.nextcloudnative.app

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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class DesktopDetachedDownloadTest {
    @Test
    fun `cancellation stops the redirected desktop request and UTF-8 credentials are preserved`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(307)
                    .addHeader("Location", "/cloud/redirected.bin")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("eventually")
                    .bodyDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            server.start()
            val destination = Files.createTempFile("ncn-desktop-detached-cancel-", ".tmp").toFile()
            val session = NextcloudSession(server.url("/cloud").toString(), "alïce", "pässword")
            try {
                val job = launch(Dispatchers.Default) {
                    FileOutputStream(destination).use { output ->
                        downloadDesktopDetachedFile(
                            client = OkHttpClient(),
                            session = session,
                            url = server.url("/cloud/large.bin").toString(),
                            output = output,
                            maximumBytes = Long.MAX_VALUE,
                            userAgent = "test",
                            failureMessage = { status -> "HTTP $status" },
                            limitMessage = "Too large",
                            onNetworkFailure = { _, _, _ -> },
                        )
                    }
                }
                val initial = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val redirected = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val expected = Base64.getEncoder().encodeToString(
                    "alïce:pässword".toByteArray(StandardCharsets.UTF_8),
                )
                assertEquals("Basic $expected", initial.headers["Authorization"])
                assertEquals(initial.headers["Authorization"], redirected.headers["Authorization"])

                withTimeout(2_000L) { job.cancelAndJoin() }

                assertTrue(job.isCancelled)
            } finally {
                destination.delete()
            }
        }
    }

    @Test
    fun `desktop detached download rejects another origin before sending credentials`() = runBlocking {
        MockWebServer().use { accountServer ->
            MockWebServer().use { unrelatedServer ->
                accountServer.start()
                unrelatedServer.start()
                val destination = Files.createTempFile("ncn-desktop-detached-origin-", ".tmp").toFile()
                try {
                    assertFailsWith<IllegalArgumentException> {
                        FileOutputStream(destination).use { output ->
                            downloadDesktopDetachedFile(
                                client = OkHttpClient(),
                                session = NextcloudSession(
                                    accountServer.url("/cloud").toString(),
                                    "alice",
                                    "secret",
                                ),
                                url = unrelatedServer.url("/capture.bin").toString(),
                                output = output,
                                maximumBytes = Long.MAX_VALUE,
                                userAgent = "test",
                                failureMessage = { status -> "HTTP $status" },
                                limitMessage = "Too large",
                                onNetworkFailure = { _, _, _ -> },
                            )
                        }
                    }

                    assertEquals(0, accountServer.requestCount)
                    assertEquals(0, unrelatedServer.requestCount)
                } finally {
                    destination.delete()
                }
            }
        }
    }
}
