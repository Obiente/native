package dev.obiente.nextcloudnative.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class DesktopFileSyncCancellableCallTest {
    @Test
    fun `pause cancels the final redirected call while its body is consumed`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(307)
                    .addHeader("Location", "/cloud/final")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("blocked-response-body")
                    .throttleBody(1, 30, TimeUnit.SECONDS)
                    .build(),
            )
            server.start()
            val policy = NextcloudAuthenticatedRequestPolicy(
                NextcloudSession(server.url("/cloud").toString(), "alice", "secret"),
                "cancellation-test",
            )
            val request = policy.requestBuilder(server.url("/cloud/source").toString()).get().build()
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
            val shouldContinue = AtomicBoolean(true)
            val bodyConsumptionStarted = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<String> {
                    withDesktopFileSyncCallCancellation(shouldContinue::get) { executeCall ->
                        executeNextcloudAuthenticatedRequest(client, request, executeCall) { response ->
                            bodyConsumptionStarted.countDown()
                            response.body.string()
                        }
                    }
                }
                assertTrue(bodyConsumptionStarted.await(2, TimeUnit.SECONDS))

                shouldContinue.set(false)

                val failure = runCatching { result.get(2, TimeUnit.SECONDS) }.exceptionOrNull()
                assertIs<CancellationException>(assertIs<ExecutionException>(failure).cause)
                assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
                assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
