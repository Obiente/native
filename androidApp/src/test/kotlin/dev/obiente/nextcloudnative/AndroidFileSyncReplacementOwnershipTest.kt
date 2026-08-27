package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.FileSyncPendingUploadCleanup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class AndroidFileSyncReplacementOwnershipTest {
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
            server.enqueue(MockResponse.Builder().code(412).build())
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
