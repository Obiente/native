package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidTiffPreviewDecoderTest {
    @Test
    fun firstPageInspectionExposesFormatDetailsWithoutDecodingPixels() = runBlocking {
        val fixture = classicRgbTiff(
            bitsPerSample = 16,
            pixels = List(4) { intArrayOf(0xffff, 0x8000, 0x0000) },
        )
        var downloadedBytes = 0

        val inspected = AndroidTiffPreviewDecoder(fixture.size.toLong()) { offset, length ->
            downloadedBytes += length
            fixture.copyOfRange(offset.toInt(), offset.toInt() + length)
        }.inspectFirstPage()

        requireNotNull(inspected)
        assertEquals(2, inspected.width)
        assertEquals(2, inspected.height)
        assertEquals(listOf(16, 16, 16), inspected.bitsPerSample)
        assertEquals(3, inspected.samplesPerPixel)
        assertEquals(1, inspected.compression)
        assertEquals(2, inspected.photometricInterpretation)
        assertFalse(inspected.hasAdditionalPages)
        assertTrue(downloadedBytes <= fixture.size)

        val information = inspected.toMediaInformation()
        assertEquals(
            "Uncompressed",
            information.sections.flatMap { it.fields }
                .single { it.key == "compression" }
                .value,
        )
    }

    @Test
    fun uncompressedSixteenBitRgbPageDecodesThroughBoundedRanges() = runBlocking {
        val fixture = classicRgbTiff(
            bitsPerSample = 16,
            pixels = listOf(
                intArrayOf(0xffff, 0x0000, 0x0000),
                intArrayOf(0x0000, 0xffff, 0x0000),
                intArrayOf(0x0000, 0x0000, 0xffff),
                intArrayOf(0xffff, 0xffff, 0xffff),
            ),
        )
        val reads = mutableListOf<Pair<Long, Int>>()

        val decoded = AndroidTiffPreviewDecoder(fixture.size.toLong()) { offset, length ->
            reads += offset to length
            fixture.copyOfRange(offset.toInt(), offset.toInt() + length)
        }.decodeFirstPage(maximumDimension = 2, maximumPixels = 4)

        requireNotNull(decoded)
        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
        assertEquals(2, decoded.sourceWidth)
        assertEquals(2, decoded.sourceHeight)
        assertFalse(decoded.hasAlphaChannel)
        assertEquals(TiffPreviewEncoding.Jpeg, decoded.previewEncoding())
        assertContentEquals(
            intArrayOf(
                0xffff0000.toInt(),
                0xff00ff00.toInt(),
                0xff0000ff.toInt(),
                0xffffffff.toInt(),
            ),
            decoded.pixels,
        )
        assertTrue(reads.all { (offset, length) -> offset >= 0L && length in 1..fixture.size })
    }

    @Test
    fun fourSampleTiffPreservesItsAlphaChannelForPngEncoding() = runBlocking {
        val fixture = classicRgbTiff(
            bitsPerSample = 8,
            samplesPerPixel = 4,
            pixels = listOf(
                intArrayOf(0xff, 0, 0, 0x40),
                intArrayOf(0, 0xff, 0, 0x80),
                intArrayOf(0, 0, 0xff, 0xc0),
                intArrayOf(0xff, 0xff, 0xff, 0xff),
            ),
        )

        val decoded = AndroidTiffPreviewDecoder(fixture.size.toLong()) { offset, length ->
            fixture.copyOfRange(offset.toInt(), offset.toInt() + length)
        }.decodeFirstPage(maximumDimension = 2, maximumPixels = 4)

        requireNotNull(decoded)
        assertTrue(decoded.hasAlphaChannel)
        assertEquals(TiffPreviewEncoding.Png, decoded.previewEncoding())
        assertEquals(0x40ff0000, decoded.pixels.first())
    }

    @Test
    fun nativeTiffTransportPrefersAuthoritativeDavOverMemories() {
        val file = nativeTiffFile(
            originalAccessAllowed = true,
            davPathAuthoritative = true,
            memoriesRenderAllowed = true,
        )

        val plan = nativeTiffRangeReadPlanOrNull(file, userId = "account-user")

        val dav = plan as NativeTiffRangeReadPlan.FilesDav
        assertEquals("account-user", dav.userId)
        assertEquals(file.path, dav.path)
        assertEquals(file.etag, dav.etag)
        assertEquals(file.size, dav.sourceSize)
    }

    @Test
    fun nativeTiffTransportUsesMemoriesOnlyWhenExplicitlyAllowed() {
        val timelineFile = nativeTiffFile(
            originalAccessAllowed = false,
            davPathAuthoritative = false,
            memoriesRenderAllowed = true,
        )
        val unauthorized = timelineFile.copy(memoriesRenderAllowed = false)

        assertTrue(
            nativeTiffRangeReadPlanOrNull(timelineFile, userId = null) is
                NativeTiffRangeReadPlan.Memories,
        )
        assertNull(nativeTiffRangeReadPlanOrNull(unauthorized, userId = null))
    }

    @Test
    fun nativeTiffTransportRejectsMissingGenerationMetadataAndUnsafeDavPaths() {
        val file = nativeTiffFile(
            originalAccessAllowed = true,
            davPathAuthoritative = true,
            memoriesRenderAllowed = false,
        )

        assertNull(nativeTiffRangeReadPlanOrNull(file.copy(size = null), "account-user"))
        assertNull(nativeTiffRangeReadPlanOrNull(file.copy(etag = null), "account-user"))
        assertNull(
            nativeTiffRangeReadPlanOrNull(
                file.copy(path = "../outside/example.tiff"),
                "account-user",
            ),
        )
    }

    @Test
    fun exactIdResolutionSuppliesMissingMetadataWithoutEscalatingOriginalAccess() {
        val requested = nativeTiffFile(
            originalAccessAllowed = false,
            davPathAuthoritative = false,
            memoriesRenderAllowed = true,
        ).copy(size = null, etag = null)
        val resolved = nativeTiffFile(
            originalAccessAllowed = true,
            davPathAuthoritative = true,
            memoriesRenderAllowed = false,
        )

        val source = resolvedNativeTiffPreviewSourceOrNull(
            requestedFile = requested,
            resolvedFile = resolved,
            userId = "account-user",
        )

        requireNotNull(source)
        assertFalse(source.originalAccessAllowed)
        assertTrue(source.memoriesRenderAllowed)
        assertTrue(
            nativeTiffRangeReadPlanOrNull(source, "account-user") is
                NativeTiffRangeReadPlan.Memories,
        )
        assertNull(
            resolvedNativeTiffPreviewSourceOrNull(
                requestedFile = requested,
                resolvedFile = resolved.copy(fileId = 43L),
                userId = "account-user",
            ),
        )
    }

    @Test
    fun pixelBudgetForcesSamplingBeforeAllocation() = runBlocking {
        val fixture = classicRgbTiff(
            width = 4,
            height = 4,
            bitsPerSample = 8,
            pixels = List(16) { intArrayOf(0xff, 0x80, 0x00) },
        )

        val decoded = AndroidTiffPreviewDecoder(fixture.size.toLong()) { offset, length ->
            fixture.copyOfRange(offset.toInt(), offset.toInt() + length)
        }.decodeFirstPage(maximumDimension = 4, maximumPixels = 4)

        requireNotNull(decoded)
        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
        assertEquals(4, decoded.pixels.size)
    }

    @Test
    fun unsupportedCompressionReturnsNoPreview() = runBlocking {
        val fixture = classicRgbTiff(
            bitsPerSample = 8,
            compression = 5,
            pixels = List(4) { intArrayOf(0, 0, 0) },
        )

        val decoded = AndroidTiffPreviewDecoder(fixture.size.toLong()) { offset, length ->
            fixture.copyOfRange(offset.toInt(), offset.toInt() + length)
        }.decodeFirstPage(maximumDimension = 2, maximumPixels = 4)

        assertNull(decoded)
    }

    @Test
    fun oversizedSourceRowIsRejectedBeforeReadingPixelData() = runBlocking {
        val fixture = classicRgbTiff(
            bitsPerSample = 16,
            pixels = List(4) { intArrayOf(0, 0, 0) },
        ).also { encoded ->
            encoded.putU32(offset = 18, value = 1_500_000)
        }
        var largestRead = 0

        val decoded = AndroidTiffPreviewDecoder(fixture.size.toLong()) { offset, length ->
            largestRead = maxOf(largestRead, length)
            fixture.copyOfRange(offset.toInt(), offset.toInt() + length)
        }.decodeFirstPage(maximumDimension = 1_600)

        assertNull(decoded)
        assertTrue(largestRead < 4 * 1024 * 1024)
    }

    @Test
    fun malformedDirectoryAndStripOffsetsReturnNoPreview() = runBlocking {
        val invalidDirectory = ByteArray(8).also { encoded ->
            encoded[0] = 'I'.code.toByte()
            encoded[1] = 'I'.code.toByte()
            encoded.putU16(2, 42)
            encoded.putU32(4, 64)
        }
        val invalidStrip = classicRgbTiff(
            bitsPerSample = 8,
            pixels = List(4) { intArrayOf(0, 0, 0) },
        ).also { encoded ->
            encoded.putU32(offset = 78, value = encoded.size + 128)
        }

        suspend fun decode(bytes: ByteArray): DecodedTiffPreview? =
            AndroidTiffPreviewDecoder(bytes.size.toLong()) { offset, length ->
                bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
            }.decodeFirstPage(maximumDimension = 2, maximumPixels = 4)

        assertNull(decode(invalidDirectory))
        assertNull(decode(invalidStrip))
    }

    @Test
    fun incompleteRangeReadReturnsNoPreview() = runBlocking {
        val fixture = classicRgbTiff(
            bitsPerSample = 8,
            pixels = List(4) { intArrayOf(0, 0, 0) },
        )

        val decoded = AndroidTiffPreviewDecoder(fixture.size.toLong()) { offset, length ->
            val shortLength = (length - 1).coerceAtLeast(0)
            fixture.copyOfRange(offset.toInt(), offset.toInt() + shortLength)
        }.decodeFirstPage(maximumDimension = 2, maximumPixels = 4)

        assertNull(decoded)
    }

    @Test
    fun aggregateRemoteReadBudgetStopsPathologicalTallSource() = runBlocking {
        val fixture = classicRgbTiff(
            bitsPerSample = 8,
            pixels = List(4) { intArrayOf(0, 0, 0) },
        ).also { encoded ->
            encoded.putU32(offset = 18, value = 1)
            encoded.putU32(offset = 30, value = 200_000_000)
            encoded.putU32(offset = 114, value = 200_000_000)
            encoded.putU32(offset = 126, value = 600_000_000)
        }
        val declaredSourceSize = 600_000_000L + fixture.size.toLong()
        var downloadedBytes = 0L
        var requestCount = 0

        val decoded = AndroidTiffPreviewDecoder(declaredSourceSize) { offset, length ->
            downloadedBytes += length.toLong()
            requestCount += 1
            ByteArray(length).also { bytes ->
                if (offset == 0L) {
                    fixture.copyInto(bytes, endIndex = minOf(fixture.size, bytes.size))
                }
            }
        }.decodeFirstPage(maximumDimension = 4_096)

        assertNull(decoded)
        assertTrue(downloadedBytes <= 96L * 1024L * 1024L)
        assertTrue(requestCount <= 8_192)
    }

    @Test
    fun cacheSeparatesRemoteGenerationsAndSurvivesRestart() {
        val root = Files.createTempDirectory("native-media-preview-cache-test").toFile()
        try {
            val cache = AndroidNativeMediaPreviewCache(root, maximumBytes = 1_024)
            val first = cacheKey(etag = "\"one\"")
            val second = cacheKey(etag = "\"two\"")
            cache.store(first, byteArrayOf(1, 2, 3), cache.accountGeneration(first.accountId))
            cache.store(second, byteArrayOf(4, 5, 6), cache.accountGeneration(second.accountId))

            val restarted = AndroidNativeMediaPreviewCache(root, maximumBytes = 1_024)
            assertContentEquals(byteArrayOf(1, 2, 3), restarted.load(first))
            assertContentEquals(byteArrayOf(4, 5, 6), restarted.load(second))
            assertTrue(
                root.walkTopDown().filter(File::isFile).all { file ->
                    "\"one\"" !in file.name && "\"two\"" !in file.name
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheRemovesInterruptedTemporaryEntriesOnRestart() {
        val root = Files.createTempDirectory("native-media-preview-cache-recovery-test").toFile()
        try {
            val accountDirectory = File(root, "a".repeat(64)).apply { mkdirs() }
            val interrupted = File(accountDirectory, ".interrupted.tmp").apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }

            AndroidNativeMediaPreviewCache(root, maximumBytes = 1_024)

            assertFalse(interrupted.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearingAccountRemovesOnlyThatAccountsPreviews() {
        val root = Files.createTempDirectory("native-media-preview-cache-account-test").toFile()
        try {
            val cache = AndroidNativeMediaPreviewCache(root, maximumBytes = 1_024)
            val first = cacheKey(etag = "\"one\"")
            val second = first.copy(accountId = "b".repeat(64), etag = "\"two\"")
            cache.store(first, byteArrayOf(1, 2, 3), cache.accountGeneration(first.accountId))
            cache.store(second, byteArrayOf(4, 5, 6), cache.accountGeneration(second.accountId))

            cache.clearAccount(first.accountId)

            assertNull(cache.load(first))
            assertContentEquals(byteArrayOf(4, 5, 6), cache.load(second))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearedAccountRejectsPublicationFromAnEarlierGeneration() {
        val root = Files.createTempDirectory("native-media-preview-cache-generation-test").toFile()
        try {
            val cache = AndroidNativeMediaPreviewCache(root, maximumBytes = 1_024)
            val key = cacheKey(etag = "\"one\"")
            val generation = cache.accountGeneration(key.accountId)

            cache.clearAccount(key.accountId)

            assertFalse(cache.store(key, byteArrayOf(1, 2, 3), generation))
            assertNull(cache.load(key))
        } finally {
            root.deleteRecursively()
        }
    }
}

private fun cacheKey(etag: String): NativeMediaPreviewCacheKey = NativeMediaPreviewCacheKey(
    accountId = "a".repeat(64),
    fileId = 42,
    etag = etag,
    maximumDimension = 1_600,
    decoderVersion = "test-v1",
)

private fun classicRgbTiff(
    width: Int = 2,
    height: Int = 2,
    bitsPerSample: Int,
    compression: Int = 1,
    samplesPerPixel: Int = 3,
    pixels: List<IntArray>,
): ByteArray {
    require(width > 0 && height > 0)
    require(bitsPerSample == 8 || bitsPerSample == 16)
    require(pixels.size == width * height)
    require(samplesPerPixel in 3..4)
    require(pixels.all { it.size == samplesPerPixel })
    val entryCount = 11
    val ifdOffset = 8
    val ifdLength = 2 + entryCount * 12 + 4
    val bitsOffset = ifdOffset + ifdLength
    val pixelOffset = bitsOffset + samplesPerPixel * 2
    val bytesPerSample = bitsPerSample / 8
    val pixelBytes = width * height * samplesPerPixel * bytesPerSample
    val encoded = ByteArray(pixelOffset + pixelBytes)
    encoded[0] = 'I'.code.toByte()
    encoded[1] = 'I'.code.toByte()
    encoded.putU16(2, 42)
    encoded.putU32(4, ifdOffset)
    encoded.putU16(ifdOffset, entryCount)
    var entry = ifdOffset + 2
    fun long(tag: Int, value: Int) {
        encoded.putU16(entry, tag)
        encoded.putU16(entry + 2, 4)
        encoded.putU32(entry + 4, 1)
        encoded.putU32(entry + 8, value)
        entry += 12
    }
    fun short(tag: Int, value: Int) {
        encoded.putU16(entry, tag)
        encoded.putU16(entry + 2, 3)
        encoded.putU32(entry + 4, 1)
        encoded.putU16(entry + 8, value)
        entry += 12
    }
    long(256, width)
    long(257, height)
    encoded.putU16(entry, 258)
    encoded.putU16(entry + 2, 3)
    encoded.putU32(entry + 4, samplesPerPixel)
    encoded.putU32(entry + 8, bitsOffset)
    entry += 12
    short(259, compression)
    short(262, 2)
    long(273, pixelOffset)
    short(274, 1)
    short(277, samplesPerPixel)
    long(278, height)
    long(279, pixelBytes)
    short(284, 1)
    encoded.putU32(entry, 0)
    repeat(samplesPerPixel) { index -> encoded.putU16(bitsOffset + index * 2, bitsPerSample) }
    var cursor = pixelOffset
    pixels.forEach { pixel ->
        pixel.forEach { sample ->
            if (bitsPerSample == 8) {
                encoded[cursor] = sample.toByte()
                cursor += 1
            } else {
                encoded.putU16(cursor, sample)
                cursor += 2
            }
        }
    }
    return encoded
}

private fun nativeTiffFile(
    originalAccessAllowed: Boolean,
    davPathAuthoritative: Boolean,
    memoriesRenderAllowed: Boolean,
) = NextcloudFile(
    path = "Photos/Scans/example.tiff",
    name = "example.tiff",
    isDirectory = false,
    mimeType = "image/tiff",
    size = 4_096L,
    lastModified = null,
    fileId = 42L,
    hasPreview = false,
    etag = "\"generation\"",
    originalAccessAllowed = originalAccessAllowed,
    davPathAuthoritative = davPathAuthoritative,
    memoriesRenderAllowed = memoriesRenderAllowed,
)

private fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

private fun ByteArray.putU32(offset: Int, value: Int) {
    repeat(4) { index ->
        this[offset + index] = (value ushr (index * 8)).toByte()
    }
}
