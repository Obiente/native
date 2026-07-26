package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOperationsTest {
    @Test
    fun renameBuildsConflictSafeMoveWithoutOverwrite() {
        val spec = NextcloudFileMutation.Rename(
            sourcePath = "Documents/old name.md",
            newName = "new name.md",
            expectedEtag = "\"v2\"",
        ).toWebDavMutationSpec()

        assertEquals("MOVE", spec.method)
        assertEquals("Documents/old name.md", spec.sourcePath)
        assertEquals("Documents/new name.md", spec.destinationPath)
        assertEquals("\"v2\"", spec.expectedEtag)
        assertFalse(spec.overwrite)
    }

    @Test
    fun moveAndCopyPreserveOrReplaceTheSourceName() {
        val move = NextcloudFileMutation.Move(
            sourcePath = "Inbox/report.pdf",
            destinationDirectoryPath = "Archive/2026",
            expectedEtag = "etag",
        ).toWebDavMutationSpec()
        val copy = NextcloudFileMutation.Copy(
            sourcePath = "Inbox/report.pdf",
            destinationDirectoryPath = "",
            destinationName = "report copy.pdf",
            expectedEtag = "etag",
        ).toWebDavMutationSpec()

        assertEquals("Archive/2026/report.pdf", move.destinationPath)
        assertEquals("report copy.pdf", copy.destinationPath)
        assertEquals("COPY", copy.method)
    }

    @Test
    fun unsafeAndSelfNestedDestinationsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            NextcloudFileMutation.Rename("Documents/a.txt", "../b.txt", "etag").toWebDavMutationSpec()
        }
        assertFailsWith<IllegalArgumentException> {
            NextcloudFileMutation.Move("Folder", "Folder/Child", expectedEtag = "etag").toWebDavMutationSpec()
        }
        assertFailsWith<IllegalArgumentException> {
            NextcloudFileMutation.Delete("Folder/file.txt", " ").toWebDavMutationSpec()
        }
        assertFailsWith<IllegalArgumentException> {
            NextcloudFileMutation.Copy("../secret", "Archive", expectedEtag = "etag").toWebDavMutationSpec()
        }
    }

    @Test
    fun directoryMutationsPreserveCollectionConditionRequirement() {
        val spec = NextcloudFileMutation.Delete(
            sourcePath = "Projects",
            expectedEtag = "\"directory-etag\"",
            sourceIsDirectory = true,
        ).toWebDavMutationSpec()

        assertTrue(spec.sourceIsDirectory)
        assertEquals("\"directory-etag\"", spec.expectedEtag)
        assertEquals(mapOf("If" to "(\"directory-etag\")"), spec.conflictConditionHeaders())
        assertEquals(
            mapOf("If-Match" to "\"file-etag\""),
            NextcloudFileMutation.Delete("Projects/readme.md", "\"file-etag\"")
                .toWebDavMutationSpec()
                .conflictConditionHeaders(),
        )
    }

    @Test
    fun webDavStatusesMapToTypedErrors() {
        assertEquals(NextcloudFileOperationError.AuthenticationRequired, fileOperationException(401).error)
        assertEquals(NextcloudFileOperationError.Conflict, fileOperationException(412).error)
        assertEquals(NextcloudFileOperationError.Locked, fileOperationException(423).error)
        assertEquals(NextcloudFileOperationError.InsufficientStorage, fileOperationException(507).error)
        assertEquals(NextcloudFileOperationError.ServerFailure, fileOperationException(500).error)
    }

    @Test
    fun publicShareBuildsBoundedSameOriginOcsRequest() {
        val request = CreateFileShareRequest(
            path = "Photos/Summer trip.jpg",
            target = FileShareTarget.PublicLink,
            permissions = FileSharePermissions(read = true),
        ).toNextcloudApiRequest()

        assertEquals(NextcloudApiMethod.POST, request.method)
        assertEquals("/ocs/v2.php/apps/files_sharing/api/v1/shares", request.relativePath)
        assertEquals(mapOf("format" to "json"), request.queryParameters)
        assertTrue(request.ocsApiRequest)
        assertEquals(256L * 1024L, request.maximumResponseBytes)
        assertEquals(
            "path=%2FPhotos%2FSummer%20trip.jpg&shareType=3&permissions=1",
            request.body?.decodeToString(),
        )
        assertFalse(request.body?.decodeToString().orEmpty().contains("Authorization", ignoreCase = true))
    }

    @Test
    fun userShareRequiresRecipientAndCombinesPermissions() {
        assertFailsWith<IllegalArgumentException> {
            CreateFileShareRequest("a.txt", FileShareTarget.User).toNextcloudApiRequest()
        }
        val request = CreateFileShareRequest(
            path = "a.txt",
            target = FileShareTarget.User,
            shareWith = "ada@example.test",
            permissions = FileSharePermissions(read = true, update = true, reshare = true),
        ).toNextcloudApiRequest()
        assertEquals(19, FileSharePermissions(read = true, update = true, reshare = true).mask)
        assertTrue(request.body!!.decodeToString().contains("shareWith=ada%40example.test"))
    }

    @Test
    fun reusableShareTargetsAndPermissionPresetsMatchSelectedItemContext() {
        assertEquals("Remote user", FileShareTarget.Remote.presentation().label)
        assertEquals("Search email addresses", FileShareTarget.Email.presentation().searchLabel)
        assertTrue(FileShareTarget.Email.requiresRecipient)
        assertFalse(FileShareTarget.PublicLink.requiresRecipient)
        assertEquals(
            FileSharePermissions(read = true, update = true, create = true, delete = true),
            FileSharePermissionPreset.Edit.toPermissions(sourceIsDirectory = true),
        )
        assertEquals(
            FileSharePermissions(read = true),
            FileSharePermissionPreset.View.toPermissions(sourceIsDirectory = false),
        )
    }

    @Test
    fun `share request rejects oversized paths and unsafe recipients before transport`() {
        assertFailsWith<IllegalArgumentException> {
            CreateFileShareRequest(
                path = "a".repeat(4_097),
                target = FileShareTarget.PublicLink,
            ).toNextcloudApiRequest()
        }
        assertFailsWith<IllegalArgumentException> {
            CreateFileShareRequest(
                path = "a.txt",
                target = FileShareTarget.User,
                shareWith = "user\ninjected",
            ).toNextcloudApiRequest()
        }
        assertFailsWith<IllegalArgumentException> {
            CreateFileShareRequest(
                path = "a.txt",
                target = FileShareTarget.Group,
                shareWith = "g".repeat(256),
            ).toNextcloudApiRequest()
        }
    }

    @Test
    fun parsesOcsShareResponse() {
        val response = NextcloudApiResponse(
            status = 200,
            body = """{"ocs":{"meta":{"statuscode":100},"data":{"id":"42","share_type":3,"token":"abc","url":"https://cloud.test/s/abc"}}}"""
                .encodeToByteArray(),
            contentType = "application/json",
            etag = null,
        )

        val share = parseNextcloudFileShareResponse(response)

        assertEquals("42", share.id)
        assertEquals(3, share.shareType)
        assertEquals("abc", share.token)
        assertEquals("https://cloud.test/s/abc", share.url)
    }

    @Test
    fun missingOptionalShareFieldsStayNull() {
        val response = NextcloudApiResponse(
            201,
            """{"ocs":{"meta":{"statuscode":100},"data":{"id":7}}}""".encodeToByteArray(),
            "application/json",
            null,
        )
        val share = parseNextcloudFileShareResponse(response)
        assertEquals("7", share.id)
        assertNull(share.url)
        assertNull(share.token)
    }

    @Test
    fun `share response drops unsafe optional fields and bounds remote failure text`() {
        val share = parseNextcloudFileShareResponse(
            NextcloudApiResponse(
                200,
                """{"ocs":{"meta":{"statuscode":100},"data":{"id":"7","url":"https://cloud.test/s/a\nunsafe","token":""}}}"""
                    .encodeToByteArray(),
                "application/json",
                null,
            ),
        )
        assertNull(share.url)
        assertNull(share.token)

        val failure = assertFailsWith<NextcloudFileOperationException> {
            parseNextcloudFileShareResponse(
                NextcloudApiResponse(
                    200,
                    """{"ocs":{"meta":{"statuscode":403,"message":"${"x".repeat(400)}\nunsafe"},"data":[]}}"""
                        .encodeToByteArray(),
                    "application/json",
                    null,
                ),
            )
        }
        assertEquals(320, failure.message?.length)
        assertFalse(failure.message.orEmpty().contains('\n'))
    }
}
