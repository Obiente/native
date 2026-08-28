package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.FileSyncPendingUploadCleanup
import dev.obiente.nextcloudnative.app.cleanupJvmFileSyncOwnedUploads
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class AndroidFileSyncReplacementOwnershipTest {
    @Test
    fun `published chunk replacement is verified before its backup is retired`() {
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
                    .body("same").build(),
            )
            repeat(2) {
                server.enqueue(
                    MockResponse.Builder().code(207).addHeader("Content-Type", "application/xml")
                        .body(listing).build(),
                )
            }
            server.enqueue(MockResponse.Builder().code(204).build())
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
                accountId = "account",
                localRootId = "root",
                remoteRootPath = "Vault",
                configuration = FileSyncConfiguration(deviceLabel = "Phone"),
                pendingUploadCleanups = listOf(cleanup),
            )
            val remote = androidFileSyncOwnedRemoteTree(
                NextcloudSession(server.url("/").toString().trimEnd('/'), "alice", "app-password"),
                "alice",
                pair,
                NextcloudDocumentWebDav(),
            )

            val result = cleanupJvmFileSyncOwnedUploads(
                remote,
                FileSyncCoordinatorState(listOf(pair)),
                pair.id,
                listOf(cleanup),
            )

            assertTrue(result.state.pairs.single().pendingUploadCleanups.isEmpty())
            assertTrue(result.unresolvedUploads.isEmpty())
            assertEquals(listOf("DELETE", "PROPFIND", "GET", "PROPFIND", "PROPFIND", "DELETE"),
                List(6) { server.takeRequest().method })
        }
    }

    @Test
    fun `definitive published mismatch preserves the protected directory as a conflict`() {
        MockWebServer().use { server ->
            server.start()
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            val listing = """
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/remote.php/dav/files/alice/Vault/archive.bin</d:href>
                    <d:propstat><d:prop><d:displayname>archive.bin</d:displayname>
                      <d:getetag>concurrent-etag</d:getetag><d:getcontentlength>5</d:getcontentlength>
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
            repeat(5) {
                server.enqueue(
                    MockResponse.Builder().code(207).addHeader("Content-Type", "application/xml")
                        .body(listing).build(),
                )
            }
            server.enqueue(MockResponse.Builder().code(201).build())
            val tree = AndroidFileSyncRemoteTree(
                NextcloudSession(server.url("/").toString().trimEnd('/'), "alice", "app-password"),
                "alice",
                "Vault",
                NextcloudDocumentWebDav(),
                ownedUploadIds = setOf(uploadId),
                ownedUploadPaths = mapOf(uploadId to "archive.bin"),
                ownedReplacementBackupEtags = mapOf(uploadId to "directory-etag"),
            )

            val reconciled = tree.reconcilePublishedReplacement(
                relativePath = "archive.bin",
                uploadId = uploadId,
                expectedSizeBytes = 4,
                expectedContentHash = "sha256:" + "55".repeat(32),
                expectedBackupEtag = "directory-etag",
            )

            assertEquals(true, reconciled)
            val requests = List(server.requestCount) { server.takeRequest() }
            val move = requests.single { it.method == "MOVE" }
            assertTrue(move.url.encodedPath.endsWith(".nextcloud-native-backup-$uploadId"))
            assertTrue(
                move.headers["Destination"].orEmpty().endsWith(".nextcloud-native-conflict-$uploadId"),
            )
            assertTrue(requests.none { it.method == "GET" || it.method == "DELETE" })
        }
    }

    @Test
    fun `pair removal remote retains replacement backup ownership`() {
        MockWebServer().use { server ->
            server.start()
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            val backupListing = """
                <d:multistatus xmlns:d="DAV:"><d:response>
                  <d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-backup-$uploadId/</d:href>
                  <d:propstat><d:prop>
                    <d:displayname>.nextcloud-native-backup-$uploadId</d:displayname>
                    <d:getetag>directory-etag</d:getetag>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop></d:propstat>
                </d:response></d:multistatus>
            """.trimIndent()
            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(MockResponse.Builder().code(404).build())
            repeat(2) {
                server.enqueue(
                    MockResponse.Builder().code(207).addHeader("Content-Type", "application/xml")
                        .body(backupListing).build(),
                )
            }
            server.enqueue(MockResponse.Builder().code(201).build())
            server.enqueue(
                MockResponse.Builder().code(207).addHeader("Content-Type", "application/xml")
                    .body(
                        """
                        <d:multistatus xmlns:d="DAV:"><d:response>
                          <d:href>/remote.php/dav/files/alice/Vault/archive.bin/</d:href>
                          <d:propstat><d:prop><d:displayname>archive.bin</d:displayname>
                            <d:getetag>directory-etag</d:getetag>
                            <d:resourcetype><d:collection/></d:resourcetype>
                          </d:prop></d:propstat>
                        </d:response></d:multistatus>
                        """.trimIndent(),
                    ).build(),
            )
            val cleanup = FileSyncPendingUploadCleanup(
                uploadId = uploadId,
                relativePath = "archive.bin",
                replacementBackupEtag = "directory-etag",
            )
            val pair = FileSyncPair(
                id = "pair",
                accountId = "account",
                localRootId = "root",
                remoteRootPath = "Vault",
                configuration = FileSyncConfiguration(deviceLabel = "Phone"),
                pendingUploadCleanups = listOf(cleanup),
            )
            val remote = androidFileSyncOwnedRemoteTree(
                NextcloudSession(server.url("/").toString().trimEnd('/'), "alice", "app-password"),
                "alice",
                pair,
                NextcloudDocumentWebDav(),
            )

            assertEquals(true, remote.discardOwnedUpload(uploadId, "archive.bin", "stage-etag"))

            assertEquals(6, server.requestCount)
            assertEquals("DELETE", server.takeRequest().method)
            assertEquals("DELETE", server.takeRequest().method)
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("PROPFIND", server.takeRequest().method)
            val restore = server.takeRequest()
            assertEquals("MOVE", restore.method)
            assertTrue(requireNotNull(restore.headers["If"]).contains("([directory-etag])"))
            assertEquals("PROPFIND", server.takeRequest().method)
        }
    }

    @Test
    fun `replacement cleanup preserves a backup whose directory generation changed`() {
        MockWebServer().use { server ->
            server.start()
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            server.enqueue(
                MockResponse.Builder()
                    .code(207)
                    .addHeader("Content-Type", "application/xml")
                    .body(
                        """
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
                            <d:getetag>changed-directory-etag</d:getetag>
                            <d:resourcetype><d:collection/></d:resourcetype>
                          </d:prop></d:propstat>
                          </d:response>
                        </d:multistatus>
                        """.trimIndent(),
                    )
                    .build(),
            )
            val remote = AndroidFileSyncRemoteTree(
                NextcloudSession(server.url("/").toString().trimEnd('/'), "alice", "app-password"),
                "alice",
                "Vault",
                NextcloudDocumentWebDav(),
                ownedUploadIds = setOf(uploadId),
                ownedUploadPaths = mapOf(uploadId to "archive.bin"),
            ).resumableUploadRemote("directory-etag")

            assertFailsWith<IllegalArgumentException> {
                remote.completePublishedFile(uploadId, "archive.bin")
            }
            assertEquals(1, server.requestCount)
            assertEquals("PROPFIND", server.takeRequest().method)
        }
    }
}
