package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.hashExactJvmFileSyncSlice
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient

class NextcloudDocumentWebDavTest {
    @Test
    fun ownedUploadCleanupAcceptsMissingCollectionAndAReplacedStageWithoutListingItsParent() =
        RecordingServer().use { server ->
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            server.enqueue(404)
            server.enqueue(412)
            server.enqueue(404)
            val remote = AndroidFileSyncRemoteTree(
                server.session,
                "alice",
                "Vault",
                NextcloudDocumentWebDav(),
                ownedUploadIds = setOf(uploadId),
                ownedUploadPaths = mapOf(uploadId to "nested/large.bin"),
            )

            remote.discardOwnedUpload(uploadId, "nested/large.bin", "owned-stage-etag")

            val collectionDelete = server.request(0)
            val stageDelete = server.request(1)
            assertEquals("DELETE", collectionDelete.method)
            assertTrue(collectionDelete.path.endsWith("/uploads/alice/$uploadId"))
            assertEquals("DELETE", stageDelete.method)
            assertTrue(stageDelete.path.endsWith("/Vault/nested/.nextcloud-native-$uploadId.upload"))
            assertEquals("owned-stage-etag", stageDelete.header("If-Match"))
            val backupProbe = server.request(2)
            assertEquals("PROPFIND", backupProbe.method)
            assertEquals("0", backupProbe.header("Depth"))
            assertTrue(backupProbe.path.endsWith("/Vault/nested/.nextcloud-native-backup-$uploadId"))
        }

    @Test
    fun remoteSyncScanHidesOnlyUploadStagesDurablyOwnedByThisPair() = RecordingServer().use { server ->
        val ownedId = "01234567-89ab-cdef-0123-456789abcdef"
        val userId = "fedcba98-7654-3210-fedc-ba9876543210"
        server.enqueue(
            207,
            body = """
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/remote.php/dav/files/alice/Vault/</d:href><d:propstat><d:prop>
                    <d:displayname>Vault</d:displayname><d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop></d:propstat></d:response>
                  <d:response><d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-$ownedId.upload</d:href>
                    <d:propstat><d:prop><d:displayname>.nextcloud-native-$ownedId.upload</d:displayname>
                      <d:getetag>owned-etag</d:getetag><d:getcontentlength>1</d:getcontentlength>
                      <d:resourcetype/></d:prop></d:propstat></d:response>
                  <d:response><d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-$userId.upload</d:href>
                    <d:propstat><d:prop><d:displayname>.nextcloud-native-$userId.upload</d:displayname>
                      <d:getetag>user-etag</d:getetag><d:getcontentlength>1</d:getcontentlength>
                      <d:resourcetype/></d:prop></d:propstat></d:response>
                </d:multistatus>
            """.trimIndent(),
        )
        val remote = AndroidFileSyncRemoteTree(
            server.session,
            "alice",
            "Vault",
            NextcloudDocumentWebDav(),
            ownedUploadIds = setOf(ownedId),
        )

        assertEquals(
            listOf(".nextcloud-native-$userId.upload"),
            remote.scan().map { it.entry.relativePath },
        )
    }

    @Test
    fun `remote sync scan retains the protected directory while publication awaits verification`() =
        RecordingServer().use { server ->
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            server.enqueue(207, body = replacementListing(uploadId, includePublishedFile = true))
            server.enqueue(207, body = protectedDirectoryListing(uploadId))
            val remote = AndroidFileSyncRemoteTree(
                server.session,
                "alice",
                "Vault",
                NextcloudDocumentWebDav(),
                ownedUploadIds = setOf(uploadId),
                ownedStageEtags = mapOf(uploadId to "published-etag"),
                ownedUploadPaths = mapOf(uploadId to "archive.bin"),
            )

            val scanned = remote.scan()

            assertEquals(listOf("archive.bin", "archive.bin/inside.txt"), scanned.map { it.entry.relativePath })
            assertEquals(dev.obiente.nextcloudnative.app.SyncEntryKind.Directory, scanned.first().entry.kind)
            assertEquals("directory-etag", scanned.first().entry.etag)
            assertEquals(dev.obiente.nextcloudnative.app.SyncEntryKind.File, scanned.last().entry.kind)
            val protectedDirectoryRead = server.request(1)
            assertTrue(protectedDirectoryRead.path.endsWith("/Vault/.nextcloud-native-backup-$uploadId"))
        }

