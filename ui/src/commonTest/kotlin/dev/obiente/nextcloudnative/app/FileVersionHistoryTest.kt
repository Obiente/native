package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileVersionHistoryTest {
    @Test
    fun `availability uses enabled inventory and live DAV independently of optional capabilities`() {
        assertEquals(
            FileVersionAvailability.Available,
            fileVersionAvailability(listOf("files", "files_versions"), davReachable = true),
        )
        assertEquals(
            FileVersionAvailability.DavUnavailable,
            fileVersionAvailability(listOf("files_versions"), davReachable = false),
        )
        assertEquals(
            FileVersionAvailability.AppDisabled,
            fileVersionAvailability(listOf("files", "files_trashbin"), davReachable = true),
        )
    }

    @Test
    fun `plans official bounded versions DAV discovery`() {
        val request = fileVersionHistoryRequest("person@example.test", 42L)
        val body = requireNotNull(request.body).decodeToString()

        assertEquals("PROPFIND", request.method)
        assertEquals("/remote.php/dav/versions/person%40example.test/versions/42", request.relativePath)
        assertEquals(1, request.depth)
        assertEquals("application/xml; charset=utf-8", request.contentType)
        assertTrue("<d:getcontentlength />" in body)
        assertTrue("<nc:version-author />" in body)
        assertTrue("<nc:version-label />" in body)
        assertFalse("Authorization" in body)
        assertTrue(request.maximumResponseBytes in 1..(4L * 1024L * 1024L))
        assertEquals(
            "/remote.php/dav/versions/%C3%BCser%2Fname/versions/42",
            fileVersionHistoryRequest("üser/name", 42L).relativePath,
        )
    }

    @Test
    fun `normalizes direct children newest first and ignores collection`() {
        val base = "/remote.php/dav/versions/opaque-user/versions/42"
        val history = normalizeFileVersionHistory(
            userId = "opaque-user",
            fileId = 42L,
            records = listOf(
                record(base),
                record(
                    "$base/1720000000",
                    size = "12",
                    modified = "Wed, 03 Jul 2024 09:46:40 GMT",
                    etag = "\"old\"",
                    author = "editor-a",
                ),
                record("$base/1730000000", size = "24", label = "Before review"),
            ),
        )

        assertEquals(42L, history.fileId)
        assertEquals(listOf("1730000000", "1720000000"), history.versions.map(NextcloudFileVersion::id))
        assertEquals(24L, history.versions.first().sizeBytes)
        assertEquals("Before review", history.versions.first().label)
        assertNull(history.versions.first().author)
        assertEquals("\"old\"", history.versions.last().etag)
    }

    @Test
    fun `sorts opaque numeric generations without overflowing Long`() {
        val base = "/remote.php/dav/versions/person%40example.test/versions/42"
        val history = normalizeFileVersionHistory(
            "person@example.test",
            42L,
            listOf(
                record("$base/99999999999999999999999999999999"),
                record("$base/1730000000"),
                record(base.replace("%40", "%40")),
            ),
        )

        assertEquals(
            listOf("99999999999999999999999999999999", "1730000000"),
            history.versions.map(NextcloudFileVersion::id),
        )
    }

    @Test
    fun `accepts percent escape case differences without decoding reserved bytes`() {
        val base = "/remote.php/dav/versions/%C3%BCser/versions/42"
        val history = normalizeFileVersionHistory(
            "üser",
            42L,
            listOf(record("/remote.php/dav/versions/%c3%bcser/versions/42/1730000000")),
        )

        assertEquals("1730000000", history.versions.single().id)
        assertFailsWith<IllegalArgumentException> {
            normalizeFileVersionHistory(
                "üser/name",
                42L,
                listOf(record("$base/1730000000")),
            )
        }
    }

    @Test
    fun `historical reads are bounded and range aware`() {
        val full = fileVersionContentRequest("opaque-user", 42L, "1730000000")
        val range = fileVersionContentRequest(
            "opaque-user",
            42L,
            "1730000000",
            FileVersionByteRange(start = 1_024L, endInclusive = 2_047L),
        )

        assertEquals("GET", full.method)
        assertEquals("/remote.php/dav/versions/opaque-user/versions/42/1730000000", full.relativePath)
        assertTrue(full.maximumResponseBytes <= 16L * 1024L * 1024L)
        assertEquals(mapOf("Range" to "bytes=1024-2047"), range.headers)
        assertEquals(1_024L, range.maximumResponseBytes)

        val export = boundedFileVersionContentRequest(
            "opaque-user",
            42L,
            "1730000000",
            MAX_FILE_VERSION_IN_MEMORY_BYTES,
        )
        assertEquals(
            "bytes=0-${MAX_FILE_VERSION_IN_MEMORY_BYTES - 1L}",
            export.headers.getValue("Range"),
        )
        assertEquals(MAX_FILE_VERSION_IN_MEMORY_BYTES, export.maximumResponseBytes)
        val knownSmall = boundedFileVersionContentRequest(
            "opaque-user",
            42L,
            "1730000000",
            1_024L,
            expectedSizeBytes = 128L,
        )
        assertTrue(knownSmall.headers.isEmpty())
        assertEquals(1_024L, knownSmall.maximumResponseBytes)
        assertFailsWith<IllegalArgumentException> {
            boundedFileVersionContentRequest(
                "opaque-user",
                42L,
                "1730000000",
                MAX_FILE_VERSION_IN_MEMORY_BYTES + 1L,
            )
        }
    }

    @Test
    fun `follow-up content reads preserve current file identity and access policy`() {
        val file = file(fileId = 42L)
        val version = version(fileId = 42L)

        assertEquals(42L, requireMatchingFileVersion(file, version))
        assertFailsWith<IllegalArgumentException> {
            requireMatchingFileVersion(file, version(fileId = 41L))
        }
        assertFailsWith<IllegalArgumentException> {
            requireMatchingFileVersion(file.copy(originalAccessAllowed = false), version)
        }
        assertFailsWith<IllegalArgumentException> {
            requireMatchingFileVersion(file.copy(isDirectory = true), version)
        }
    }

    @Test
    fun `plans official same-account DAV restore target`() {
        val file = file(fileId = 42L)
        val version = version(fileId = 42L)

        val request = fileVersionRestoreRequest("person@example.test", file, version)

        assertEquals("MOVE", request.method)
        assertEquals(
            "/remote.php/dav/versions/person%40example.test/versions/42/1730000000",
            request.relativePath,
        )
        assertEquals(
            "/remote.php/dav/versions/person%40example.test/restore/target",
            request.destinationRelativePath,
        )
        assertEquals(mapOf("Overwrite" to "T"), request.headers)
        assertTrue(request.maximumResponseBytes in 1L..(64L * 1024L))
    }

    @Test
    fun `restore requires write permission original access and matching identity`() {
        val writable = file(fileId = 42L)
        val version = version(fileId = 42L)

        assertEquals(42L, requireRestorableFileVersion(writable, version))
        assertFailsWith<IllegalArgumentException> {
            fileVersionRestoreRequest("person", writable.copy(permissions = "RG"), version)
        }
        assertFailsWith<IllegalArgumentException> {
            fileVersionRestoreRequest("person", writable.copy(permissions = null), version)
        }
        assertFailsWith<IllegalArgumentException> {
            fileVersionRestoreRequest("person", writable.copy(originalAccessAllowed = false), version)
        }
        assertFailsWith<IllegalArgumentException> {
            fileVersionRestoreRequest("person", writable, version(fileId = 41L))
        }
    }

    @Test
    fun `historical copies keep extension and have a distinct bounded name`() {
        assertEquals(
            "draft-version-1730000000.md",
            historicalFileCopyName("draft.md", "1730000000"),
        )
        assertEquals(
            "archive-version-1730000000",
            historicalFileCopyName("../archive", "1730000000"),
        )
        assertFailsWith<IllegalArgumentException> {
            historicalFileCopyName("draft.md", "../current")
        }
    }

    @Test
    fun `preview and export affordances obey their independent byte bounds`() {
        val small = version(fileId = 42L, sizeBytes = 12L)
        val unknown = version(fileId = 42L, id = "1720000000", sizeBytes = null)
        val capability = ExternalFileHandoffSupport.Available(
            ExternalFileHandoffCapability(
                supportedActions = setOf(ExternalFileHandoffAction.Share),
                maximumInMemoryFileBytes = MAX_FILE_VERSION_IN_MEMORY_BYTES,
            ),
        )

        assertEquals(12L, versionPreviewByteLimit(small))
        assertEquals(MAX_FILE_VERSION_PREVIEW_BYTES, versionPreviewByteLimit(unknown))
        assertTrue(canExportFileVersion(capability, small))
        assertTrue(
            canExportFileVersion(
                capability,
                version(
                    fileId = 42L,
                    id = "1710000000",
                    sizeBytes = MAX_FILE_VERSION_IN_MEMORY_BYTES + 1L,
                ),
            ),
        )
        assertFalse(canExportFileVersion(ExternalFileHandoffSupport.Unsupported("No export"), small))
        assertTrue(canRestoreFileVersion(file(fileId = 42L), small))
        assertFalse(canRestoreFileVersion(file(fileId = 42L).copy(permissions = "RG"), small))
        assertFalse(canRestoreFileVersion(file(fileId = 41L), small))
    }

    @Test
    fun `rejects cross-file nested and non-generation hrefs`() {
        val badHrefs = listOf(
            "/remote.php/dav/versions/opaque-user/versions/41/1730000000",
            "/remote.php/dav/versions/opaque-user/versions/42/1730000000/child",
            "/remote.php/dav/versions/opaque-user/versions/42/%2e%2e",
            "/remote.php/dav/versions/opaque-user/versions/42/current",
        )

        badHrefs.forEach { href ->
            assertFailsWith<IllegalArgumentException> {
                normalizeFileVersionHistory("opaque-user", 42L, listOf(record(href)))
            }
        }
    }

    @Test
    fun `rejects malformed duplicate and oversized metadata`() {
        val base = "/remote.php/dav/versions/opaque-user/versions/42"
        assertFailsWith<IllegalStateException> {
            normalizeFileVersionHistory(
                "opaque-user",
                42L,
                listOf(record("$base/1730000000", size = "not-a-size")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeFileVersionHistory(
                "opaque-user",
                42L,
                listOf(record("$base/1730000000"), record("$base/1730000000")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeFileVersionHistory(
                "opaque-user",
                42L,
                listOf(record("$base/1730000000", label = "x".repeat(513))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FileVersionByteRange(0L, MAX_FILE_VERSION_IN_MEMORY_BYTES)
        }
    }

    private fun record(
        href: String,
        size: String? = null,
        modified: String? = null,
        etag: String? = null,
        author: String? = null,
        label: String? = null,
    ) = FileVersionDavRecord(
        href = href,
        contentLength = size,
        lastModified = modified,
        etag = etag,
        author = author,
        label = label,
    )

    private fun file(fileId: Long) = NextcloudFile(
        path = "Documents/draft.md",
        name = "draft.md",
        isDirectory = false,
        mimeType = "text/markdown",
        size = 128L,
        lastModified = null,
        fileId = fileId,
        hasPreview = false,
        etag = "\"current\"",
        permissions = "RGDNVW",
    )

    private fun version(
        fileId: Long,
        id: String = "1730000000",
        sizeBytes: Long? = 128L,
    ) = NextcloudFileVersion(
        fileId = fileId,
        id = id,
        sizeBytes = sizeBytes,
        lastModified = null,
        etag = "\"history\"",
        author = null,
        label = null,
    )
}
