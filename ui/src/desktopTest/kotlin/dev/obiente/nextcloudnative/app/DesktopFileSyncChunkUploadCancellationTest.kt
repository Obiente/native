package dev.obiente.nextcloudnative.app

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class DesktopFileSyncChunkUploadCancellationTest {
    @Test
    fun `pausing sync cancels an in-flight chunk assembly request`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(201)
                    .headersDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            server.start()
            val active = AtomicBoolean(true)
            val failure = AtomicReference<Throwable?>()
            val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
            val tree = DesktopFileSyncRemoteTree(session, "alice", "Vault", OkHttpClient())
            val remote = tree.resumableUploadRemote(shouldContinue = active::get)
            val upload = thread(name = "desktop-chunk-assembly-test") {
                runCatching {
                    remote.commitChunksToOwnedStage(
                        "01234567-89ab-cdef-0123-456789abcdef",
                        "large.bin",
                        25L * 1024L * 1024L,
                    )
                }.exceptionOrNull()?.let(failure::set)
            }

            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            active.set(false)
            upload.join(2_000L)

            assertFalse(upload.isAlive, "The paused assembly call did not release promptly.")
            assertIs<CancellationException>(failure.get())
        }
    }
}
