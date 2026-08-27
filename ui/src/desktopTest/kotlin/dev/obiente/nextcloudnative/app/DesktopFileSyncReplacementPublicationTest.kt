package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class DesktopFileSyncReplacementPublicationTest {
    @Test
    fun `direct replacement deletes its backup before completing ownership`() {
        val payload = "replacement file".encodeToByteArray()
        val retained = mutableListOf<FileSyncPendingUploadCleanup>()
        val completed = mutableListOf<String>()
        val requests = mutableListOf<Request>()
        var uploadId: String? = null
        var published = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            when (chain.request().method) {
                "PUT" -> {
                    uploadId = chain.request().url.pathSegments.last()
                        .removePrefix(".nextcloud-native-").removeSuffix(".upload")
                    response(chain.request(), 201).newBuilder().header("ETag", "stage-etag").build()
                }
                "MOVE" -> {
                    if (chain.request().header("Destination")!!.endsWith("/Vault/archive.bin")) published = true
                    response(chain.request(), 201)
                }
                "GET" -> response(chain.request(), 200, payload)
                "DELETE" -> {
                    assertTrue(completed.isEmpty())
                    response(chain.request(), 204)
                }
                "PROPFIND" -> response(
                    chain.request(),
                    207,
                    if (published) publishedListing(requireNotNull(uploadId), payload.size.toLong())
                    else stagedListing(uploadId, payload.size.toLong()),
                )
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

            val result = replaceDesktopFileSyncRemoteType(
                source, "archive.bin", "directory-etag", tree,
                retained::add, completed::add, shouldContinue = { true },
            )

            assertEquals("published-etag", result.etag)
            assertEquals(listOf(requireNotNull(uploadId)), completed)
            assertEquals(2, retained.size)
            assertEquals("stage-etag", retained.last().assembledStageEtag)
            assertTrue(retained.last().publicationInFlight)
            assertTrue(requests.indexOfLast { it.method == "GET" } < requests.indexOfLast { it.method == "DELETE" })
            assertTrue(requests.last { it.method == "DELETE" }.url.encodedPath.contains(".nextcloud-native-backup-"))
        } finally {
            assertTrue(source.delete())
        }
    }

    @Test
    fun `restart verifies a published direct replacement instead of rolling it back`() {
        val payload = "replacement file".encodeToByteArray()
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            when (chain.request().method) {
                "PROPFIND" -> response(chain.request(), 207, publishedListing(uploadId, payload.size.toLong()))
                "GET" -> response(chain.request(), 200, payload).newBuilder()
                    .header("ETag", "published-etag")
                    .build()
                "DELETE" -> response(chain.request(), if (chain.request().url.encodedPath.contains("/uploads/")) 404 else 204)
                else -> error("Restart cleanup must not ${chain.request().method} the published file")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            NextcloudSession("https://cloud.example.test", "alice", "secret"),
            "alice",
            "Vault",
            client,
            ownedUploadIds = setOf(uploadId),
            ownedStageEtags = mapOf(uploadId to "published-etag"),
            ownedUploadPaths = mapOf(uploadId to "archive.bin"),
            ownedReplacementBackupEtags = mapOf(uploadId to "directory-etag"),
        )

        val cleaned = tree.resumableUploadRemote(shouldContinue = { true }).discardOwnedUpload(
            uploadId = uploadId,
            relativePath = "archive.bin",
            assembledStageEtag = "stage-etag",
            expectedStageSizeBytes = payload.size.toLong(),
            expectedStageContentHash = hashExactJvmFileSyncContent(
                payload.inputStream(),
                payload.size.toLong(),
            ),
            publicationInFlight = true,
        )

        assertTrue(cleaned)
        assertEquals(1, requests.count { it.method == "GET" })
        assertTrue(requests.none { it.method == "DELETE" && it.url.encodedPath.endsWith("/archive.bin") })
        assertTrue(requests.last().url.encodedPath.endsWith(".nextcloud-native-backup-$uploadId"))
    }

    @Test
    fun `definitive published mismatch preserves the protected directory as a conflict`() {
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            when (chain.request().method) {
                "PROPFIND" -> response(chain.request(), 207, publishedListing(uploadId, sizeBytes = 5))
                "MOVE" -> response(chain.request(), 201)
                else -> error("Mismatch recovery must not ${chain.request().method} either generation")
            }
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            NextcloudSession("https://cloud.example.test", "alice", "secret"),
            "alice",
            "Vault",
            client,
            ownedUploadIds = setOf(uploadId),
            ownedUploadPaths = mapOf(uploadId to "archive.bin"),
            ownedReplacementBackupEtags = mapOf(uploadId to "directory-etag"),
        )

        val reconciled = tree.reconcilePublishedReplacement(
            relativePath = "archive.bin",
            uploadId = uploadId,
            expectedSizeBytes = 4,
            expectedContentHash = "sha256:" + "55".repeat(32),
            shouldContinue = { true },
        )

        assertEquals(true, reconciled)
        val move = requests.single { it.method == "MOVE" }
        assertTrue(move.url.encodedPath.endsWith(".nextcloud-native-backup-$uploadId"))
        assertTrue(move.header("Destination").orEmpty().endsWith(".nextcloud-native-conflict-$uploadId"))
        assertTrue(requests.none { it.method == "DELETE" || it.method == "GET" })
    }

    @Test
    fun `recovery scan traverses an owned backup at its physical path`() {
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        val requestedPaths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedPaths += chain.request().url.encodedPath
            val body = if (chain.request().url.encodedPath.endsWith(".nextcloud-native-backup-$uploadId")) {
                backupChildListing(uploadId)
            } else {
                publishedListing(uploadId, 4)
            }
            response(chain.request(), 207, body)
        }.build()
        val tree = DesktopFileSyncRemoteTree(
            NextcloudSession("https://cloud.example.test", "alice", "secret"),
            "alice",
            "Vault",
            client,
            ownedUploadIds = setOf(uploadId),
            ownedStageEtags = mapOf(uploadId to "published-etag"),
            ownedUploadPaths = mapOf(uploadId to "archive.bin"),
            ownedReplacementBackupEtags = mapOf(uploadId to "directory-etag"),
        )

        val scanned = tree.scan()

        assertEquals(listOf("archive.bin", "archive.bin/kept.txt"), scanned.map { it.entry.relativePath })
        assertTrue(requestedPaths.last().endsWith(".nextcloud-native-backup-$uploadId"))
        assertTrue(requestedPaths.none { it.endsWith("/archive.bin") })
    }

    private fun stagedListing(uploadId: String?, sizeBytes: Long): String =
        """
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/remote.php/dav/files/alice/Vault/archive.bin/</d:href>
            <d:propstat><d:prop><d:getetag>directory-etag</d:getetag>
              <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
          ${uploadId?.let { fileResponse(".nextcloud-native-$it.upload", "stage-etag", sizeBytes) }.orEmpty()}
        </d:multistatus>
        """.trimIndent()

    private fun publishedListing(uploadId: String, sizeBytes: Long): String =
        """
        <d:multistatus xmlns:d="DAV:">
          ${fileResponse("archive.bin", "published-etag", sizeBytes)}
          <d:response><d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-backup-$uploadId/</d:href>
            <d:propstat><d:prop><d:getetag>directory-etag</d:getetag>
              <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
        </d:multistatus>
        """.trimIndent()

    private fun backupChildListing(uploadId: String): String =
        """
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/remote.php/dav/files/alice/Vault/.nextcloud-native-backup-$uploadId/</d:href>
            <d:propstat><d:prop><d:getetag>directory-etag</d:getetag>
              <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
          ${fileResponse(".nextcloud-native-backup-$uploadId/kept.txt", "child-etag", 3)}
        </d:multistatus>
        """.trimIndent()

    private fun fileResponse(name: String, etag: String, sizeBytes: Long): String =
        """
        <d:response><d:href>/remote.php/dav/files/alice/Vault/$name</d:href>
          <d:propstat><d:prop><d:getetag>$etag</d:getetag><d:getcontentlength>$sizeBytes</d:getcontentlength>
            <d:resourcetype/></d:prop></d:propstat></d:response>
        """.trimIndent()

    private fun response(request: Request, code: Int, body: ByteArray = ByteArray(0)): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody()).build()

    private fun response(request: Request, code: Int, body: String): Response =
        response(request, code, body.encodeToByteArray())
}
