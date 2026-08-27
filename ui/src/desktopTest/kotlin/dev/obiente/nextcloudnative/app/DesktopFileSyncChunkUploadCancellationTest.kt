package dev.obiente.nextcloudnative.app

import java.io.File
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
    fun `pausing sync cancels direct verification while waiting for headers`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(207).body(
                    """
                    <d:multistatus xmlns:d="DAV:"><d:response>
                      <d:href>/remote.php/dav/files/alice/Vault/small.bin</d:href>
                      <d:propstat><d:prop><d:getetag>uploaded-etag</d:getetag>
                        <d:getcontentlength>5</d:getcontentlength><d:resourcetype/>
                      </d:prop></d:propstat>
                    </d:response></d:multistatus>
                    """.trimIndent(),
                ).build(),
            )
            server.enqueue(
                MockResponse.Builder().code(200).headersDelay(30, TimeUnit.SECONDS).body("chunk").build(),
            )
            server.start()
            val source = File.createTempFile("desktop-sync-direct-", ".bin")
            source.writeText("chunk")
            val active = AtomicBoolean(true)
            val failure = AtomicReference<Throwable?>()
            val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
            val tree = DesktopFileSyncRemoteTree(session, "alice", "Vault", OkHttpClient())
            val remote = tree.resumableUploadRemote(shouldContinue = active::get)
            val verification = thread(name = "desktop-direct-verification-test") {
                runCatching {
                    remote.verifyDirectUpload(
                        source,
                        "small.bin",
                        RemoteSyncEntry(
                            relativePath = "small.bin",
                            kind = SyncEntryKind.File,
                            etag = "uploaded-etag",
                            size = source.length(),
                            modifiedEpochMillis = 1L,
                        ),
                    )
                }.exceptionOrNull()?.let(failure::set)
            }

            try {
                repeat(2) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                active.set(false)
                verification.join(2_000L)

                assertFalse(verification.isAlive, "The paused direct verification call did not release promptly.")
                assertIs<CancellationException>(failure.get())
            } finally {
                active.set(false)
                verification.join(2_000L)
                source.delete()
            }
        }
    }

    @Test
    fun `pausing sync cancels stage verification while waiting for headers`() {
        MockWebServer().use { server ->
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            val stageName = ".nextcloud-native-$uploadId.upload"
            server.enqueue(
                MockResponse.Builder().code(207).body(
                    """
                    <d:multistatus xmlns:d="DAV:"><d:response>
                      <d:href>/remote.php/dav/files/alice/Vault/$stageName</d:href>
                      <d:propstat><d:prop><d:getetag>stage-etag</d:getetag>
                        <d:getcontentlength>5</d:getcontentlength><d:resourcetype/>
                      </d:prop></d:propstat>
                    </d:response></d:multistatus>
                    """.trimIndent(),
                ).build(),
            )
            server.enqueue(
                MockResponse.Builder().code(200).headersDelay(30, TimeUnit.SECONDS).body("chunk").build(),
            )
            server.start()
            val source = File.createTempFile("desktop-sync-stage-", ".bin")
            source.writeText("chunk")
            val active = AtomicBoolean(true)
            val failure = AtomicReference<Throwable?>()
            val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
            val tree = DesktopFileSyncRemoteTree(session, "alice", "Vault", OkHttpClient())
            val remote = tree.resumableUploadRemote(shouldContinue = active::get)
            val verification = thread(name = "desktop-stage-verification-test") {
                runCatching {
                    remote.verifyOwnedStage(uploadId, "large.bin", source, "stage-etag")
                }.exceptionOrNull()?.let(failure::set)
            }

            try {
                repeat(2) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                active.set(false)
                verification.join(2_000L)

                assertFalse(verification.isAlive, "The paused stage verification call did not release promptly.")
                assertIs<CancellationException>(failure.get())
            } finally {
                active.set(false)
                verification.join(2_000L)
                source.delete()
            }
        }
    }

    @Test
    fun `pausing sync cancels resumed chunk discovery`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(207)
                    .headersDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            server.start()
            val active = AtomicBoolean(true)
            val failure = AtomicReference<Throwable?>()
            val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
            val tree = DesktopFileSyncRemoteTree(session, "alice", "Vault", OkHttpClient())
            val remote = tree.resumableUploadRemote(shouldContinue = active::get)
            val upload = thread(name = "desktop-chunk-discovery-test") {
                runCatching {
                    remote.listChunkCollection("01234567-89ab-cdef-0123-456789abcdef")
                }.exceptionOrNull()?.let(failure::set)
            }

            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            active.set(false)
            upload.join(2_000L)

            assertFalse(upload.isAlive, "The paused chunk discovery call did not release promptly.")
            assertIs<CancellationException>(failure.get())
        }
    }

    @Test
    fun `pausing sync cancels a chunk put while waiting for its response`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(201)
                    .headersDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            server.start()
            val source = File.createTempFile("desktop-sync-chunk-", ".bin")
            source.writeText("chunk")
            val active = AtomicBoolean(true)
            val failure = AtomicReference<Throwable?>()
            val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
            val tree = DesktopFileSyncRemoteTree(session, "alice", "Vault", OkHttpClient())
            val remote = tree.resumableUploadRemote(shouldContinue = active::get)
            val upload = thread(name = "desktop-chunk-put-test") {
                runCatching {
                    remote.uploadChunk(
                        "01234567-89ab-cdef-0123-456789abcdef",
                        "large.bin",
                        source,
                        NextcloudUploadChunk(1, 0, source.length()),
                    )
                }.exceptionOrNull()?.let(failure::set)
            }

            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                active.set(false)
                upload.join(2_000L)

                assertFalse(upload.isAlive, "The paused chunk PUT did not release promptly.")
                assertIs<CancellationException>(failure.get())
            } finally {
                active.set(false)
                upload.join(2_000L)
                source.delete()
            }
        }
    }

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
