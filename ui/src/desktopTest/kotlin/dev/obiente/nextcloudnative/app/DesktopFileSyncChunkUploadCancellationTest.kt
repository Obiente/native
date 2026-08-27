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
import kotlin.test.assertTrue
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

    @Test
    fun `pausing sync cancels an in-flight final publication request`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(201)
                    .headersDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            server.start()
            val active = AtomicBoolean(true)
            val ambiguous = AtomicBoolean(false)
            val failure = AtomicReference<Throwable?>()
            val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
            val tree = DesktopFileSyncRemoteTree(
                session,
                "alice",
                "Vault",
                OkHttpClient(),
                onAmbiguousMutationResult = { ambiguous.set(true) },
            )
            val remote = tree.resumableUploadRemote(shouldContinue = active::get)
            val upload = thread(name = "desktop-upload-publication-test") {
                runCatching {
                    remote.publishOwnedStage(
                        "01234567-89ab-cdef-0123-456789abcdef",
                        "large.bin",
                        "stage-etag",
                        null,
                    )
                }.exceptionOrNull()?.let(failure::set)
            }

            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            active.set(false)
            upload.join(2_000L)

            assertFalse(upload.isAlive, "The paused publication call did not release promptly.")
            assertIs<CancellationException>(failure.get())
            assertTrue(ambiguous.get(), "Cancellation after network exchange must trigger reconciliation.")
        }
    }

    @Test
    fun `pausing sync cancels direct replacement publication`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(207).body(
                    """
                    <d:multistatus xmlns:d="DAV:"><d:response>
                      <d:href>/remote.php/dav/files/alice/Vault/archive.bin/</d:href>
                      <d:propstat><d:prop><d:getetag>directory-etag</d:getetag>
                        <d:resourcetype><d:collection/></d:resourcetype>
                      </d:prop></d:propstat>
                    </d:response></d:multistatus>
                    """.trimIndent(),
                ).build(),
            )
            server.enqueue(MockResponse.Builder().code(201).build())
            server.enqueue(
                MockResponse.Builder().code(201).headersDelay(30, TimeUnit.SECONDS).build(),
            )
            server.enqueue(
                MockResponse.Builder().code(207).body(
                    """
                    <d:multistatus xmlns:d="DAV:"><d:response>
                      <d:href>/remote.php/dav/files/alice/Vault/archive.bin/</d:href>
                      <d:propstat><d:prop><d:getetag>directory-etag</d:getetag>
                        <d:resourcetype><d:collection/></d:resourcetype>
                      </d:prop></d:propstat>
                    </d:response></d:multistatus>
                    """.trimIndent(),
                ).build(),
            )
            server.start()
            val active = AtomicBoolean(true)
            val failure = AtomicReference<Throwable?>()
            val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
            val tree = DesktopFileSyncRemoteTree(session, "alice", "Vault", OkHttpClient())
            val upload = thread(name = "desktop-direct-publication-test") {
                runCatching {
                    tree.publishOwnedStageReplacingDirectory(
                        "archive.bin",
                        "01234567-89ab-cdef-0123-456789abcdef",
                        "stage-etag",
                        "directory-etag",
                        active::get,
                    )
                }.exceptionOrNull()?.let(failure::set)
            }

            repeat(3) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            active.set(false)
            upload.join(2_000L)

            assertFalse(upload.isAlive, "The paused direct publication call did not release promptly.")
            assertIs<CancellationException>(failure.get())
        }
    }
}