    @Test
    fun `remote sync scan surfaces a concurrent destination instead of its owned backup`() =
        RecordingServer().use { server ->
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            server.enqueue(207, body = replacementListing(uploadId, includePublishedFile = true))
            val remote = AndroidFileSyncRemoteTree(
                server.session,
                "alice",
                "Vault",
                NextcloudDocumentWebDav(),
                ownedUploadIds = setOf(uploadId),
                ownedStageEtags = mapOf(uploadId to "stage-etag"),
                ownedUploadPaths = mapOf(uploadId to "archive.bin"),
            )

            val scanned = remote.scan()

            assertEquals(listOf("archive.bin"), scanned.map { it.entry.relativePath })
            assertEquals(dev.obiente.nextcloudnative.app.SyncEntryKind.File, scanned.single().entry.kind)
            assertEquals("published-etag", scanned.single().entry.etag)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `verified chunk stage replaces a directory through a protected backup`() = RecordingServer().use { server ->
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        server.enqueue(207, body = directoryListing())
        server.enqueue(207, body = directoryListing())
        server.enqueue(201)
        server.enqueue(201)
        server.enqueue(207, body = replacementListing(uploadId, includePublishedFile = true))
        server.enqueue(207, body = replacementListing(uploadId, includePublishedFile = true))
        server.enqueue(204)
        val remote = AndroidFileSyncRemoteTree(
            server.session,
            "alice",
            "Vault",
            NextcloudDocumentWebDav(),
            ownedUploadIds = setOf(uploadId),
            ownedUploadPaths = mapOf(uploadId to "archive.bin"),
        )

        val published = remote.publishOwnedStageReplacingDirectory(
            uploadId,
            "archive.bin",
            "stage-etag",
            "directory-etag",
        )

        assertEquals("published-etag", published.etag)
        val protect = server.request(2)
        assertEquals("MOVE", protect.method)
        assertEquals("F", protect.header("Overwrite"))
        assertTrue(protect.header("If").orEmpty().contains("directory-etag"))
        val publish = server.request(3)
        assertEquals("MOVE", publish.method)
        assertEquals("F", publish.header("Overwrite"))
        assertEquals("stage-etag", publish.header("If-Match"))
        val cleanup = server.request(6)
        assertEquals("DELETE", cleanup.method)
        assertTrue(cleanup.path.contains(".nextcloud-native-backup-$uploadId"))
        assertTrue(cleanup.header("If").orEmpty().contains("directory-etag"))
    }

    @Test
    fun `ambiguous replacement publication verifies bytes and retires its directory backup`() =
        RecordingServer().use { server ->
            val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
            val source = Files.createTempFile("android-published-replacement-", ".bin").toFile()
            try {
                source.writeText("same")
                server.enqueue(207, body = replacementListing(uploadId, includePublishedFile = true, publishedBytes = 4))
                server.enqueue(200, headers = mapOf("ETag" to "published-etag"), body = "same")
                server.enqueue(207, body = replacementListing(uploadId, includePublishedFile = true, publishedBytes = 4))
                server.enqueue(204)
                val remote = AndroidFileSyncRemoteTree(
                    server.session,
                    "alice",
                    "Vault",
                    NextcloudDocumentWebDav(),
                    ownedUploadIds = setOf(uploadId),
                    ownedUploadPaths = mapOf(uploadId to "archive.bin"),
                ).resumableUploadRemote("directory-etag")

                val verified = remote.verifyPublishedFile(
                    uploadId,
                    source,
                    "archive.bin",
                    dev.obiente.nextcloudnative.app.RemoteSyncEntry(
                        "archive.bin",
                        dev.obiente.nextcloudnative.app.SyncEntryKind.File,
                        "published-etag",
                        4,
                    ),
                )
                remote.completePublishedFile(uploadId, "archive.bin")

                assertEquals("published-etag", verified.etag)
                val cleanup = server.request(3)
                assertEquals("DELETE", cleanup.method)
                assertTrue(cleanup.path.contains(".nextcloud-native-backup-$uploadId"))
            } finally {
                source.delete()
            }
        }

    @Test
    fun largeDavUploadsSkipOnlyTheOptionalPrecomputedChecksumPass() {
        assertTrue(shouldPrecomputeDavChecksum(byteCount = 64L * 1024L * 1024L))
        assertFalse(shouldPrecomputeDavChecksum(byteCount = 12L * 1024L * 1024L * 1024L))
    }

    @Test
    fun readStreamsContentWithMetadataAndEncodedPath() = RecordingServer().use { server ->
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/plain; charset=utf-8")
                .addHeader("ETag", "\"read-1\"")
                .body("hello from cloud")
                .build(),
        )
        val destination = ByteArrayOutputStream()

        val result = NextcloudDocumentWebDav().readFile(
            server.session,
            "alice",
            "Documents/July + August/report 1.txt",
            destination,
            maximumBytes = 1024,
            expectedEtag = "\"read-1\"",
        )

        assertEquals("hello from cloud", destination.toByteArray().toString(Charsets.UTF_8))
        assertEquals(16L, result.byteCount)
        assertEquals("text/plain; charset=utf-8", result.contentType)
        assertEquals("\"read-1\"", result.etag)
        assertEquals(
            "/remote.php/dav/files/alice/Documents/July%20%2B%20August/report%201.txt",
            server.request(0).path,
        )
        assertEquals("\"read-1\"", server.request(0).header("If-Match"))
    }

    @Test
    fun readRejectsDeclaredAndChunkedResponsesAboveLimit() = RecordingServer().use { server ->
        server.enqueue(MockResponse.Builder().code(200).body("123456").build())
        server.enqueue(MockResponse.Builder().code(200).chunkedBody("123456", 2).build())
        val client = NextcloudDocumentWebDav()

        repeat(2) { responseIndex ->
            val output = ByteArrayOutputStream()
            val failure = assertFailsWith<DocumentWebDavException> {
                client.readFile(server.session, "alice", "large.bin", output, maximumBytes = 5)
            }
            assertEquals(DocumentWebDavError.TooLarge, failure.error)
            if (responseIndex == 0) {
                // A declared oversize response is rejected before any bytes are consumed.
                assertEquals(0, output.size())
            } else {
                // Unknown-length streams may deliver an earlier chunk, but never beyond the cap.
                assertTrue(output.size() <= 5)
            }
        }
    }

