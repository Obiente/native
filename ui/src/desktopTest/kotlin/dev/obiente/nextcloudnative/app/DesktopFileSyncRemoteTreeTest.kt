package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class DesktopFileSyncRemoteTreeTest {
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

    private fun response(request: Request, code: Int, body: String = ""): Response = Response.Builder()
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
    fun `only exact provider owned replacement backups reveal a recovery destination`() {
        assertEquals(
            "Photos/today.md",
            desktopOwnedBackupDestination(
                "Photos/.today.md.nextcloud-native-backup-123e4567-e89b-12d3-a456-426614174000",
            ),
        )
        assertEquals(null, desktopOwnedBackupDestination("Photos/.today.md.nextcloud-native-backup-not-a-uuid"))
        assertEquals(null, desktopOwnedBackupDestination("Photos/user-backup"))
    }

    @Test
    fun `backup recovery is bounded before orphan processing`() {
        val first = "Photos/.one.jpg.nextcloud-native-backup-123e4567-e89b-12d3-a456-426614174000"
        val second = "Photos/.two.jpg.nextcloud-native-backup-123e4567-e89b-12d3-a456-426614174001"

        assertFails {
            desktopOwnedBackupRecoveryPlan(listOf(first, second), maximumRecoveryItems = 1)
        }
        assertEquals(
            listOf(first to "Photos/one.jpg"),
            desktopOwnedBackupRecoveryPlan(listOf(first), maximumRecoveryItems = 1),
        )
        assertEquals(
            emptyList(),
            desktopOwnedBackupRecoveryPlan(listOf(first, "Photos/one.jpg"), maximumRecoveryItems = 1),
        )
        assertEquals(
            emptyList(),
            desktopOwnedBackupRecoveryPlan(
                listOf(first, second, "Photos/one.jpg", "Photos/two.jpg"),
                maximumRecoveryItems = 0,
            ),
        )
    }

    @Test
    fun `completed replacement backup is exposed when its destination also exists`() {
        val backup = "Photos/.today.md.nextcloud-native-backup-123e4567-e89b-12d3-a456-426614174000"

        assertEquals(false, shouldSuppressDesktopOwnedBackup(backup, setOf(backup, "Photos/today.md")))
        assertEquals(true, shouldSuppressDesktopOwnedBackup(backup, setOf(backup)))
    }
}
