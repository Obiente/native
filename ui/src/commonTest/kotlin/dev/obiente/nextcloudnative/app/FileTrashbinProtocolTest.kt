package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTrashbinProtocolTest {
    @Test
    fun listRequestTargetsOnlyTheUsersTrashCollection() {
        val request = trashbinListRequest("synthetic user")
        assertEquals(FilesDavMethod.PROPFIND, request.method)
        assertEquals("/remote.php/dav/trashbin/synthetic%20user/trash/", request.relativePath)
        assertEquals(1, request.depth)
        assertTrue(request.body!!.decodeToString().contains("trashbin-original-location"))
    }

    @Test
    fun restoreMovesTheServerKeyIntoTheRestoreCollection() {
        val request = trashbinRestoreRequest("alice", item("report.pdf.d1700000000"))
        assertEquals(FilesDavMethod.MOVE, request.method)
        assertEquals("/remote.php/dav/trashbin/alice/trash/report.pdf.d1700000000", request.relativePath)
        assertEquals("/remote.php/dav/trashbin/alice/restore/report.pdf.d1700000000", request.destinationRelativePath)
        val mapped = request.toAccountDavRequest("https://cloud.example.test/")
        assertEquals("https://cloud.example.test/remote.php/dav/trashbin/alice/restore/report.pdf.d1700000000", mapped.headers["Destination"])
    }

    @Test
    fun deleteAndEmptyUseDeleteWithoutBodies() {
        assertEquals("/remote.php/dav/trashbin/alice/trash/a.d1", trashbinDeleteRequest("alice", item("a.d1")).relativePath)
        val empty = trashbinEmptyRequest("alice")
        assertEquals(FilesDavMethod.DELETE, empty.method)
        assertEquals("/remote.php/dav/trashbin/alice/trash", empty.relativePath)
        assertNull(empty.body)
    }

    @Test
    fun filesDavRequestRejectsPathsOutsideReviewedRoots() {
        assertFailsWith<IllegalArgumentException> { FilesDavRequest(FilesDavMethod.DELETE, "/remote.php/dav/files/alice/a.txt") }
        assertFailsWith<IllegalArgumentException> { FilesDavRequest(FilesDavMethod.DELETE, "/remote.php/dav/trashbin/alice/trash/../x") }
        assertFailsWith<IllegalArgumentException> { FilesDavRequest(FilesDavMethod.DELETE, "/remote.php/dav/trashbin/alice/trash/a%2Fb") }
        assertFailsWith<IllegalArgumentException> { FilesDavRequest(FilesDavMethod.DELETE, "/ocs/v2.php/cloud/user") }
        assertFailsWith<IllegalArgumentException> {
            FilesDavRequest(FilesDavMethod.MOVE, "/remote.php/dav/trashbin/alice/trash/a", destinationRelativePath = "/remote.php/dav/comments/files/1")
        }
        assertFailsWith<IllegalArgumentException> {
            FilesDavRequest(FilesDavMethod.DELETE, "/remote.php/dav/trashbin/alice/trash/a", destinationRelativePath = "/remote.php/dav/trashbin/alice/restore/a")
        }
    }

    @Test
    fun parsesItemsNewestFirstAndSkipsTheCollectionAndUnsafeEntries() {
        val listing = parseTrashbinListing(multistatus(
            entry("/remote.php/dav/trashbin/alice/trash/", folder = true),
            entry("/remote.php/dav/trashbin/alice/trash/old.txt.d100", name = "old.txt", time = 100, location = "Docs/old.txt", size = 12),
            entry("/remote.php/dav/trashbin/alice/trash/Photos.d200", name = "Photos", time = 200, location = "Photos", folder = true),
            entry("/remote.php/dav/trashbin/bob/trash/foreign.d1", name = "foreign", time = 1),
            entry("/remote.php/dav/trashbin/alice/trash/missing-name.d5", name = null, time = 5),
        ), "alice")
        assertEquals(listOf("Photos.d200", "old.txt.d100"), listing.items.map { it.key })
        assertEquals(2, listing.skippedEntries)
        val old = listing.items.last()
        assertEquals("old.txt", old.name)
        assertEquals("Docs", old.originalFolder)
        assertEquals(12L, old.sizeBytes)
        assertFalse(old.isDirectory)
        assertTrue(listing.items.first().isDirectory)
        assertEquals("", listing.items.first().originalFolder)
    }

    @Test
    fun oversizedListingKeepsTheNewestItemsAndReportsTruncation() {
        val entries = listOf(entry("/remote.php/dav/trashbin/alice/trash/", folder = true)) +
            (1..MAX_TRASH_ITEMS + 1).map { index ->
                entry("/remote.php/dav/trashbin/alice/trash/file$index.txt.d$index", name = "file$index.txt", time = index.toLong())
            }
        val listing = parseTrashbinListing(multistatus(*entries.toTypedArray()), "alice")
        assertTrue(listing.truncated)
        assertEquals(MAX_TRASH_ITEMS, listing.items.size)
        assertEquals("file${MAX_TRASH_ITEMS + 1}.txt.d${MAX_TRASH_ITEMS + 1}", listing.items.first().key)
        assertFalse(listing.items.any { it.key == "file1.txt.d1" })
    }

    @Test
    fun malformedListingFailsClosed() {
        assertFailsWith<FilesDavProtocolException> {
            parseTrashbinListing(NextcloudApiResponse(200, "<html/>".encodeToByteArray(), "text/html", null), "alice")
        }
        assertFailsWith<FilesDavProtocolException> {
            parseTrashbinListing(NextcloudApiResponse(207, "not xml".encodeToByteArray(), "application/xml", null), "alice")
        }
    }

    @Test
    fun ignoresPropertiesFromFailedPropstatBlocks() {
        val body = """
            <d:multistatus xmlns:d="DAV:" xmlns:nc="http://nextcloud.org/ns">
              <d:response><d:href>/remote.php/dav/trashbin/alice/trash/a.d1</d:href>
                <d:propstat><d:prop><nc:trashbin-filename>a</nc:trashbin-filename></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                <d:propstat><d:prop><nc:trashbin-original-location>secret/a</nc:trashbin-original-location></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val item = parseTrashbinListing(NextcloudApiResponse(207, body.encodeToByteArray(), "application/xml", null), "alice").items.single()
        assertNull(item.originalLocation)
    }

    @Test
    fun availabilityComesFromFilesCapabilities() {
        assertEquals(TrashbinAvailability.Available(true), parseTrashbinAvailability(capabilities("""{"files":{"undelete":true}}""")))
        assertEquals(
            TrashbinAvailability.Available(false),
            parseTrashbinAvailability(capabilities("""{"files":{"undelete":true,"delete_from_trash":false}}""")),
        )
        assertEquals(TrashbinAvailability.NotEnabled, parseTrashbinAvailability(capabilities("""{"files":{"undelete":false}}""")))
        assertEquals(TrashbinAvailability.NotEnabled, parseTrashbinAvailability(capabilities("""{"core":{}}""")))
        assertFailsWith<FilesDavProtocolException> {
            parseTrashbinAvailability(NextcloudApiResponse(200, "{}".encodeToByteArray(), "application/json", null))
        }
    }

    @Test
    fun writeOutcomesSeparateRejectedFromUnknown(): Unit = kotlinx.coroutines.runBlocking {
        val request = trashbinEmptyRequest("alice")
        assertIs<FilesDavWriteOutcome.Confirmed>(sendFilesDavWrite({ response(204) }, request))
        assertEquals(
            FilesDavWriteOutcome.Rejected(FilesDavFailureKind.Forbidden, 403),
            sendFilesDavWrite({ response(403) }, request),
        )
        assertEquals(
            FilesDavWriteOutcome.Rejected(FilesDavFailureKind.InsufficientStorage, 507),
            sendFilesDavWrite({ response(507) }, request),
        )
        assertEquals(FilesDavWriteOutcome.Unknown, sendFilesDavWrite({ response(502) }, request))
        assertEquals(FilesDavWriteOutcome.Unknown, sendFilesDavWrite({ throw IllegalStateException("reset") }, request))
    }

    @Test
    fun cancellationIsNotConvertedIntoAnOutcome(): Unit = kotlinx.coroutines.runBlocking {
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            sendFilesDavWrite({ throw kotlinx.coroutines.CancellationException("left") }, trashbinEmptyRequest("alice"))
        }
    }

    @Test
    fun bulkReportNeverOverstatesSuccess() {
        val report = BulkOperationReport(
            "restored",
            listOf(
                BulkItemResult("a", "a", BulkItemOutcome.Completed),
                BulkItemResult("b", "b", BulkItemOutcome.Failed, "No space", retrySafe = true),
                BulkItemResult("c", "c", BulkItemOutcome.Unconfirmed, "Lost connection"),
            ),
        )
        assertEquals("1 of 3 items restored. 1 failed. 1 could not be confirmed; refresh before trying again.", report.summary())
        assertEquals(setOf("b"), report.retryableKeys)
        assertFailsWith<IllegalArgumentException> { BulkItemResult("x", "x", BulkItemOutcome.Unconfirmed, retrySafe = true) }
    }

    companion object {
        fun item(key: String) = NextcloudTrashItem(key, key.substringBeforeLast(".d"), null, null, false, null, null, null, null)

        fun response(status: Int, body: String = "") = NextcloudApiResponse(status, body.encodeToByteArray(), "application/xml", null)

        fun capabilities(json: String) = NextcloudApiResponse(
            200,
            """{"ocs":{"meta":{"statuscode":200},"data":{"capabilities":$json}}}""".encodeToByteArray(),
            "application/json",
            null,
        )

        fun multistatus(vararg entries: String) = NextcloudApiResponse(
            207,
            """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">${entries.joinToString("")}</d:multistatus>"""
                .encodeToByteArray(),
            "application/xml",
            null,
        )

        fun entry(
            href: String,
            name: String? = href.trimEnd('/').substringAfterLast('/').substringBeforeLast(".d"),
            time: Long? = null,
            location: String? = null,
            size: Long? = null,
            folder: Boolean = false,
        ) = buildString {
            append("<d:response><d:href>$href</d:href><d:propstat><d:prop>")
            name?.let { append("<nc:trashbin-filename>$it</nc:trashbin-filename>") }
            location?.let { append("<nc:trashbin-original-location>$it</nc:trashbin-original-location>") }
            time?.let { append("<nc:trashbin-deletion-time>$it</nc:trashbin-deletion-time>") }
            size?.let { append("<oc:size>$it</oc:size>") }
            append(if (folder) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/>")
            append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        }
    }
}
