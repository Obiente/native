package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalFileHandoffTest {
    private val capability = ExternalFileHandoffCapability(
        supportedActions = ExternalFileHandoffAction.entries.toSet(),
        maximumFileBytes = MAX_EXTERNAL_FILE_HANDOFF_BYTES,
    )

    @Test
    fun `directory handoff is rejected without a download`() {
        val rejection = validateExternalFileHandoff(
            file(name = "Folder", directory = true),
            ExternalFileHandoffAction.Share,
            capability,
        )

        assertEquals(ExternalFileHandoffRejection.Directory, rejection?.reason)
    }

    @Test
    fun `known oversized files are rejected before download`() {
        val rejection = validateExternalFileHandoff(
            file(name = "video.mov", size = MAX_EXTERNAL_FILE_HANDOFF_BYTES + 1L),
            ExternalFileHandoffAction.OpenWith,
            capability,
        )

        assertEquals(ExternalFileHandoffRejection.FileTooLarge, rejection?.reason)
        assertTrue(requireNotNull(rejection).message.contains("64 MiB"))
    }

    @Test
    fun `unknown file size remains eligible for bounded download`() {
        assertNull(
            validateExternalFileHandoff(
                file(name = "report.pdf", size = null),
                ExternalFileHandoffAction.OpenWith,
                capability,
            ),
        )
    }

    @Test
    fun `unsupported actions return a typed rejection`() {
        val shareOnly = ExternalFileHandoffCapability(setOf(ExternalFileHandoffAction.Share), 1024L)
        val rejection = validateExternalFileHandoff(
            file(name = "report.pdf", size = 12L),
            ExternalFileHandoffAction.OpenWith,
            shareOnly,
        )

        assertIs<ExternalFileHandoffResult.Rejected>(rejection)
        assertEquals(ExternalFileHandoffRejection.UnsupportedAction, rejection.reason)
    }

    @Test
    fun `handoff requires a selected readable DAV generation`() {
        val missingVersion = validateExternalFileHandoff(
            file(name = "report.pdf").copy(etag = null),
            ExternalFileHandoffAction.OpenWith,
            capability,
        )
        val restricted = validateExternalFileHandoff(
            file(name = "shared.pdf").copy(originalAccessAllowed = false),
            ExternalFileHandoffAction.OpenWith,
            capability,
        )

        assertEquals(ExternalFileHandoffRejection.MissingVersion, missingVersion?.reason)
        assertEquals(ExternalFileHandoffRejection.OriginalAccessRestricted, restricted?.reason)
    }

    @Test
    fun `downloaded generation must match selected etag before staging`() {
        val selected = file(name = "report.pdf").copy(etag = "\"v2\"")
        val verified = NextcloudFileContent(
            bytes = byteArrayOf(1, 2, 3),
            mimeType = "application/pdf",
            etag = "\"v2\"",
        )
        val changed = verified.copy(etag = "\"v3\"")
        val unversioned = verified.copy(etag = null)

        assertNull(validateDownloadedExternalFile(selected, verified, maximumBytes = 10))
        assertEquals(
            ExternalFileHandoffRejection.VersionChanged,
            validateDownloadedExternalFile(selected, changed, maximumBytes = 10)?.reason,
        )
        assertEquals(
            ExternalFileHandoffRejection.VersionChanged,
            validateDownloadedExternalFile(selected, unversioned, maximumBytes = 10)?.reason,
        )
    }

    @Test
    fun `filename sanitizer prevents traversal and preserves a useful extension`() {
        assertEquals("secret_photo.jpg", sanitizeExternalFileName("../../secret:photo.jpg"))
        assertEquals("invoice_cod.exe.pdf", sanitizeExternalFileName("invoice\u202Ecod.exe.pdf"))
        assertEquals("nextcloud-file", sanitizeExternalFileName("../.."))
        val longName = sanitizeExternalFileName("a".repeat(300) + ".raw")
        assertTrue(longName.length <= 180)
        assertTrue(longName.endsWith(".raw"))
        assertTrue('/' !in longName && '\\' !in longName)
    }

    @Test
    fun `mime sanitizer removes parameters and rejects intent injection`() {
        assertEquals("image/jpeg", sanitizeExternalMimeType(" IMAGE/JPEG; charset=binary "))
        assertEquals("application/octet-stream", sanitizeExternalMimeType("text/plain\nmalicious: value"))
        assertEquals("application/octet-stream", sanitizeExternalMimeType("file:///tmp/a"))
        assertEquals("application/octet-stream", sanitizeExternalMimeType(null))
    }

    private fun file(
        name: String,
        directory: Boolean = false,
        size: Long? = 1L,
    ): NextcloudFile = NextcloudFile(
        path = name,
        name = name,
        isDirectory = directory,
        mimeType = null,
        size = size,
        lastModified = null,
        fileId = null,
        hasPreview = false,
        etag = "\"v1\"",
    )
}
