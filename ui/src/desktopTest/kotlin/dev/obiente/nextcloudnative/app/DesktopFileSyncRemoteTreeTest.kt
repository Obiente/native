package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class DesktopFileSyncRemoteTreeTest {
    @Test
    fun `cleanup verifies an etagless direct replacement stage before deleting it`() {
        val requests = mutableListOf<Request>()
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        val stageName = ".nextcloud-native-$uploadId.upload"
        val payload = "owned-stage".encodeToByteArray()
        val expectedHash = hashExactJvmFileSyncContent(payload.inputStream(), payload.size.toLong())
        var listingCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            when (chain.request().method) {
                "DELETE" -> response(chain.request(), if (requests.size == 1) 404 else 204)
                "GET" -> binaryResponse(chain.request(), 200, payload)
                "PROPFIND" -> {
                    listingCount += 1
                    response(
                        chain.request(),
                        207,
                        if (listingCount <= 2) davFile(stageName, "recovered-stage-etag", payload.size.toLong())
                        else "<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>",
                    )
                }
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            NextcloudSession("https://cloud.example.test", "alice", "secret"),
            "alice",
            "Vault",
            client,
            ownedUploadIds = setOf(uploadId),
            ownedUploadPaths = mapOf(uploadId to "archive.bin"),
        )

        val completed = tree.resumableUploadRemote().discardOwnedUpload(
            uploadId,
            "archive.bin",
            assembledStageEtag = null,
            expectedStageSizeBytes = payload.size.toLong(),
            expectedStageContentHash = expectedHash,
        )

        assertTrue(completed)
        assertEquals(
            listOf("DELETE", "PROPFIND", "GET", "PROPFIND", "DELETE", "PROPFIND"),
            requests.map { it.method },
        )
        assertEquals("recovered-stage-etag", requests[4].header("If-Match"))
    }

    @Test
    fun `direct replacement persists stage and backup identity before upload`() {
        val retained = mutableListOf<FileSyncPendingUploadCleanup>()
        val payload = "new file".encodeToByteArray()
        val expectedHash = hashExactJvmFileSyncContent(payload.inputStream(), payload.size.toLong())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            when (chain.request().method) {
                "PROPFIND" -> response(chain.request(), 207, davDirectory("archive.bin", "directory-etag"))
                "PUT" -> throw IOException("response lost")
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            NextcloudSession("https://cloud.example.test", "alice", "secret"),
            "alice",
            "Vault",
            client,
        )
        val source = Files.createTempFile("nextcloud-sync-direct-replacement", ".tmp").toFile()
        try {
            source.writeBytes(payload)

            assertFails {
                replaceDesktopFileSyncRemoteType(
                    source,
                    "archive.bin",
                    "directory-etag",
                    tree,
                    retained::add,
                    completeCleanup = {},
                    shouldContinue = { true },
                )
            }

            val ownership = retained.single()
            assertEquals("archive.bin", ownership.relativePath)
            assertEquals("directory-etag", ownership.replacementBackupEtag)
            assertEquals(payload.size.toLong(), ownership.expectedStageSizeBytes)
            assertEquals(expectedHash, ownership.expectedStageContentHash)
            assertEquals(null, ownership.assembledStageEtag)
        } finally {
            assertTrue(source.delete())
        }
    }

    @Test
    fun `cleanup conditionally deletes a reconciled replacement stage`() {
        val requests = mutableListOf<Request>()
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        val stageName = ".nextcloud-native-$uploadId.upload"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            when (chain.request().method) {
                "DELETE" -> response(chain.request(), if (requests.size == 2) 404 else 204)
                "PROPFIND" -> response(
                    chain.request(),
                    207,
                    """
                    <d:multistatus xmlns:d="DAV:"><d:response>
                      <d:href>/remote.php/dav/files/alice/Vault/$stageName</d:href>
                      <d:propstat><d:prop><d:getetag>unknown-stage</d:getetag>
                        <d:getcontentlength>7</d:getcontentlength><d:resourcetype/>
                      </d:prop></d:propstat>
                    </d:response></d:multistatus>
                    """.trimIndent(),
                )
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
            userId = "alice",
            remoteRootPath = "Vault",
            client = client,
            ownedUploadIds = setOf(uploadId),
        )

        val remote = tree.resumableUploadRemote()
        val discoveredEtag = remote.ownedStageEtag(uploadId, "large.bin")
        val completed = remote.discardOwnedUpload(uploadId, "large.bin", discoveredEtag)

        assertTrue(completed)
        assertEquals(listOf("PROPFIND", "DELETE", "DELETE", "PROPFIND"), requests.map { it.method })
        assertEquals("unknown-stage", requests[2].header("If-Match"))
    }

    @Test
    fun `cleanup deletes only the recorded stage generation and accepts its replacement`() {
        val requests = mutableListOf<Request>()
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            when (requests.size) {
                1 -> response(chain.request(), 404)
                2 -> response(chain.request(), 412)
                else -> response(chain.request(), 207, "<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
            userId = "alice",
            remoteRootPath = "Vault",
            client = client,
            ownedUploadIds = setOf(uploadId),
        )

        assertTrue(
            tree.resumableUploadRemote()
                .discardOwnedUpload(uploadId, "nested/large.bin", "owned-stage-etag"),
        )

        assertEquals(listOf("DELETE", "DELETE", "PROPFIND"), requests.map { it.method })
        assertTrue(requests[0].url.encodedPath.endsWith("/uploads/alice/$uploadId"))
        assertTrue(requests[1].url.encodedPath.endsWith("/Vault/nested/.nextcloud-native-$uploadId.upload"))
        assertEquals("owned-stage-etag", requests[1].header("If-Match"))
    }

    @Test
    fun `scan hides only upload stages durably owned by this sync pair`() {
        val ownedId = "01234567-89ab-cdef-0123-456789abcdef"
        val userId = "fedcba98-7654-3210-fedc-ba9876543210"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(
                chain.request(),
                207,
                """
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-$ownedId.upload</d:href>
                    <d:propstat><d:prop><d:getetag>owned-etag</d:getetag><d:getcontentlength>1</d:getcontentlength>
                      <d:resourcetype/></d:prop></d:propstat></d:response>
                  <d:response><d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-$userId.upload</d:href>
                    <d:propstat><d:prop><d:getetag>user-etag</d:getetag><d:getcontentlength>1</d:getcontentlength>
                      <d:resourcetype/></d:prop></d:propstat></d:response>
                </d:multistatus>
                """.trimIndent(),
            )
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
            userId = "alice",
            remoteRootPath = "Vault",
            client = client,
            ownedUploadIds = setOf(ownedId),
        )

        assertEquals(
            listOf(".nextcloud-native-$userId.upload"),
            tree.scan().map { it.entry.relativePath },
        )
    }

    @Test
    fun `chunked upload assembles an owned stage before one visible move`() {
        val requests = mutableListOf<Request>()
        var propfindCount = 0
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        val stageName = ".nextcloud-native-$uploadId.upload"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            when (chain.request().method) {
                "MKCOL", "PUT" -> response(chain.request(), 201)
                "MOVE" -> response(chain.request(), 201).newBuilder().header("ETag", "etag-1").build()
                "GET" -> binaryResponse(chain.request(), 200, ByteArray(21 * 1024 * 1024))
                "PROPFIND" -> {
                    propfindCount += 1
                    val name = if (propfindCount == 1) stageName else "large.bin"
                    response(
                        chain.request(),
                        207,
                        """
                        <d:multistatus xmlns:d="DAV:"><d:response>
                          <d:href>/remote.php/dav/files/alice/Vault/$name</d:href>
                          <d:propstat><d:prop><d:getetag>etag-$propfindCount</d:getetag>
                            <d:getcontentlength>22020096</d:getcontentlength><d:resourcetype/>
                          </d:prop></d:propstat>
                        </d:response></d:multistatus>
                        """.trimIndent(),
                    )
                }
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            NextcloudSession("https://cloud.example.test", "alice", "secret"),
            "alice",
            "Vault",
            client,
        )
        val source = Files.createTempFile("nextcloud-sync-chunked", ".tmp").toFile()
        RandomAccessFile(source, "rw").use { it.setLength(21L * 1024L * 1024L) }
        try {
            val checkpoints = mutableListOf<FileSyncUploadCheckpoint>()
            val result = jvmResumableNextcloudUpload(
                source, "large.bin", "local-1", null, null,
                newUploadId = { uploadId },
                persistCheckpoint = checkpoints::add,
                remote = tree.resumableUploadRemote(),
            )

            assertEquals("etag-2", result.etag)
            assertEquals(listOf("MKCOL", "PUT", "PUT", "PUT", "MOVE", "PROPFIND", "GET", "MOVE", "PROPFIND"),
                requests.map { it.method })
            assertTrue(requests[0].header("Destination")!!.endsWith("/Vault/$stageName"))
            assertTrue(requests[4].header("Destination")!!.endsWith("/Vault/$stageName"))
            assertTrue(requests[7].header("Destination")!!.endsWith("/Vault/large.bin"))
            assertEquals("F", requests[7].header("Overwrite"))
            assertTrue(checkpoints.last().commitInFlight)
            assertEquals("etag-1", checkpoints.last().assembledStageEtag)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `large directory replacement uploads resumable chunks before protected publication`() {
        val requests = mutableListOf<Request>()
        var propfindCount = 0
        var uploadId: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            when (chain.request().method) {
                "MKCOL" -> {
                    uploadId = chain.request().url.pathSegments.last()
                    response(chain.request(), 201)
                }
                "PUT" -> response(chain.request(), 201)
                "MOVE" -> response(chain.request(), 201).newBuilder().header("ETag", "stage-etag").build()
                "GET" -> binaryResponse(chain.request(), 200, ByteArray(21 * 1024 * 1024))
                "PROPFIND" -> {
                    propfindCount += 1
                    val body = when (propfindCount) {
                        1 -> davDirectory("archive.bin", "directory-etag")
                        2 -> davFile(
                            ".nextcloud-native-${requireNotNull(uploadId)}.upload",
                            "stage-etag",
                            21L * 1024L * 1024L,
                        )
                        3 -> davDirectory("archive.bin", "directory-etag")
                        4 -> davFile("archive.bin", "published-etag", 21L * 1024L * 1024L)
                        5 -> "<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>"
                        else -> error("Unexpected directory listing")
                    }
                    response(chain.request(), 207, body)
                }
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            NextcloudSession("https://cloud.example.test", "alice", "secret"),
            "alice",
            "Vault",
            client,
        )
        val source = Files.createTempFile("nextcloud-sync-type-replacement", ".tmp").toFile()
        RandomAccessFile(source, "rw").use { it.setLength(21L * 1024L * 1024L) }
        try {
            val checkpoints = mutableListOf<FileSyncUploadCheckpoint>()

            val result = executeDesktopFileSyncUpload(
                source = source,
                relativePath = "archive.bin",
                exactLocal = LocalSyncEntry("archive.bin", SyncEntryKind.File, "local-1", source.length()),
                expectedRemoteEtag = "directory-etag",
                checkpoint = null,
                replacingType = true,
                persistCheckpoint = checkpoints::add,
                retainCleanup = {},
                completeCleanup = {},
                remote = tree,
                shouldContinue = { true },
            )

            assertEquals("published-etag", result.etag)
            assertEquals(3, requests.count { it.method == "PUT" })
            assertTrue(
                requests.filter { it.method == "PUT" }
                    .all { requireNotNull(it.body).contentLength() <= 10L * 1024L * 1024L },
            )
            val moveDestinations = requests.filter { it.method == "MOVE" }.map { it.header("Destination") }
            assertTrue(
                moveDestinations.first()!!
                    .endsWith("/Vault/.nextcloud-native-${requireNotNull(uploadId)}.upload"),
            )
            assertTrue(
                moveDestinations[1]!!
                    .endsWith("/Vault/.nextcloud-native-backup-${requireNotNull(uploadId)}"),
            )
            assertTrue(moveDestinations.last()!!.endsWith("/Vault/archive.bin"))
            assertEquals("stage-etag", checkpoints.last().assembledStageEtag)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `ambiguous large directory replacement verifies its published file before preflight`() {
        val requests = mutableListOf<Request>()
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            when (chain.request().method) {
                "GET" -> binaryResponse(chain.request(), 200, ByteArray(21 * 1024 * 1024))
                "PROPFIND" -> response(
                    chain.request(),
                    207,
                    publishedReplacementListing(uploadId, "stage-etag", 21L * 1024L * 1024L),
                )
                "DELETE" -> response(chain.request(), 204)
                else -> error("Recovery must not ${chain.request().method} the published file")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            NextcloudSession("https://cloud.example.test", "alice", "secret"),
            "alice",
            "Vault",
            client,
            ownedUploadIds = setOf(uploadId),
            ownedStageEtags = mapOf(uploadId to "stage-etag"),
            ownedUploadPaths = mapOf(uploadId to "archive.bin"),
        )
        val source = Files.createTempFile("nextcloud-sync-published-replacement", ".tmp").toFile()
        RandomAccessFile(source, "rw").use { it.setLength(21L * 1024L * 1024L) }
        val local = LocalSyncEntry("archive.bin", SyncEntryKind.File, "local-1", source.length())
        val plan = nextcloudUploadTransferPlan(source.length()) as NextcloudUploadTransferPlan.Chunked
        val checkpoint = newFileSyncUploadCheckpoint(
            uploadId,
            local.revision,
            plan,
        ).copy(uploadedChunks = plan.chunkCount, commitInFlight = true, assembledStageEtag = "stage-etag")
        try {
            val result = executeDesktopFileSyncUpload(
                source, local.relativePath, local, "directory-etag", checkpoint, true,
                persistCheckpoint = {},
                retainCleanup = {},
                completeCleanup = {},
                remote = tree,
                shouldContinue = { true },
            )

            assertEquals("stage-etag", result.etag)
            assertEquals(
                listOf("PROPFIND", "PROPFIND", "PROPFIND", "GET", "PROPFIND", "DELETE"),
                requests.map { it.method },
            )
            assertTrue(requests.last().url.encodedPath.endsWith(".nextcloud-native-backup-$uploadId"))
        } finally {
            source.delete()
        }
    }

    @Test
    fun `published replacement cleanup failure stays visible after byte verification`() {
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            when (chain.request().method) {
                "PROPFIND" -> response(
                    chain.request(),
                    207,
                    publishedReplacementListing(uploadId, "stage-etag", 4),
                )
                "DELETE" -> response(chain.request(), 500)
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            NextcloudSession("https://cloud.example.test", "alice", "secret"),
            "alice",
            "Vault",
            client,
            ownedUploadIds = setOf(uploadId),
            ownedStageEtags = mapOf(uploadId to "stage-etag"),
            ownedUploadPaths = mapOf(uploadId to "archive.bin"),
        )

        assertFails {
            tree.resumableUploadRemote(replacingDirectoryEtag = "directory-etag")
                .completePublishedFile(uploadId, "archive.bin")
        }
        assertEquals(listOf("PROPFIND", "DELETE"), requests.map { it.method })
    }

    @Test
    fun `published replacement cleanup preserves a changed backup generation`() {
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            response(
                chain.request(),
                207,
                publishedReplacementListing(
                    uploadId,
                    "stage-etag",
                    4,
                    backupEtag = "changed-directory-etag",
                ),
            )
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            NextcloudSession("https://cloud.example.test", "alice", "secret"),
            "alice",
            "Vault",
            client,
            ownedUploadIds = setOf(uploadId),
            ownedUploadPaths = mapOf(uploadId to "archive.bin"),
        )

        assertFailsWith<IllegalArgumentException> {
            tree.resumableUploadRemote(replacingDirectoryEtag = "directory-etag")
                .completePublishedFile(uploadId, "archive.bin")
        }
        assertEquals(listOf("PROPFIND"), requests.map { it.method })
    }

    @Test
    fun `empty range verification revalidates the listed remote generation`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 207, "<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>")
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
            userId = "alice",
            remoteRootPath = "Vault",
            client = client,
        )

        assertFails {
            tree.contentRangeHash(
                relativePath = "empty.bin",
                expectedRemoteEtag = "\"empty-1\"",
                expectedBytes = 0L,
                offset = 0L,
                length = 0,
                shouldContinue = { true },
            )
        }
    }

    @Test
    fun `content identity streams and verifies one exact remote generation`() {
        val methods = mutableListOf<String>()
        val ifMatches = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            methods += chain.request().method
            ifMatches += chain.request().header("If-Match")
            when (chain.request().method) {
                "GET" -> Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("test")
                    .header("ETag", "\"note-7\"")
                    .body("same note".toResponseBody())
                    .build()
                "PROPFIND" -> response(
                    chain.request(),
                    207,
                    """
                    <d:multistatus xmlns:d="DAV:">
                      <d:response><d:href>/remote.php/dav/files/alice/Vault/Notes/today.md</d:href>
                        <d:propstat><d:prop><d:getetag>"note-7"</d:getetag>
                          <d:getcontentlength>9</d:getcontentlength><d:resourcetype/></d:prop>
                        </d:propstat></d:response>
                    </d:multistatus>
                    """.trimIndent(),
                )
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
            userId = "alice",
            remoteRootPath = "Vault",
            client = client,
        )
        val expected = "sha256:8b4c848f9c906b8b340c2400c9aa8fdc1c9d5db557bad1b6aabdd9aabe3eb6e9"

        assertTrue(
            tree.verifyContentHash(
                relativePath = "Notes/today.md",
                expectedRemoteEtag = "\"note-7\"",
                expectedBytes = 9L,
                expectedContentHash = expected,
                maximumBytes = 1_024L,
                shouldContinue = { true },
            ),
        )
        assertEquals(listOf("GET", "PROPFIND"), methods)
        assertEquals(listOf("\"note-7\"", null), ifMatches)
    }

    @Test
    fun `replacing an existing file uses a destination guarded put`() {
        val methods = mutableListOf<String>()
        val conditionalHeaders = mutableListOf<String?>()
        var listingCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            methods += chain.request().method
            conditionalHeaders += chain.request().header("If-Match")
            when (chain.request().method) {
                "PROPFIND" -> {
                    listingCount += 1
                    response(
                        chain.request(),
                        207,
                        """
                        <d:multistatus xmlns:d="DAV:">
                          <d:response><d:href>/remote.php/dav/files/alice/note.md</d:href>
                            <d:propstat><d:prop><d:getetag>${if (listingCount == 1) "old-etag" else "new-etag"}</d:getetag>
                              <d:getcontentlength>7</d:getcontentlength><d:resourcetype/></d:prop>
                            </d:propstat></d:response>
                        </d:multistatus>
                        """.trimIndent(),
                    )
                }
                "PUT" -> response(chain.request(), 204)
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
            userId = "alice",
            remoteRootPath = "",
            client = client,
        )
        val source = Files.createTempFile("nextcloud-sync-replacement", ".tmp").toFile()
        try {
            source.writeText("updated")

            val result = tree.writeFile("note.md", source, expectedRemoteEtag = "old-etag")

            assertEquals("new-etag", result.etag)
            assertEquals(listOf("PROPFIND", "PUT", "PROPFIND"), methods)
            assertEquals(listOf(null, "old-etag", null), conditionalHeaders)
        } finally {
            assertTrue(source.delete())
        }
    }

    @Test
    fun `definitive mutation rejection preserves cached metadata`() {
        val invalidated = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            when (chain.request().method) {
                "PROPFIND" -> response(
                    chain.request(),
                    207,
                    "<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>",
                )
                "MKCOL" -> response(chain.request(), 412)
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
            userId = "alice",
            remoteRootPath = "",
            client = client,
            onMutationCommitted = invalidated::add,
        )

        assertFails { tree.createDirectory("Photos", expectedRemoteEtag = null) }
        assertEquals(emptyList(), invalidated)
    }

    @Test
    fun `confirmed mutation invalidates cached metadata once`() {
        val invalidated = mutableListOf<String>()
        val ambiguous = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            when (chain.request().method) {
                "PROPFIND" -> response(
                    chain.request(),
                    207,
                    "<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>",
                )
                "MKCOL" -> response(chain.request(), 201)
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
            userId = "alice",
            remoteRootPath = "",
            client = client,
            onMutationCommitted = invalidated::add,
            onAmbiguousMutationResult = ambiguous::add,
        )

        tree.createDirectory("Photos", expectedRemoteEtag = null)
        assertEquals(listOf("Photos"), invalidated)
        assertEquals(emptyList(), ambiguous)
    }

    @Test
    fun `accepted mutation invalidates metadata before an oversized response fails`() {
        val invalidated = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            when (chain.request().method) {
                "PROPFIND" -> response(
                    chain.request(),
                    207,
                    "<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>",
                )
                "MKCOL" -> response(chain.request(), 201, "x".repeat(65 * 1024))
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
            userId = "alice",
            remoteRootPath = "",
            client = client,
            onMutationCommitted = invalidated::add,
        )

        assertFails { tree.createDirectory("Photos", expectedRemoteEtag = null) }
        assertEquals(listOf("Photos"), invalidated)
    }

    @Test
    fun `directory emptiness is bound to the revision in one authoritative listing`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(
                chain.request(),
                207,
                """
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/remote.php/dav/files/alice/Photos/Trips/</d:href>
                    <d:propstat><d:prop><d:getetag>current-directory</d:getetag>
                      <d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop></d:propstat></d:response>
                  <d:response><d:href>/remote.php/dav/files/alice/Photos/Trips/new.raf</d:href>
                    <d:propstat><d:prop><d:getetag>new-file</d:getetag><d:getcontentlength>1</d:getcontentlength>
                      <d:resourcetype/></d:prop></d:propstat></d:response>
                </d:multistatus>
                """.trimIndent(),
            )
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
            userId = "alice",
            remoteRootPath = "",
            client = client,
        )

        assertFails { tree.isDirectoryEmpty("Photos/Trips", "stale-directory") }
        assertFalse(tree.isDirectoryEmpty("Photos/Trips", "current-directory"))
    }

    @Test
    fun `confirmed replacement backup move invalidates metadata when later moves fail`() {
        val confirmed = mutableListOf<String>()
        var backupPath: String? = null
        var propfindCount = 0
        var moveCount = 0
        fun listing(vararg entries: Pair<String, String>): String = entries.joinToString(
            prefix = "<d:multistatus xmlns:d=\"DAV:\">",
            postfix = "</d:multistatus>",
            separator = "",
        ) { (path, etag) ->
            "<d:response><d:href>/remote.php/dav/files/alice/$path</d:href>" +
                "<d:propstat><d:prop><d:getetag>$etag</d:getetag><d:getcontentlength>4</d:getcontentlength>" +
                "<d:resourcetype/></d:prop></d:propstat></d:response>"
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            when (chain.request().method) {
                "PROPFIND" -> {
                    propfindCount += 1
                    val body = if (propfindCount <= 2) {
                        listing("source.txt" to "source-etag", "destination.txt" to "destination-etag")
                    } else {
                        listing("source.txt" to "source-etag", requireNotNull(backupPath) to "destination-etag")
                    }
                    response(chain.request(), 207, body)
                }
                "MOVE" -> {
                    moveCount += 1
                    when (moveCount) {
                        1 -> {
                            backupPath = requireNotNull(chain.request().header("Destination"))
                                .substringAfter("/remote.php/dav/files/alice/")
                            response(chain.request(), 201)
                        }
                        2 -> response(chain.request(), 412)
                        else -> response(chain.request(), 500)
                    }
                }
                else -> error("Unexpected ${chain.request().method} request")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
            userId = "alice",
            remoteRootPath = "",
            client = client,
            onMutationCommitted = confirmed::add,
        )

        assertFails {
            tree.moveReplacing("source.txt", "destination.txt", "source-etag", "destination-etag")
        }
        assertEquals(listOf("destination.txt"), confirmed)
        assertEquals(3, moveCount)
    }

    @Test
    fun `only started mutation exchanges have ambiguous io results`() {
        val failure = IOException("connection closed")

        assertEquals(false, desktopMutationResultIsAmbiguous(networkExchangeStarted = false, failure))
        assertEquals(true, desktopMutationResultIsAmbiguous(networkExchangeStarted = true, failure))
        assertEquals(false, desktopMutationResultIsAmbiguous(networkExchangeStarted = true, IllegalStateException()))
    }

    @Test
    fun `lost mutation response invokes only ambiguous metadata recovery`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val worker = Executors.newSingleThreadExecutor()
        val confirmed = mutableListOf<String>()
        val ambiguous = mutableListOf<String>()
        val served = worker.submit {
            server.accept().use { socket ->
                val input = socket.getInputStream().buffered()
                assertTrue(readRequestHeaders(input).startsWith("PROPFIND "))
                val body = "<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>"
                socket.getOutputStream().write(
                    (
                        "HTTP/1.1 207 Multi-Status\r\n" +
                            "Content-Length: ${body.encodeToByteArray().size}\r\n" +
                            "Connection: keep-alive\r\n\r\n" + body
                        ).encodeToByteArray(),
                )
                socket.getOutputStream().flush()
                assertTrue(readRequestHeaders(input).startsWith("MKCOL "))
            }
        }
        try {
            val tree = DesktopFileSyncRemoteTree(
                session = NextcloudSession("http://127.0.0.1:${server.localPort}", "alice", "secret"),
                userId = "alice",
                remoteRootPath = "",
                onMutationCommitted = confirmed::add,
                onAmbiguousMutationResult = ambiguous::add,
            )

            assertFails { tree.createDirectory("Photos", expectedRemoteEtag = null) }
            served.get()
            assertEquals(emptyList(), confirmed)
            assertEquals(listOf("Photos"), ambiguous)
        } finally {
            runCatching(server::close)
            worker.shutdownNow()
        }
    }

    @Test
    fun `pre-network io failure does not invoke ambiguous recovery`() {
        val executor = DesktopHttpMutationExecutor(
            OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build(),
        )
        var recovered = false

        assertFails {
            executor.execute(
                Request.Builder().url("https://cloud.example.test/remote.php/dav/files/alice/Photos").build(),
                onAmbiguousNetworkResult = { recovered = true },
            ) { response -> response.code }
        }
        assertFalse(recovered)
    }

    @Test
    fun `dav parser preserves plus signs and reads guarded revisions`() {
        val documents = parseDesktopSyncDav(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/remote.php/dav/files/alice/Photos/July%20%2B%20August/</d:href>
                <d:propstat><d:prop>
                  <d:displayname>July + August</d:displayname>
                  <d:getetag>"directory-etag"</d:getetag>
                  <d:resourcetype><d:collection/></d:resourcetype>
                </d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/alice/Photos/July%20%2B%20August/a.RAF</d:href>
                <d:propstat><d:prop>
                  <d:getetag>"file-etag"</d:getetag>
                  <d:getcontentlength>42</d:getcontentlength>
                  <d:getlastmodified>Sat, 01 Aug 2026 12:34:56 GMT</d:getlastmodified>
                  <d:resourcetype/>
                </d:prop></d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent().encodeToByteArray(),
            userId = "alice",
        )

        assertEquals(
            listOf("Photos/July + August", "Photos/July + August/a.RAF"),
            documents.map { it.entry.relativePath },
        )
        assertEquals(SyncEntryKind.Directory, documents.first().entry.kind)
        assertEquals(42L, documents.last().entry.size)
        assertEquals("\"file-etag\"", documents.last().entry.etag)
        assertEquals(1_785_587_696_000L, documents.last().lastModifiedEpochMillis)
    }

    @Test
    fun `dav parser accepts bounded cdata properties`() {
        val documents = parseDesktopSyncDav(
            (
                "<d:multistatus xmlns:d=\"DAV:\"><d:response>" +
                    "<d:href><![CDATA[/remote.php/dav/files/alice/Photos/a%20b.jpg]]></d:href>" +
                    "<d:propstat><d:prop><d:getetag><![CDATA[\"etag-a\"]]></d:getetag>" +
                    "<d:getcontentlength><![CDATA[4]]></d:getcontentlength>" +
                    "</d:prop></d:propstat></d:response></d:multistatus>"
                ).encodeToByteArray(),
            userId = "alice",
        )

        assertEquals(listOf("Photos/a b.jpg"), documents.map { it.entry.relativePath })
        assertEquals("\"etag-a\"", documents.single().entry.etag)
        assertEquals(4L, documents.single().entry.size)
    }

    private fun response(request: Request, code: Int, body: String = ""): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody())
        .build()

    private fun binaryResponse(request: Request, code: Int, body: ByteArray): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody())
        .build()

    private fun readRequestHeaders(input: java.io.InputStream): String {
        val bytes = ArrayList<Byte>()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < terminator.size) {
            val next = input.read()
            check(next >= 0) { "The test client closed before sending complete HTTP headers." }
            val byte = next.toByte()
            bytes += byte
            matched = if (byte == terminator[matched]) matched + 1 else if (byte == terminator[0]) 1 else 0
        }
        val headers = bytes.toByteArray().decodeToString()
        val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)\\s*$")
            .find(headers)?.groupValues?.get(1)?.toInt() ?: 0
        repeat(contentLength) {
            check(input.read() >= 0) { "The test client closed before sending its HTTP body." }
        }
        return headers
    }

    @Test
    fun `dav parser rejects external entities`() {
        assertFails {
            parseDesktopSyncDav(
                """
                <?xml version="1.0"?>
                <!DOCTYPE data [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>&xxe;</d:href></d:response></d:multistatus>
                """.trimIndent().encodeToByteArray(),
                userId = "alice",
            )
        }
    }

    @Test
    fun `dav parser rejects excess documents while streaming`() {
        val responses = (1..3).joinToString("") { index ->
            """
            <d:response>
              <d:href>/remote.php/dav/files/alice/Photos/$index.jpg</d:href>
              <d:propstat><d:prop><d:getetag>etag-$index</d:getetag></d:prop></d:propstat>
            </d:response>
            """.trimIndent()
        }

        assertFails {
            parseDesktopSyncDav(
                "<d:multistatus xmlns:d=\"DAV:\">$responses</d:multistatus>".encodeToByteArray(),
                userId = "alice",
                maximumDocuments = 2,
            )
        }
    }

    @Test
    fun `dav parser counts unusable responses against its streaming limit`() {
        val responses = "<d:response/>".repeat(3)

        assertFails {
            parseDesktopSyncDav(
                "<d:multistatus xmlns:d=\"DAV:\">$responses</d:multistatus>".encodeToByteArray(),
                userId = "alice",
                maximumDocuments = 2,
            )
        }
    }

    @Test
    fun `dav parser rejects an oversized property without coalescing text`() {
        val oversizedHref = "a".repeat(20_000)
        val response = (
            "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>$oversizedHref</d:href>" +
                "</d:response></d:multistatus>"
            ).encodeToByteArray()

        assertFails {
            parseDesktopSyncDav(
                response,
                userId = "alice",
            )
        }
    }

    @Test
    fun `dav parser rejects cdata before materializing property text`() {
        assertFails {
            parseDesktopSyncDav(
                (
                    "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href><![CDATA[" +
                        "a".repeat(20_000) +
                        "]]></d:href></d:response></d:multistatus>"
                    ).encodeToByteArray(),
                userId = "alice",
            )
        }
    }

    @Test
    fun `dav parser rejects non utf8 xml before decoded events`() {
        val utf16 = (
            "<?xml version=\"1.0\" encoding=\"UTF-16\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>Photos/a.jpg</d:href>" +
                "</d:response></d:multistatus>"
            ).toByteArray(Charsets.UTF_16)

        assertFails { parseDesktopSyncDav(utf16, userId = "alice") }
    }

    @Test
    fun `dav parser bounds markup and rejects unsupported token forms before stax`() {
        val oversizedAttribute = (
            "<d:multistatus xmlns:d=\"DAV:\" data-value=\"" + "a".repeat(20_000) +
                "\"></d:multistatus>"
            ).encodeToByteArray()
        val comment = "<d:multistatus xmlns:d=\"DAV:\"><!-- comment --></d:multistatus>".encodeToByteArray()
        val instruction = "<d:multistatus xmlns:d=\"DAV:\"><?unsafe value?></d:multistatus>".encodeToByteArray()

        listOf(oversizedAttribute, comment, instruction).forEach { response ->
            assertFails { parseDesktopSyncDav(response, userId = "alice") }
        }
    }

    @Test
    fun `only exact provider owned upload stages are suppressed`() {
        assertEquals(
            true,
            isDesktopOwnedUploadStage("Photos/.nextcloud-native-123e4567-e89b-12d3-a456-426614174000.upload"),
        )
        assertEquals(false, isDesktopOwnedUploadStage("Photos/.nextcloud-native-not-a-uuid.upload"))
        assertEquals(false, isDesktopOwnedUploadStage("Photos/user-upload.upload"))
    }

    @Test
    fun `only exact provider owned replacement backup names reveal an upload id`() {
        assertEquals(
            "123e4567-e89b-12d3-a456-426614174000",
            jvmOwnedReplacementBackupUploadId(
                "Photos/.nextcloud-native-backup-123e4567-e89b-12d3-a456-426614174000",
            ),
        )
        assertEquals(null, jvmOwnedReplacementBackupUploadId("Photos/.nextcloud-native-backup-not-a-uuid"))
        assertEquals(null, jvmOwnedReplacementBackupUploadId("Photos/user-backup"))
    }

    @Test
    fun `backup recovery is bounded before orphan processing`() {
        val firstId = "123e4567-e89b-12d3-a456-426614174000"
        val secondId = "123e4567-e89b-12d3-a456-426614174001"
        val first = "Photos/.nextcloud-native-backup-$firstId"
        val second = "Photos/.nextcloud-native-backup-$secondId"
        val paths = mapOf(firstId to "Photos/one.jpg", secondId to "Photos/two.jpg")

        assertFails {
            desktopOwnedBackupRecoveryPlan(
                listOf(first, second),
                paths,
                maximumRecoveryItems = 1,
            )
        }
        assertEquals(
            listOf(first to "Photos/one.jpg"),
            desktopOwnedBackupRecoveryPlan(listOf(first), paths, maximumRecoveryItems = 1),
        )
        assertEquals(
            emptyList(),
            desktopOwnedBackupRecoveryPlan(
                listOf(first, "Photos/one.jpg"),
                paths,
                maximumRecoveryItems = 1,
            ),
        )
        assertEquals(
            emptyList(),
            desktopOwnedBackupRecoveryPlan(
                listOf(first, second, "Photos/one.jpg", "Photos/two.jpg"),
                paths,
                maximumRecoveryItems = 0,
            ),
        )
        assertEquals(
            emptyList(),
            desktopOwnedBackupRecoveryPlan(listOf(first), emptyMap(), maximumRecoveryItems = 0),
        )
    }

    @Test
    fun `replacement backup projects only its verified published stage`() {
        val uploadId = "123e4567-e89b-12d3-a456-426614174000"
        val published = RemoteSyncEntry("Photos/today.md", SyncEntryKind.File, "stage-etag", 4)

        assertTrue(
            shouldProjectJvmOwnedReplacementBackup(
                uploadId,
                published,
                mapOf(uploadId to "stage-etag"),
            ),
        )
        assertFalse(
            shouldProjectJvmOwnedReplacementBackup(
                uploadId,
                published.copy(etag = "concurrent-etag"),
                mapOf(uploadId to "stage-etag"),
            ),
        )
    }

    private fun davFile(name: String, etag: String, sizeBytes: Long): String =
        """
        <d:multistatus xmlns:d="DAV:"><d:response>
          <d:href>/remote.php/dav/files/alice/Vault/$name</d:href>
          <d:propstat><d:prop><d:getetag>$etag</d:getetag>
            <d:getcontentlength>$sizeBytes</d:getcontentlength><d:resourcetype/>
          </d:prop></d:propstat>
        </d:response></d:multistatus>
        """.trimIndent()

    private fun davDirectory(name: String, etag: String): String =
        """
        <d:multistatus xmlns:d="DAV:"><d:response>
          <d:href>/remote.php/dav/files/alice/Vault/$name/</d:href>
          <d:propstat><d:prop><d:getetag>$etag</d:getetag>
            <d:resourcetype><d:collection/></d:resourcetype>
          </d:prop></d:propstat>
        </d:response></d:multistatus>
        """.trimIndent()

    private fun publishedReplacementListing(
        uploadId: String,
        etag: String,
        sizeBytes: Long,
        backupEtag: String = "directory-etag",
    ): String =
        """
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/remote.php/dav/files/alice/Vault/archive.bin</d:href>
            <d:propstat><d:prop><d:getetag>$etag</d:getetag>
              <d:getcontentlength>$sizeBytes</d:getcontentlength><d:resourcetype/>
            </d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-backup-$uploadId/</d:href>
            <d:propstat><d:prop><d:getetag>$backupEtag</d:getetag>
              <d:resourcetype><d:collection/></d:resourcetype>
            </d:prop></d:propstat>
          </d:response>
        </d:multistatus>
        """.trimIndent()
}