    @Test
    fun contentIdentityRangeIsEtagPinnedAndStrictlyBounded() = RecordingServer().use { server ->
        server.enqueue(
            206,
            mapOf("ETag" to "\"large-1\"", "Content-Range" to "bytes 2-4/8"),
            body = "cde",
        )

        val hash = NextcloudDocumentWebDav().readFileRangeHash(
            session = server.session,
            userId = "alice",
            path = "large.bin",
            expectedEtag = "\"large-1\"",
            expectedBytes = 8L,
            offset = 2L,
            length = 3,
        )

        assertEquals(hashExactJvmFileSyncSlice(ByteArrayInputStream("cde".encodeToByteArray()), 3), hash)
        assertEquals("bytes=2-4", server.request(0).header("Range"))
        assertEquals("\"large-1\"", server.request(0).header("If-Match"))
    }

    @Test
    fun remoteSyncContentIdentityReadsAndHashesTheExactEtagGeneration() = RecordingServer().use { server ->
        server.enqueue(200, mapOf("ETag" to "\"note-7\""), body = "same note")
        server.enqueue(200, mapOf("ETag" to "\"note-7\""), body = "else note")
        val remote = AndroidFileSyncRemoteTree(
            server.session,
            "alice",
            "Vault",
            NextcloudDocumentWebDav(),
        )
        val expected = "sha256:8b4c848f9c906b8b340c2400c9aa8fdc1c9d5db557bad1b6aabdd9aabe3eb6e9"

        assertTrue(
            remote.verifyContentHash(
                "Notes/today.md", "\"note-7\"", expected, expectedBytes = 9L, maximumBytes = 1_024L,
            ),
        )
        assertTrue(
            !remote.verifyContentHash(
                "Notes/today.md", "\"note-7\"", expected, expectedBytes = 9L, maximumBytes = 1_024L,
            ),
        )
        repeat(2) { index ->
            val request = server.request(index)
            assertEquals("GET", request.method)
            assertEquals("/remote.php/dav/files/alice/Vault/Notes/today.md", request.path)
            assertEquals("\"note-7\"", request.header("If-Match"))
        }
    }

    @Test
    fun remoteSyncContentIdentityRejectsAChangedResponseGeneration() = RecordingServer().use { server ->
        server.enqueue(200, mapOf("ETag" to "\"note-8\""), body = "same note")
        val remote = AndroidFileSyncRemoteTree(
            server.session,
            "alice",
            "Vault",
            NextcloudDocumentWebDav(),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            remote.verifyContentHash(
                "Notes/today.md",
                "\"note-7\"",
                "sha256:8b4c848f9c906b8b340c2400c9aa8fdc1c9d5db557bad1b6aabdd9aabe3eb6e9",
                expectedBytes = 9L,
                maximumBytes = 1_024L,
            )
        }

        assertTrue(failure.message.orEmpty().contains("changed during content verification"))
        assertEquals("\"note-7\"", server.request(0).header("If-Match"))
    }

