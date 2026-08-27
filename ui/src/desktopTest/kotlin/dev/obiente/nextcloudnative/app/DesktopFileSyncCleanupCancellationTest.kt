package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class DesktopFileSyncCleanupCancellationTest {
    @Test
    fun `unresolved upload cleanup blocks new pair work`() {
        MockWebServer().use { server ->
            server.start()
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(
                MockResponse.Builder().code(207).addHeader("Content-Type", "application/xml")
                    .body(
                        """
                        <d:multistatus xmlns:d="DAV:">
                          <d:response><d:href>/remote.php/dav/files/alice/Vault/archive.bin</d:href>
                            <d:propstat><d:prop><d:displayname>archive.bin</d:displayname>
                              <d:getetag>different-etag</d:getetag><d:getcontentlength>5</d:getcontentlength>
                              <d:resourcetype/>
                            </d:prop></d:propstat>
                          </d:response>
                          <d:response>
                            <d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-backup-$uploadId/</d:href>
                            <d:propstat><d:prop>
                              <d:displayname>.nextcloud-native-backup-$uploadId</d:displayname>
                              <d:getetag>directory-etag</d:getetag>
                              <d:resourcetype><d:collection/></d:resourcetype>
                            </d:prop></d:propstat>
                          </d:response>
                        </d:multistatus>
                        """.trimIndent(),
                    ).build(),
            )
            val directory = Files.createTempDirectory("desktop-sync-cleanup-block-").toFile()
            val localRoot = directory.resolve("local").apply { mkdirs() }
            val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
            val cleanup = FileSyncPendingUploadCleanup(
                uploadId = uploadId,
                relativePath = "archive.bin",
                assembledStageEtag = "stage-etag",
                replacementBackupEtag = "directory-etag",
                expectedStageSizeBytes = 4,
                expectedStageContentHash = "sha256:" + "55".repeat(32),
                publicationInFlight = true,
            )
            val pair = FileSyncPair(
                id = "pair",
                accountId = desktopFileCacheAccountId(session),
                localRootId = "root",
                remoteRootPath = "Vault",
                configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
                pendingUploadCleanups = listOf(cleanup),
            )
            val store = DesktopFileSyncStore(directory.resolve("state.db"), legacyStateFile = null)
            store.savePair(
                DesktopFileSyncPersistedState(
                    coordinator = FileSyncCoordinatorState(listOf(pair)),
                    roots = listOf(DesktopFileSyncRootRecord("root", localRoot.absolutePath, "Local")),
                ),
                pair.id,
            )
            val engine = DesktopFileSyncEngine(store, directory.resolve("staging"))

            try {
                val result = runBlocking { engine.runPair(session, "alice", pair.id) }

                assertIs<FileSyncCenterActionResult.Rejected>(result)
                assertEquals(FileSyncRejectionScope.Preflight, result.scope)
                assertEquals(2, server.requestCount)
                assertEquals(listOf(cleanup), store.loadPair(pair.id).coordinator.pairs.single().pendingUploadCleanups)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `pausing a run cancels published replacement cleanup`() {
        MockWebServer().use { server ->
            server.start()
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            val listing = """
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/remote.php/dav/files/alice/Vault/archive.bin</d:href>
                    <d:propstat><d:prop><d:displayname>archive.bin</d:displayname>
                      <d:getetag>published-etag</d:getetag><d:getcontentlength>4</d:getcontentlength>
                      <d:resourcetype/>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-backup-$uploadId/</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>.nextcloud-native-backup-$uploadId</d:displayname>
                      <d:getetag>directory-etag</d:getetag>
                      <d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
            """.trimIndent()
            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(
                MockResponse.Builder().code(207).addHeader("Content-Type", "application/xml")
                    .body(listing).build(),
            )
            server.enqueue(
                MockResponse.Builder().code(200).addHeader("ETag", "published-etag")
                    .body("same").bodyDelay(30, TimeUnit.SECONDS).build(),
            )
            val directory = Files.createTempDirectory("desktop-sync-cleanup-cancel-").toFile()
            val localRoot = directory.resolve("local").apply { mkdirs() }
            val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
            val cleanup = FileSyncPendingUploadCleanup(
                uploadId = uploadId,
                relativePath = "archive.bin",
                assembledStageEtag = "stage-etag",
                replacementBackupEtag = "directory-etag",
                expectedStageSizeBytes = 4,
                expectedStageContentHash =
                    "sha256:0967115f2813a3541eaef77de9d9d5773f1c0c04314b0bbfe4ff3b3b1c55b5d5",
                publicationInFlight = true,
            )
            val pair = FileSyncPair(
                id = "pair",
                accountId = desktopFileCacheAccountId(session),
                localRootId = "root",
                remoteRootPath = "Vault",
                configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
                pendingUploadCleanups = listOf(cleanup),
            )
            val store = DesktopFileSyncStore(directory.resolve("state.db"), legacyStateFile = null)
            store.savePair(
                DesktopFileSyncPersistedState(
                    coordinator = FileSyncCoordinatorState(listOf(pair)),
                    roots = listOf(DesktopFileSyncRootRecord("root", localRoot.absolutePath, "Local")),
                ),
                pair.id,
            )
            val engine = DesktopFileSyncEngine(store, directory.resolve("staging"))
            val active = AtomicBoolean(true)
            val failure = AtomicReference<Throwable?>()
            val run = thread(name = "desktop-cleanup-cancellation-test") {
                runCatching {
                    runBlocking {
                        engine.runPair(session, "alice", pair.id, shouldContinue = active::get)
                    }
                }.exceptionOrNull()?.let(failure::set)
            }

            try {
                repeat(3) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                active.set(false)
                run.join(2_000L)

                assertFalse(run.isAlive, "Published-file recovery kept running after the pair was paused.")
                assertTrue(
                    failure.get() is CancellationException || failure.get() is DesktopFileSyncScanStoppedException,
                    "Expected cooperative cleanup cancellation, got ${failure.get()}",
                )
                assertTrue(store.loadPair(pair.id).coordinator.pairs.single().pendingUploadCleanups.isNotEmpty())
            } finally {
                active.set(false)
                run.join(2_000L)
                directory.deleteRecursively()
            }
        }
    }
}
