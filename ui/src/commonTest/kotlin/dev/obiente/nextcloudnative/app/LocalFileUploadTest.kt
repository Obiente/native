package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalFileUploadTest {
    @Test
    fun durableUploadMetadataRejectsUnboundedOrPathLikeScopeValues() {
        val scope = DurableUploadScope(feature = "deck-attachment", resourceId = "42")
        val status = DurableUploadStatus(
            id = "12345678-1234-1234-1234-123456789012",
            scope = scope,
            displayName = "notes.txt",
            state = DurableUploadState.Queued,
        )

        assertEquals(scope, status.scope)
        assertFailsWith<IllegalArgumentException> {
            DurableUploadScope(feature = "deck/attachment", resourceId = "42")
        }
        assertFailsWith<IllegalArgumentException> {
            status.copy(message = "x".repeat(MAX_DURABLE_UPLOAD_MESSAGE_CHARACTERS + 1))
        }
        assertEquals(
            DurableUploadState.OutcomeUnknown,
            DurableUploadState.Uploading.afterProcessRecovery(),
        )
        assertEquals(
            DurableUploadState.Queued,
            DurableUploadState.Queued.afterProcessRecovery(),
        )
    }

    @Test
    fun `filename sanitization removes paths and header controls`() {
        assertEquals(
            "report.pdf",
            sanitizeUploadFilename("C:\\private\\report.pdf\r\n"),
        )
        assertEquals("upload.bin", sanitizeUploadFilename("\u0000\u0001"))
    }

    @Test
    fun `picker filters are bounded and understand type wildcards`() {
        assertEquals(
            listOf("image/*", "application/pdf"),
            requireSafeUploadPickerRequest(
                listOf(" Image/* ", "application/pdf", "image/*"),
                maximumBytes = 1024L,
            ),
        )
        assertTrue(isAcceptedUploadMimeType("image/png", listOf("image/*")))
        assertFalse(isAcceptedUploadMimeType("text/plain", listOf("image/*")))
        assertFailsWith<IllegalArgumentException> {
            requireSafeUploadPickerRequest(listOf("*/json"), 1024L)
        }

        NextcloudMultipartUploadRequest(
            method = NextcloudApiMethod.POST,
            relativePath = "/index.php/apps/example/api/upload",
            file = fixtureFile(sizeBytes = 12L * 1024L * 1024L * 1024L),
        ).requireSafe()
    }

    @Test
    fun `multipart envelope is rfc7578 framed and preserves utf8 filename`() {
        val file = fixtureFile(
            displayName = "r\u00e9sum\u00e9.pdf",
            mimeType = "application/pdf",
            sizeBytes = 3L,
        )
        val request = NextcloudMultipartUploadRequest(
            method = NextcloudApiMethod.POST,
            relativePath = "/index.php/apps/example/api/upload",
            queryParameters = mapOf("format" to "json"),
            file = file,
            textFields = listOf(MultipartTextField("type", "file")),
            ocsApiRequest = true,
            maximumFileBytes = 32L,
        )
        val prepared = prepareMultipartUpload(request, TEST_BOUNDARY)
        val body = stream(prepared, byteArrayOf(1, 2, 3))
        val text = body.decodeToString()

        assertEquals("multipart/form-data; boundary=$TEST_BOUNDARY", prepared.contentType)
        assertEquals(body.size.toLong(), prepared.contentLength)
        assertTrue(text.startsWith("--$TEST_BOUNDARY\r\n"))
        assertTrue(text.contains("name=\"type\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nfile\r\n"))
        assertTrue(text.contains("filename=\"r_sum_.pdf\""))
        assertTrue(text.contains("filename*=UTF-8''r%C3%A9sum%C3%A9.pdf"))
        assertTrue(text.endsWith("\r\n--$TEST_BOUNDARY--\r\n"))
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            body.copyOfRange(prepared.prefix.size, prepared.prefix.size + 3),
        )
    }

    @Test
    fun `streaming rejects a selected file that changed size`() {
        val request = NextcloudMultipartUploadRequest(
            method = NextcloudApiMethod.POST,
            relativePath = "/index.php/apps/example/api/upload",
            file = fixtureFile(sizeBytes = 2L),
            maximumFileBytes = 4L,
        )
        val prepared = prepareMultipartUpload(request, TEST_BOUNDARY)

        assertFailsWith<IllegalArgumentException> {
            stream(prepared, byteArrayOf(1, 2, 3))
        }
        assertFailsWith<IllegalArgumentException> {
            stream(prepared, byteArrayOf(1))
        }
    }

    @Test
    fun `unknown stream length is still bounded while writing`() {
        val request = NextcloudMultipartUploadRequest(
            method = NextcloudApiMethod.PUT,
            relativePath = "/index.php/apps/example/api/upload",
            file = fixtureFile(sizeBytes = null),
            maximumFileBytes = 2L,
        )
        val prepared = prepareMultipartUpload(request, TEST_BOUNDARY)

        assertEquals(null, prepared.contentLength)
        assertFailsWith<IllegalArgumentException> {
            stream(prepared, byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun `multipart request rejects traversal invalid methods and oversized metadata`() {
        assertFailsWith<IllegalArgumentException> {
            NextcloudMultipartUploadRequest(
                method = NextcloudApiMethod.GET,
                relativePath = "/index.php/apps/example/api/upload",
                file = fixtureFile(),
            ).requireSafe()
        }
        assertFailsWith<IllegalArgumentException> {
            NextcloudMultipartUploadRequest(
                method = NextcloudApiMethod.POST,
                relativePath = "/index.php/apps/example/../admin",
                file = fixtureFile(),
            ).requireSafe()
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            NextcloudMultipartUploadRequest(
                method = NextcloudApiMethod.POST,
                relativePath = "/index.php/apps/example/api/upload",
                file = fixtureFile(sizeBytes = 3L),
                maximumFileBytes = 2L,
            ).requireSafe()
        }
        assertIs<IllegalArgumentException>(failure)
    }

    private fun fixtureFile(
        displayName: String = "fixture.bin",
        mimeType: String? = "application/octet-stream",
        sizeBytes: Long? = 1L,
    ): LocalUploadFile = localUploadFile(
        selectionId = "selection-1234567",
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
    )

    private fun stream(
        prepared: PreparedMultipartUpload,
        source: ByteArray,
    ): ByteArray {
        var cursor = 0
        val chunks = mutableListOf<ByteArray>()
        writePreparedMultipartUpload(
            upload = prepared,
            readFile = { buffer ->
                if (cursor >= source.size) {
                    -1
                } else {
                    val count = minOf(buffer.size, source.size - cursor)
                    source.copyInto(buffer, destinationOffset = 0, startIndex = cursor, endIndex = cursor + count)
                    cursor += count
                    count
                }
            },
            write = { bytes, offset, count ->
                chunks += bytes.copyOfRange(offset, offset + count)
            },
        )
        return chunks.fold(ByteArray(0)) { bytes, chunk -> bytes + chunk }
    }

    private companion object {
        const val TEST_BOUNDARY = "boundary-1234567"
    }
}