    @Test
    fun cancellationAbortsInflightHttpReadAndDetachesCallback() = RecordingServer().use { server ->
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("eventually")
                .bodyDelay(30, TimeUnit.SECONDS)
                .build(),
        )
        val cancellation = TestCancellation()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Unit> {
                NextcloudDocumentWebDav().readFile(
                    server.session,
                    "alice",
                    "slow.txt",
                    ByteArrayOutputStream(),
                    maximumBytes = 1024,
                    cancellation = cancellation,
                )
            }
            assertTrue(cancellation.attached.await(2, TimeUnit.SECONDS))
            cancellation.cancel()
            val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                future.get(2, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is TestCancelledException)
            assertTrue(cancellation.detached.await(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun createFolderUsesMkcolWithoutOverwrite() = RecordingServer().use { server ->
        server.enqueue(201)
        NextcloudDocumentWebDav().createFolder(server.session, "alice", "Documents/New folder")
        val request = server.request(0)
        assertEquals("MKCOL", request.method)
        assertEquals("/remote.php/dav/files/alice/Documents/New%20folder", request.path)
        assertEquals("*", request.header("If-None-Match"))
    }

    @Test
    fun readOnlyGateBlocksMutationBeforeAnyNetworkRequest() = RecordingServer().use { server ->
        val client = NextcloudDocumentWebDav(cloudMutationsAllowed = { false })

        val failure = assertFailsWith<IllegalStateException> {
            client.createFolder(server.session, "alice", "Documents/New folder")
        }

        assertTrue(failure.message.orEmpty().contains("read-only"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun createFileUsesCreateOnlyConditionalPut() = RecordingServer().use { server ->
        server.enqueue(201, mapOf("ETag" to "\"created-1\""))
        val source = Files.createTempFile("ncn-create-", ".txt").toFile()
        try {
            source.writeText("hello")
            var requestStarted = false
            val result = NextcloudDocumentWebDav().createFile(
                server.session,
                "alice",
                "Documents/report.txt",
                source,
                onRequestStarted = { requestStarted = true },
            )
            assertTrue(requestStarted)
            assertEquals("\"created-1\"", result.etag)
            val request = server.request(0)
            assertEquals("PUT", request.method)
            assertEquals("/remote.php/dav/files/alice/Documents/report.txt", request.path)
            assertEquals("*", request.header("If-None-Match"))
            assertEquals(
                "SHA256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                request.header("OC-Checksum"),
            )
            assertEquals("hello", request.body?.utf8())
            assertTrue(request.header("Authorization").orEmpty().startsWith("Basic "))
        } finally {
            source.delete()
        }
    }

    @Test
    fun createFileRejectsSuccessfulStatusThatDoesNotConfirmCreation() = RecordingServer().use { server ->
        server.enqueue(204)
        val source = Files.createTempFile("ncn-create-unconfirmed-", ".txt").toFile()
        try {
            source.writeText("preserve me")

            val failure = assertFailsWith<DocumentWebDavException> {
                NextcloudDocumentWebDav().createFile(
                    server.session,
                    "alice",
                    "Documents/report.txt",
                    source,
                )
            }

            assertEquals(DocumentWebDavError.Server, failure.error)
            assertEquals(204, failure.status)
            assertTrue(failure.message.orEmpty().contains("did not confirm"))
        } finally {
            source.delete()
        }
    }

    @Test
    fun createFileClassifiesPayloadTooLargeAsDefiniteRejection() = RecordingServer().use { server ->
        server.enqueue(413)
        val source = Files.createTempFile("ncn-create-too-large-", ".txt").toFile()
        try {
            source.writeText("too large according to server policy")

            val failure = assertFailsWith<DocumentWebDavException> {
                NextcloudDocumentWebDav().createFile(
                    server.session,
                    "alice",
                    "Documents/report.txt",
                    source,
                )
            }

            assertEquals(DocumentWebDavError.TooLarge, failure.error)
            assertEquals(413, failure.status)
        } finally {
            source.delete()
        }
    }

    @Test
    fun createFileDoesNotMarkRequestStartedWhenMutationGateRejectsPreflight() = RecordingServer().use { server ->
        val source = Files.createTempFile("ncn-create-gated-", ".txt").toFile()
        try {
            source.writeText("hello")
            var requestStarted = false
            assertFailsWith<IllegalStateException> {
                NextcloudDocumentWebDav(cloudMutationsAllowed = { false }).createFile(
                    server.session,
                    "alice",
                    "Documents/report.txt",
                    source,
                    onRequestStarted = { requestStarted = true },
                )
            }
            assertTrue(!requestStarted)
            assertEquals(0, server.requestCount)
        } finally {
            source.delete()
        }
    }

    @Test
    fun connectionFailureDoesNotMarkAConditionalPutAsServerVisible() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val source = Files.createTempFile("ncn-connect-failure-", ".txt").toFile()
        try {
            source.writeText("not sent")
            var requestStarted = false
            val client = NextcloudDocumentWebDav(
                OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
            )

            assertFailsWith<java.io.IOException> {
                client.createFile(
                    NextcloudSession("http://127.0.0.1:$unusedPort", "alice", "app-password"),
                    "alice",
                    "Documents/report.txt",
                    source,
                    onRequestStarted = { requestStarted = true },
                )
            }
            assertFalse(requestStarted)
        } finally {
            source.delete()
        }
    }

    @Test
    fun createFileCancellationAbortsTheInflightPut() = RecordingServer().use { server ->
        server.enqueue(MockResponse.Builder().code(201).headersDelay(30, TimeUnit.SECONDS).build())
        val source = Files.createTempFile("ncn-cancel-put-", ".txt").toFile()
        val cancellation = TestCancellation()
        val executor = Executors.newSingleThreadExecutor()
        try {
            source.writeText("cancel me")
            val future = executor.submit<Unit> {
                NextcloudDocumentWebDav().createFile(
                    server.session,
                    "alice",
                    "Documents/cancel.txt",
                    source,
                    cancellation = cancellation,
                )
            }
            assertTrue(cancellation.attached.await(2, TimeUnit.SECONDS))
            cancellation.cancel()
            val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                future.get(2, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is TestCancelledException)
            assertTrue(cancellation.detached.await(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            source.delete()
        }
    }

    @Test
    fun chunkedUploadUsesOfficialV2HeadersAndNeverOverwritesDestination() = RecordingServer().use { server ->
        server.enqueue(201)
        server.enqueue(201)
        server.enqueue(201)
        val source = Files.createTempFile("ncn-chunk-", ".bin").toFile()
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        try {
            source.writeText("0123456789")
            var commitReadTimeoutMillis: Int? = null
            val httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    if (chain.request().method == "MOVE") commitReadTimeoutMillis = chain.readTimeoutMillis()
                    chain.proceed(chain.request())
                }
                .build()
            val client = NextcloudDocumentWebDav(httpClient)
            assertTrue(
                client.createChunkUpload(
                    server.session,
                    "alice",
                    uploadId,
                    "Shared/archive.bin",
                    allowExistingSession = false,
                    cancellation = TestCancellation(),
                ),
            )
            client.uploadChunk(
                server.session,
                "alice",
                uploadId,
                "Shared/archive.bin",
                source,
                offset = 2,
                length = 5,
                totalLength = 10,
                chunkNumber = 1,
                cancellation = TestCancellation(),
            )
            client.commitChunkUpload(
                server.session,
                "alice",
                uploadId,
                "Shared/archive.bin",
                totalLength = 10,
                cancellation = TestCancellation(),
                onRequestStarted = {},
            )

            val destination = server.baseUrl + "/remote.php/dav/files/alice/Shared/archive.bin"
            assertEquals("MKCOL", server.request(0).method)
            assertEquals(destination, server.request(0).header("Destination"))
            assertEquals("PUT", server.request(1).method)
            assertEquals("23456", server.request(1).body?.utf8())
            assertEquals("10", server.request(1).header("OC-Total-Length"))
            assertTrue(server.request(1).path.endsWith("/$uploadId/00001"))
            assertEquals("MOVE", server.request(2).method)
            assertEquals("F", server.request(2).header("Overwrite"))
            assertEquals(destination, server.request(2).header("Destination"))
            assertTrue(server.request(2).path.endsWith("/$uploadId/.file"))
            assertEquals(30 * 60 * 1_000, commitReadTimeoutMillis)
        } finally {
            source.delete()
        }
    }

    @Test
    fun resumableUploadReadsTheAuthoritativeServerChunkPrefix() = RecordingServer().use { server ->
        server.enqueue(
            MockResponse.Builder().code(207).body(
                """
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/remote.php/dav/uploads/alice/upload/</d:href></d:response>
                  <d:response><d:href>/remote.php/dav/uploads/alice/upload/00001</d:href>
                    <d:propstat><d:prop><d:getcontentlength>10485760</d:getcontentlength></d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent(),
            ).build(),
        )

        val chunks = NextcloudDocumentWebDav().listChunkUpload(
            server.session,
            "alice",
            "01234567-89ab-cdef-0123-456789abcdef",
            TestCancellation(),
        )

        assertEquals(mapOf(1 to 10L * 1024L * 1024L), chunks)
        assertEquals("PROPFIND", server.request(0).method)
        assertEquals("1", server.request(0).header("Depth"))
    }

    @Test
    fun resumableUploadStreamsVerboseChunkMetadataPastTheDirectoryReadBudget() = RecordingServer().use { server ->
        val xml = buildString {
            append("<d:multistatus xmlns:d=\"DAV:\"><!--")
            append("x".repeat(5 * 1024 * 1024))
            append("--><d:response><d:href>/remote.php/dav/uploads/alice/upload/00001</d:href>")
            append("<d:propstat><d:prop><d:getcontentlength>1</d:getcontentlength>")
            append("</d:prop></d:propstat></d:response></d:multistatus>")
        }
        server.enqueue(MockResponse.Builder().code(207).body(xml).build())

        val chunks = NextcloudDocumentWebDav().listChunkUpload(
            server.session,
            "alice",
            "01234567-89ab-cdef-0123-456789abcdef",
            TestCancellation(),
        )

        assertEquals(mapOf(1 to 1L), chunks)
    }

    @Test
    fun chunkCommitRejectsSuccessfulStatusThatDoesNotConfirmCreation() = RecordingServer().use { server ->
        server.enqueue(204)

        val failure = assertFailsWith<DocumentWebDavException> {
            NextcloudDocumentWebDav().commitChunkUpload(
                server.session,
                "alice",
                "01234567-89ab-cdef-0123-456789abcdef",
                "Shared/archive.bin",
                totalLength = 10,
                cancellation = TestCancellation(),
                onRequestStarted = {},
            )
        }

        assertEquals(DocumentWebDavError.Server, failure.error)
        assertEquals(204, failure.status)
        assertTrue(failure.message.orEmpty().contains("did not confirm"))
        assertEquals("F", server.request(0).header("Overwrite"))
    }

    @Test
    fun assembledChunkStageUsesSourceAndDestinationGenerationGuards() = RecordingServer().use { server ->
        server.enqueue(204)
        val destination = server.baseUrl + "/remote.php/dav/files/alice/Shared/archive.bin"

        NextcloudDocumentWebDav().publishChunkUploadStage(
            server.session,
            "alice",
            "Shared/.nextcloud-native-01234567-89ab-cdef-0123-456789abcdef.upload",
            "Shared/archive.bin",
            stagedEtag = "stage-etag",
            expectedRemoteEtag = "old-etag",
        )

        val request = server.request(0)
        assertEquals("MOVE", request.method)
        assertEquals("stage-etag", request.header("If-Match"))
        assertEquals("T", request.header("Overwrite"))
        assertEquals(destination, request.header("Destination"))
        assertEquals("<$destination> ([old-etag])", request.header("If"))
    }

    @Test
    fun oversizedRootNameListingFallsBackToConditionalCreates() = RecordingServer().use { server ->
        server.enqueue(207, body = " ".repeat(4 * 1024 * 1024 + 1))
        val remote = AndroidFileSyncRemoteTree(
            server.session,
            "alice",
            "Shared",
            NextcloudDocumentWebDav(),
        )

        val snapshot = remote.rootChildNames()

        assertEquals(emptySet(), snapshot.names)
        assertFalse(snapshot.complete)
    }

    @Test
    fun exactResourceProbeAvoidsEnumeratingAnIncompleteParent() = RecordingServer().use { server ->
        server.enqueue(404)
        server.enqueue(207, body = "<d:multistatus xmlns:d=\"DAV:\"/>")
        val client = NextcloudDocumentWebDav()

        assertFalse(client.resourceExists(server.session, "alice", "Shared/archive.bin"))
        assertTrue(client.resourceExists(server.session, "alice", "Shared/archive (1).bin"))
        repeat(2) { index ->
            assertEquals("PROPFIND", server.request(index).method)
            assertEquals("0", server.request(index).header("Depth"))
        }
    }

    @Test
    fun retryAfterAcceptsSecondsAndHttpDatesWithinOneDay() {
        assertEquals(17L, parseDocumentRetryAfterSeconds("17", nowEpochMillis = 0L))
        assertEquals(
            120L,
            parseDocumentRetryAfterSeconds("Thu, 1 Jan 1970 00:02:00 GMT", nowEpochMillis = 0L),
        )
        assertEquals(
            86_400L,
            parseDocumentRetryAfterSeconds("Sat, 3 Jan 1970 00:00:00 GMT", nowEpochMillis = 0L),
        )
        assertEquals(null, parseDocumentRetryAfterSeconds("not-a-delay", nowEpochMillis = 0L))
    }

    @Test
    fun chunkCollectionDistinguishesUnsupportedFreshExistingAndMissingParent() = RecordingServer().use { server ->
        server.enqueue(405)
        server.enqueue(405)
        server.enqueue(409)
        val client = NextcloudDocumentWebDav()

        val unsupported = assertFailsWith<DocumentWebDavException> {
            client.createChunkUpload(
                server.session,
                "alice",
                "01234567-89ab-cdef-0123-456789abcdef",
                "Shared/archive.bin",
                allowExistingSession = false,
                cancellation = TestCancellation(),
            )
        }
        assertEquals(405, unsupported.status)
        assertFalse(
            client.createChunkUpload(
                server.session,
                "alice",
                "11234567-89ab-cdef-0123-456789abcdef",
                "Shared/archive.bin",
                allowExistingSession = true,
                cancellation = TestCancellation(),
            ),
        )
        val failure = assertFailsWith<DocumentWebDavException> {
            client.createChunkUpload(
                server.session,
                "alice",
                "21234567-89ab-cdef-0123-456789abcdef",
                "Missing/archive.bin",
                allowExistingSession = false,
                cancellation = TestCancellation(),
            )
        }
        assertEquals(409, failure.status)
    }

    @Test
    fun directoryCreatePermissionAndRateLimitAreTyped() = RecordingServer().use { server ->
        server.enqueue(
            207,
            body = """
                <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response>
                  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>
                    <oc:permissions>RGDNVCK</oc:permissions>
                  </d:prop></d:propstat>
                </d:response></d:multistatus>
            """.trimIndent(),
        )
        server.enqueue(429, headers = mapOf("Retry-After" to "17"))
        val client = NextcloudDocumentWebDav()

        val access = client.inspectDirectoryAccess(server.session, "alice", "Shared")
        assertTrue(access.canCreateFiles)
        assertTrue(access.canCreateDirectories)
        assertEquals("0", server.request(0).header("Depth"))
        val failure = assertFailsWith<DocumentWebDavException> {
            client.createFolder(server.session, "alice", "Shared/New")
        }
        assertEquals(DocumentWebDavError.Throttled, failure.error)
        assertEquals(17L, failure.retryAfterSeconds)
    }

    @Test
    fun replacementUsesDestinationGuardedPut() = RecordingServer().use { server ->
        server.enqueue(204, mapOf("ETag" to "\"saved-2\""))
        val source = Files.createTempFile("ncn-replace-put-", ".txt").toFile()
        try {
            source.writeText("edited")
            val result = NextcloudDocumentWebDav().replaceFile(
                server.session,
                "alice",
                "Documents/report.txt",
                source,
                "\"old-1\"",
            )

            assertEquals("\"saved-2\"", result.etag)
            assertEquals(1, server.requestCount)
            val request = server.request(0)
            assertEquals("PUT", request.method)
            assertEquals("/remote.php/dav/files/alice/Documents/report.txt", request.path)
            assertEquals("\"old-1\"", request.header("If-Match"))
            assertEquals(null, request.header("If-None-Match"))
            assertEquals(
                "SHA256:1fb9f4097256db2d7b1e13aff79cee44339891a31c556b9cf6093885773b3618",
                request.header("OC-Checksum"),
            )
            assertEquals("edited", request.body?.utf8())
        } finally {
            source.delete()
        }
    }

    @Test
    fun replacementStagesThenConditionallyMovesOverExpectedEtag() = RecordingServer().use { server ->
        server.enqueue(201, mapOf("ETag" to "\"staged-1\""))
        server.enqueue(201, mapOf("ETag" to "\"saved-2\""))
        val source = Files.createTempFile("ncn-replace-", ".txt").toFile()
        try {
            source.writeText("edited")
            val result = NextcloudDocumentWebDav().replaceFileAtomically(
                server.session,
                "alice",
                "Documents/report.txt",
                source,
                "\"old-1\"",
            )
            assertEquals("\"saved-2\"", result.etag)
            val upload = server.request(0)
            val move = server.request(1)
            assertEquals("PUT", upload.method)
            assertTrue(upload.path.startsWith("/remote.php/dav/files/alice/Documents/.nextcloud-native-"))
            assertTrue(upload.path.endsWith(".upload"))
            assertEquals("*", upload.header("If-None-Match"))
            assertEquals("MOVE", move.method)
            assertEquals(upload.path, move.path)
            assertEquals("T", move.header("Overwrite"))
            assertEquals("\"staged-1\"", move.header("If-Match"))
            val destination = server.baseUrl + "/remote.php/dav/files/alice/Documents/report.txt"
            assertEquals(destination, move.header("Destination"))
            assertEquals("<$destination> ([\"old-1\"])", move.header("If"))
        } finally {
            source.delete()
        }
    }

    @Test
    fun replacementConflictCleansRemoteStageAndMapsConflict() = RecordingServer().use { server ->
        server.enqueue(201, mapOf("ETag" to "\"staged-2\""))
        server.enqueue(412)
        server.enqueue(204)
        val source = Files.createTempFile("ncn-conflict-", ".txt").toFile()
        try {
            source.writeText("local edit")
            val failure = assertFailsWith<DocumentWebDavException> {
                NextcloudDocumentWebDav().replaceFileAtomically(
                    server.session,
                    "alice",
                    "report.txt",
                    source,
                    "\"remote-newer\"",
                )
            }
            assertEquals(DocumentWebDavError.Conflict, failure.error)
            val cleanup = server.request(2)
            assertEquals("DELETE", cleanup.method)
            assertEquals(server.request(0).path, cleanup.path)
            assertEquals("\"staged-2\"", cleanup.header("If-Match"))
        } finally {
            source.delete()
        }
    }

    @Test
    fun moveNeverOverwritesAndDeleteRequiresMatchingEtag() = RecordingServer().use { server ->
        server.enqueue(201)
        server.enqueue(204)
        val client = NextcloudDocumentWebDav()
        client.move(server.session, "alice", "a.txt", "Archive/a.txt", "\"move-me\"")
        client.delete(server.session, "alice", "Archive/a.txt", "\"delete-me\"")

        val move = server.request(0)
        assertEquals("MOVE", move.method)
        assertEquals("F", move.header("Overwrite"))
        assertEquals("\"move-me\"", move.header("If-Match"))
        assertNotNull(move.header("Destination"))
        val delete = server.request(1)
        assertEquals("DELETE", delete.method)
        assertEquals("\"delete-me\"", delete.header("If-Match"))
    }

    @Test
    fun collectionDeleteUsesTaggedWebDavCondition() = RecordingServer().use { server ->
        server.enqueue(204)
        val client = NextcloudDocumentWebDav()

        client.delete(
            server.session,
            "alice",
            "Archive",
            "\"collection-1\"",
            isDirectory = true,
        )

        val delete = server.request(0)
        val resource = server.baseUrl + "/remote.php/dav/files/alice/Archive"
        assertEquals("DELETE", delete.method)
        assertEquals("<$resource> ([\"collection-1\"])", delete.header("If"))
        assertEquals(null, delete.header("If-Match"))
    }

    @Test
    fun searchUsesBoundedBasicSearchAndParsesFilesWithoutDownloadingContent() = RecordingServer().use { server ->
        server.enqueue(
            MockResponse.Builder()
                .code(207)
                .addHeader("Content-Type", "application/xml")
                .body(
                    """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
                          <d:response>
                            <d:href>/remote.php/dav/files/alice/Documents/Project%20notes.md</d:href>
                            <d:propstat><d:prop>
                              <d:displayname>Project notes.md</d:displayname>
                              <d:getcontenttype>text/markdown</d:getcontenttype>
                              <d:getcontentlength>42</d:getcontentlength>
                              <d:getetag>&quot;search-1&quot;</d:getetag>
                              <oc:checksums>
                                <oc:checksum>SHA1:ignored-for-convergence</oc:checksum>
                                <oc:checksum>SHA256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef</oc:checksum>
                              </oc:checksums>
                              <oc:fileid>91</oc:fileid><nc:has-preview>true</nc:has-preview>
                            </d:prop></d:propstat>
                          </d:response>
                        </d:multistatus>
                    """.trimIndent(),
                )
                .build(),
        )

        val result = NextcloudDocumentWebDav().searchFiles(
            server.session,
            "alice",
            "Project & <notes>",
            maximumResults = 20,
        )

        assertEquals(1, result.files.size)
        assertEquals("Documents/Project notes.md", result.files.single().path)
        assertEquals(91L, result.files.single().fileId)
        assertEquals("\"search-1\"", result.files.single().etag)
        assertEquals(
            listOf(
                "SHA1:ignored-for-convergence",
                "SHA256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ),
            result.files.single().checksums,
        )
        assertEquals("SEARCH", server.request(0).method)
        val body = server.request(0).body?.utf8().orEmpty()
        assertTrue("<d:nresults>21</d:nresults>" in body)
        assertTrue("%Project &amp; &lt;notes&gt;%" in body)
        assertTrue("Project & <notes>" !in body)
    }

    @Test
    fun directoryListingUsesDepthOneAndKeepsOnlyImmediateChildren() = RecordingServer().use { server ->
        server.enqueue(
            MockResponse.Builder()
                .code(207)
                .addHeader("Content-Type", "application/xml")
                .body(
                    """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
                          <d:response><d:href>/remote.php/dav/files/alice/Vault/</d:href><d:propstat><d:prop>
                            <d:displayname>Vault</d:displayname><d:resourcetype><d:collection/></d:resourcetype>
                          </d:prop></d:propstat></d:response>
                          <d:response><d:href>/remote.php/dav/files/alice/Vault/Notes/</d:href><d:propstat><d:prop>
                            <d:displayname>Notes</d:displayname><d:resourcetype><d:collection/></d:resourcetype>
                            <d:getetag>&quot;notes&quot;</d:getetag>
                          </d:prop></d:propstat></d:response>
                          <d:response><d:href>/remote.php/dav/files/alice/Vault/readme.md</d:href><d:propstat><d:prop>
                            <d:displayname>readme.md</d:displayname><d:getcontenttype>text/markdown</d:getcontenttype>
                            <d:getcontentlength>12</d:getcontentlength><d:getetag>&quot;readme&quot;</d:getetag>
                            <oc:checksums><oc:checksum>SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</oc:checksum></oc:checksums>
                          </d:prop></d:propstat></d:response>
                          <d:response><d:href>/remote.php/dav/files/alice/Elsewhere/private.md</d:href><d:propstat><d:prop>
                            <d:displayname>private.md</d:displayname><d:getcontentlength>4</d:getcontentlength>
                          </d:prop></d:propstat></d:response>
                        </d:multistatus>
                    """.trimIndent(),
                )
                .build(),
        )

        val result = NextcloudDocumentWebDav().listDirectory(server.session, "alice", "Vault")

        assertEquals(listOf("Vault/Notes", "Vault/readme.md"), result.files.map { it.path })
        assertEquals(
            listOf("SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            result.files.last().checksums,
        )
        assertTrue(!result.limited)
        val request = server.request(0)
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.header("Depth"))
        assertTrue(request.body?.utf8().orEmpty().contains("getetag"))
        assertTrue(request.body?.utf8().orEmpty().contains("oc:checksums"))
    }

    @Test
    fun searchValidationAndParserRejectTraversalShapedResults() {
        assertFailsWith<IllegalArgumentException> {
            normalizeDocumentSearchQuery(" \u0000 ")
        }
        val parsed = parseDocumentSearchResponse(
            """
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/remote.php/dav/files/alice/../secret</d:href>
                    <d:propstat><d:prop><d:displayname>secret</d:displayname></d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
            """.trimIndent().encodeToByteArray(),
            userId = "alice",
            maximumResults = 20,
        )
        assertTrue(parsed.isEmpty())
    }

    private fun directoryListing(): String =
        """
        <d:multistatus xmlns:d="DAV:"><d:response>
          <d:href>/remote.php/dav/files/alice/Vault/archive.bin/</d:href>
          <d:propstat><d:prop><d:displayname>archive.bin</d:displayname>
            <d:getetag>directory-etag</d:getetag><d:resourcetype><d:collection/></d:resourcetype>
          </d:prop></d:propstat>
        </d:response></d:multistatus>
        """.trimIndent()

    private fun replacementListing(
        uploadId: String,
        includePublishedFile: Boolean,
        publishedBytes: Long = 22_020_096L,
    ): String =
        """
        <d:multistatus xmlns:d="DAV:">
          ${if (includePublishedFile) """
          <d:response><d:href>/remote.php/dav/files/alice/Vault/archive.bin</d:href>
            <d:propstat><d:prop><d:displayname>archive.bin</d:displayname>
              <d:getetag>published-etag</d:getetag><d:getcontentlength>$publishedBytes</d:getcontentlength>
              <d:resourcetype/>
            </d:prop></d:propstat>
          </d:response>
          """.trimIndent() else ""}
          <d:response>
            <d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-backup-$uploadId/</d:href>
            <d:propstat><d:prop>
              <d:displayname>.nextcloud-native-backup-$uploadId</d:displayname>
              <d:getetag>directory-etag</d:getetag><d:resourcetype><d:collection/></d:resourcetype>
            </d:prop></d:propstat>
          </d:response>
        </d:multistatus>
        """.trimIndent()

    private fun protectedDirectoryListing(uploadId: String): String =
        """
        <d:multistatus xmlns:d="DAV:"><d:response>
          <d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-backup-$uploadId/inside.txt</d:href>
          <d:propstat><d:prop><d:displayname>inside.txt</d:displayname>
            <d:getetag>inside-etag</d:getetag><d:getcontentlength>4</d:getcontentlength><d:resourcetype/>
          </d:prop></d:propstat>
        </d:response></d:multistatus>
        """.trimIndent()

    private class RecordingServer : AutoCloseable {
        private val server = MockWebServer()
        private val requests = mutableListOf<RecordedRequest>()
        init {
            server.start()
        }
        val baseUrl: String = server.url("/").toString().trimEnd('/')
        val session = NextcloudSession(baseUrl, "alice", "app-password")

        fun enqueue(
            status: Int,
            headers: Map<String, String> = emptyMap(),
            body: String? = null,
        ) {
            val flattenedHeaders = headers.flatMap { (name, value) -> listOf(name, value) }.toTypedArray()
            server.enqueue(
                MockResponse.Builder()
                    .code(status)
                    .headers(headersOf(*flattenedHeaders))
                    .apply { body?.let { content -> body(content) } }
                    .build(),
            )
        }

        fun enqueue(response: MockResponse) = server.enqueue(response)

        val requestCount: Int
            get() = server.requestCount

        fun request(index: Int): RecordedRequest {
            while (requests.size <= index) {
                requests += requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "Request $index was not received." }
            }
            return requests[index]
        }

        override fun close() = server.close()
    }

    private val RecordedRequest.path: String get() = url.encodedPath
    private fun RecordedRequest.header(name: String): String? = headers[name]
    private class TestCancellation : DocumentRequestCancellation {
        val attached = CountDownLatch(1)
        val detached = CountDownLatch(1)
        @Volatile private var cancelled = false
        @Volatile private var cancelAction: (() -> Unit)? = null

        fun cancel() {
            cancelled = true
            cancelAction?.invoke()
        }

        override fun throwIfCancelled() {
            if (cancelled) throw TestCancelledException()
        }

        override fun setOnCancelAction(action: (() -> Unit)?) {
            cancelAction = action
            if (action == null) detached.countDown() else attached.countDown()
            if (cancelled) action?.invoke()
        }
    }

    private class TestCancelledException : RuntimeException()
}
