package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RawPhotoPreviewTest {
    @Test
    fun memoriesRenderIsUsedWhenCoreCannotPreviewStandaloneRaw() = runBlocking {
        val raw = rawFile(hasPreview = true)
        var rangeReads = 0

        val payload = loadMediaDisplayPayload(
            file = raw,
            loadCorePreview = { "<html>preview failed</html>".encodeToByteArray() },
            loadMemoriesRawRender = { jpegFixture() },
            loadFileRange = { _, _ ->
                rangeReads += 1
                error("RAF range fallback should not be needed.")
            },
        )

        assertEquals(MediaDisplayPayloadKind.MemoriesRawRender, payload.kind)
        assertEquals(0, rangeReads)
    }

    @Test
    fun rafUsesItsOwnEmbeddedPreviewWithoutSiblingJpegOrServerPreview() = runBlocking {
        val embedded = jpegFixture()
        val embeddedOffset = 4_096L
        val raw = rawFile(hasPreview = false, size = embeddedOffset + embedded.size)
        val header = rafHeader(embeddedOffset, embedded.size)
        var coreCalls = 0
        val requestedRanges = mutableListOf<Pair<Long, Int>>()

        val payload = loadMediaDisplayPayload(
            file = raw,
            loadCorePreview = {
                coreCalls += 1
                error("Core preview must be skipped when the server reports no preview.")
            },
            loadMemoriesRawRender = { error("Memories cannot decode this file.") },
            loadFileRange = { offset, length ->
                requestedRanges += offset to length
                when (offset) {
                    0L -> header
                    embeddedOffset -> embedded
                    else -> error("Unexpected RAF range.")
                }
            },
        )

        assertEquals(MediaDisplayPayloadKind.EmbeddedCameraPreview, payload.kind)
        assertEquals(embedded.toList(), payload.bytes.toList())
        assertEquals(0, coreCalls)
        assertEquals(listOf(0L to 0x5C, embeddedOffset to embedded.size), requestedRanges)
    }

    @Test
    fun malformedOrOutOfBoundsRafDirectoryIsRejectedBeforePayloadRead() = runBlocking {
        val raw = rawFile(hasPreview = false, size = 4_200L)
        val header = rafHeader(offset = 4_096L, length = 1_024)
        var reads = 0

        assertFailsWith<IllegalStateException> {
            loadMediaDisplayPayload(
                file = raw,
                loadCorePreview = { error("No core preview.") },
                loadMemoriesRawRender = { error("No Memories render.") },
                loadFileRange = { _, _ ->
                    reads += 1
                    header
                },
            )
        }

        assertEquals(1, reads)
        assertNull(parseFujiRafEmbeddedPreview(header, fileSize = 4_200L))
    }

    @Test
    fun cancellationIsNeverConvertedIntoAnotherPreviewFallback() = runBlocking {
        val raw = rawFile(hasPreview = true)
        val candidates = planMediaSources(listOf(raw), raw).previewCandidates

        assertFailsWith<CancellationException> {
            loadFirstUsableMediaPreviewSource(
                candidates = candidates,
                load = { throw CancellationException("viewer closed") },
                decode = { "decoded" },
            )
        }
        Unit
    }

    @Test
    fun rafDirectoryRejectsWrongSignatureAndOversizedPreview() {
        val wrongSignature = rafHeader(offset = 4_096L, length = 1_024).also { it[0] = 0 }
        val oversized = rafHeader(
            offset = 4_096L,
            length = MAX_RAW_EMBEDDED_PREVIEW_BYTES.toLong() + 1L,
        )

        assertNull(parseFujiRafEmbeddedPreview(wrongSignature, fileSize = 8_192L))
        assertNull(
            parseFujiRafEmbeddedPreview(
                oversized,
                fileSize = 4_096L + MAX_RAW_EMBEDDED_PREVIEW_BYTES + 1L,
            ),
        )
    }

    @Test
    fun standaloneRawCanUseEmbeddedPreviewWithoutAFileId() {
        val raw = rawFile(hasPreview = false).copy(fileId = null)

        val candidates = planMediaSources(listOf(raw), raw).previewCandidates

        assertEquals(listOf(raw), candidates.map(MediaSourceChoice::file))
    }

    @Test
    fun embeddedCameraPreviewAppliesExifWhileServerRendersStayUpright() {
        assertEquals(
            EncodedImageOrientationPolicy.ApplyExif,
            MediaDisplayPayloadKind.EmbeddedCameraPreview.orientationPolicy(),
        )
        assertEquals(
            EncodedImageOrientationPolicy.PixelsAlreadyUpright,
            MediaDisplayPayloadKind.ServerPreview.orientationPolicy(),
        )
        assertEquals(
            EncodedImageOrientationPolicy.PixelsAlreadyUpright,
            MediaDisplayPayloadKind.MemoriesRawRender.orientationPolicy(),
        )
    }

    @Test
    fun contentRangeMustIdentifyTheExactRequestedBytes() {
        assertEquals(true, isExactHttpByteContentRange("bytes 4096-8191/64000000", 4_096L, 8_191L))
        assertEquals(true, isExactHttpByteContentRange("Bytes 4096-8191/64000000", 4_096L, 8_191L))
        assertEquals(true, isExactHttpByteContentRange("bytes 4096-8191/*", 4_096L, 8_191L))
        assertEquals(false, isExactHttpByteContentRange("bytes 0-4095/64000000", 4_096L, 8_191L))
        assertEquals(false, isExactHttpByteContentRange("bytes 4096-8191/8191", 4_096L, 8_191L))
        assertEquals(false, isExactHttpByteContentRange("bytes */64000000", 4_096L, 8_191L))
        assertEquals(false, isExactHttpByteContentRange(null, 4_096L, 8_191L))
    }

    @Test
    fun memoriesPreviewRequestUsesTheRawDisplayBound() {
        val request = memoriesPhotoDecodableApiRequest(
            fileId = 1_001L,
            etag = "raw-etag",
            maximumResponseBytes = MAX_RAW_DISPLAY_PREVIEW_BYTES.toLong(),
        )

        assertEquals(MAX_RAW_DISPLAY_PREVIEW_BYTES.toLong(), request.maximumResponseBytes)
    }

    @Test
    fun displayLabelsDistinguishGeneratedAndEmbeddedRawRepresentations() {
        val raw = rawFile(hasPreview = false)
        val source = planMediaSources(listOf(raw), raw).previewCandidates.single()

        assertEquals(
            "Generated RAW render",
            describeMediaDisplaySource(
                selected = source,
                displayed = source,
                fullQuality = false,
                payloadKind = MediaDisplayPayloadKind.MemoriesRawRender,
            ),
        )
        assertEquals(
            "RAW embedded camera preview",
            describeMediaDisplaySource(
                selected = source,
                displayed = source,
                fullQuality = false,
                payloadKind = MediaDisplayPayloadKind.EmbeddedCameraPreview,
            ),
        )
    }

    private fun rawFile(
        hasPreview: Boolean,
        size: Long = 64L * 1024L * 1024L,
    ) = NextcloudFile(
        path = "Photos/Samples/SAMPLE0001.RAF",
        name = "SAMPLE0001.RAF",
        isDirectory = false,
        mimeType = "image/x-fuji-raf",
        size = size,
        lastModified = null,
        fileId = 1_001L,
        hasPreview = hasPreview,
        etag = "raf-etag",
    )

    private fun jpegFixture(): ByteArray = byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0xE0.toByte(),
        0,
        0,
        0,
        0,
    )

    private fun rafHeader(offset: Long, length: Int): ByteArray = rafHeader(offset, length.toLong())

    private fun rafHeader(offset: Long, length: Long): ByteArray = ByteArray(0x5C).also { bytes ->
        "FUJIFILMCCD-RAW ".encodeToByteArray().copyInto(bytes)
        bytes.writeUnsignedBigEndianInt(0x54, offset)
        bytes.writeUnsignedBigEndianInt(0x58, length)
    }

    private fun ByteArray.writeUnsignedBigEndianInt(offset: Int, value: Long) {
        for (index in 0 until Int.SIZE_BYTES) {
            this[offset + index] = (value shr ((Int.SIZE_BYTES - index - 1) * Byte.SIZE_BITS)).toByte()
        }
    }
}
