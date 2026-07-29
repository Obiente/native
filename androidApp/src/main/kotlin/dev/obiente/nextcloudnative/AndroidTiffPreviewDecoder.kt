package dev.obiente.nextcloudnative

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class AndroidTiffPreviewDecoder(
    private val sourceSize: Long,
    readRange: suspend (offset: Long, length: Int) -> ByteArray,
) {
    private val source = BufferedTiffRangeSource(sourceSize, readRange)

    suspend fun inspectFirstPage(): AndroidTiffInformation? {
        val header = source.read(0L, TIFF_HEADER_BYTES) ?: return null
        val byteOrder = TiffByteOrder.fromHeader(header) ?: return null
        if (header.u16(2, byteOrder) != CLASSIC_TIFF_MAGIC) return null
        val firstIfdOffset = header.u32(4, byteOrder) ?: return null
        val directory = readDirectory(firstIfdOffset, byteOrder) ?: return null
        return AndroidTiffInformation(
            width = directory.width,
            height = directory.height,
            bitsPerSample = directory.bitsPerSample,
            samplesPerPixel = directory.samplesPerPixel,
            compression = directory.compression,
            photometricInterpretation = directory.photometric,
            orientation = directory.orientation,
            planarConfiguration = directory.planarConfiguration,
            tiled = directory.tiled,
            hasAdditionalPages = directory.pageCount > 1,
        )
    }

    suspend fun decodeFirstPage(
        maximumDimension: Int,
        maximumPixels: Long = MAXIMUM_TIFF_PREVIEW_PIXELS,
    ): DecodedTiffPreview? {
        require(maximumDimension > 0)
        require(maximumPixels > 0L)
        val header = source.read(0L, TIFF_HEADER_BYTES) ?: return null
        val byteOrder = TiffByteOrder.fromHeader(header) ?: return null
        if (header.u16(2, byteOrder) != CLASSIC_TIFF_MAGIC) return null
        val firstIfdOffset = header.u32(4, byteOrder) ?: return null
        val directory = readDirectory(firstIfdOffset, byteOrder) ?: return null
        if (!directory.isSupported()) return null

        val sampling = tiffSamplingPlan(
            sourceWidth = directory.width,
            sourceHeight = directory.height,
            maximumDimension = maximumDimension,
            maximumPixels = maximumPixels,
        )
        val orientedWidth = orientedWidth(
            directory.orientation,
            sampling.sampledWidth,
            sampling.sampledHeight,
        )
        val orientedHeight = orientedHeight(
            directory.orientation,
            sampling.sampledWidth,
            sampling.sampledHeight,
        )
        val outputPixels = orientedWidth.toLong() * orientedHeight.toLong()
        if (
            orientedWidth <= 0 ||
            orientedHeight <= 0 ||
            outputPixels <= 0L ||
            outputPixels > maximumPixels
        ) {
            return null
        }

        val rowBytes = directory.rowByteCount() ?: return null
        val pixels = IntArray(outputPixels.toInt())
        var sourceY = 0
        while (sourceY < directory.height) {
            if (sourceY % TIFF_CANCELLATION_ROW_INTERVAL == 0) {
                currentCoroutineContext().ensureActive()
            }
            val stripIndex = sourceY / directory.rowsPerStrip
            val rowWithinStrip = sourceY % directory.rowsPerStrip
            val stripOffset = directory.stripOffsets.getOrNull(stripIndex) ?: return null
            val stripBytes = directory.stripByteCounts.getOrNull(stripIndex) ?: return null
            val rowOffsetWithinStrip = rowWithinStrip.toLong() * rowBytes.toLong()
            if (rowOffsetWithinStrip + rowBytes.toLong() > stripBytes) return null
            val sourceOffset = safeAdd(stripOffset, rowOffsetWithinStrip) ?: return null
            val row = source.read(sourceOffset, rowBytes) ?: return null
            val sampledY = sourceY / sampling.sampleSize
            var sourceX = 0
            while (sourceX < directory.width) {
                val sampledX = sourceX / sampling.sampleSize
                val color = directory.readPixel(row, sourceX, byteOrder) ?: return null
                val oriented = orientPoint(
                    orientation = directory.orientation,
                    x = sampledX,
                    y = sampledY,
                    width = sampling.sampledWidth,
                    height = sampling.sampledHeight,
                )
                pixels[oriented.second * orientedWidth + oriented.first] = color
                sourceX += sampling.sampleSize
            }
            sourceY += sampling.sampleSize
        }

        return DecodedTiffPreview(
            pixels = pixels,
            width = orientedWidth,
            height = orientedHeight,
            sourceWidth = orientedWidth(directory.orientation, directory.width, directory.height),
            sourceHeight = orientedHeight(directory.orientation, directory.width, directory.height),
            pageCount = directory.pageCount,
            hasAlphaChannel = directory.samplesPerPixel in setOf(2, 4),
        )
    }

    private suspend fun readDirectory(offset: Long, byteOrder: TiffByteOrder): TiffDirectory? {
        if (offset < TIFF_HEADER_BYTES || offset >= sourceSize) return null
        val countBytes = source.read(offset, TIFF_DIRECTORY_COUNT_BYTES) ?: return null
        val entryCount = countBytes.u16(0, byteOrder) ?: return null
        if (entryCount !in 1..MAXIMUM_TIFF_DIRECTORY_ENTRIES) return null
        val directoryBytes = Math.addExact(
            TIFF_DIRECTORY_COUNT_BYTES.toLong(),
            Math.addExact(
                entryCount.toLong() * TIFF_DIRECTORY_ENTRY_BYTES.toLong(),
                TIFF_NEXT_DIRECTORY_OFFSET_BYTES.toLong(),
            ),
        ).toInt()
        val encoded = source.read(offset, directoryBytes) ?: return null
        val entries = buildMap {
            repeat(entryCount) { index ->
                val entryOffset = TIFF_DIRECTORY_COUNT_BYTES + index * TIFF_DIRECTORY_ENTRY_BYTES
                val tag = encoded.u16(entryOffset, byteOrder) ?: return null
                val type = encoded.u16(entryOffset + 2, byteOrder) ?: return null
                val count = encoded.u32(entryOffset + 4, byteOrder) ?: return null
                val valueField = encoded.copyOfRange(entryOffset + 8, entryOffset + 12)
                put(tag, TiffEntry(type, count, valueField))
            }
        }
        val nextDirectoryOffsetPosition =
            TIFF_DIRECTORY_COUNT_BYTES + entryCount * TIFF_DIRECTORY_ENTRY_BYTES
        val pageCount = if ((encoded.u32(nextDirectoryOffsetPosition, byteOrder) ?: 0L) > 0L) 2 else 1

        val width = entries.value(TIFF_TAG_IMAGE_WIDTH, byteOrder)?.toIntOrNull() ?: return null
        val height = entries.value(TIFF_TAG_IMAGE_HEIGHT, byteOrder)?.toIntOrNull() ?: return null
        val bitsPerSample = entries.values(TIFF_TAG_BITS_PER_SAMPLE, byteOrder)
            ?.mapNotNull(Long::toIntOrNull)
            ?.takeIf(List<Int>::isNotEmpty)
            ?: listOf(1)
        val samplesPerPixel =
            entries.value(TIFF_TAG_SAMPLES_PER_PIXEL, byteOrder)?.toIntOrNull() ?: 1
        val compression = entries.value(TIFF_TAG_COMPRESSION, byteOrder)?.toIntOrNull() ?: 1
        val photometric =
            entries.value(TIFF_TAG_PHOTOMETRIC, byteOrder)?.toIntOrNull() ?: return null
        val orientation = entries.value(TIFF_TAG_ORIENTATION, byteOrder)?.toIntOrNull() ?: 1
        val rowsPerStrip = entries.value(TIFF_TAG_ROWS_PER_STRIP, byteOrder)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: height
        val stripOffsets = entries.values(TIFF_TAG_STRIP_OFFSETS, byteOrder) ?: return null
        val stripByteCounts = entries.values(TIFF_TAG_STRIP_BYTE_COUNTS, byteOrder) ?: return null
        val planarConfiguration =
            entries.value(TIFF_TAG_PLANAR_CONFIGURATION, byteOrder)?.toIntOrNull() ?: 1
        val tiled = TIFF_TILE_TAGS.any(entries::containsKey)
        return TiffDirectory(
            width = width,
            height = height,
            bitsPerSample = bitsPerSample,
            samplesPerPixel = samplesPerPixel,
            compression = compression,
            photometric = photometric,
            orientation = orientation,
            rowsPerStrip = rowsPerStrip,
            stripOffsets = stripOffsets,
            stripByteCounts = stripByteCounts,
            planarConfiguration = planarConfiguration,
            tiled = tiled,
            pageCount = pageCount,
        )
    }

    private suspend fun Map<Int, TiffEntry>.value(
        tag: Int,
        byteOrder: TiffByteOrder,
    ): Long? = values(tag, byteOrder)?.firstOrNull()

    private suspend fun Map<Int, TiffEntry>.values(
        tag: Int,
        byteOrder: TiffByteOrder,
    ): List<Long>? {
        val entry = get(tag) ?: return null
        val typeBytes = tiffTypeBytes(entry.type) ?: return null
        val byteCount = entry.count * typeBytes.toLong()
        if (
            entry.count <= 0L ||
            entry.count > MAXIMUM_TIFF_VALUES ||
            byteCount <= 0L ||
            byteCount > MAXIMUM_TIFF_TAG_BYTES
        ) {
            return null
        }
        val encoded = if (byteCount <= TIFF_VALUE_FIELD_BYTES) {
            entry.valueField.copyOf(byteCount.toInt())
        } else {
            val valueOffset = entry.valueField.u32(0, byteOrder) ?: return null
            source.read(valueOffset, byteCount.toInt()) ?: return null
        }
        return (0 until entry.count.toInt()).mapNotNull { index ->
            when (entry.type) {
                TIFF_TYPE_BYTE -> encoded.getOrNull(index)?.toInt()?.and(0xff)?.toLong()
                TIFF_TYPE_SHORT -> encoded.u16(index * typeBytes, byteOrder)?.toLong()
                TIFF_TYPE_LONG -> encoded.u32(index * typeBytes, byteOrder)
                else -> null
            }
        }.takeIf { it.size == entry.count.toInt() }
    }
}

internal data class AndroidTiffInformation(
    val width: Int,
    val height: Int,
    val bitsPerSample: List<Int>,
    val samplesPerPixel: Int,
    val compression: Int,
    val photometricInterpretation: Int,
    val orientation: Int,
    val planarConfiguration: Int,
    val tiled: Boolean,
    val hasAdditionalPages: Boolean,
)

internal data class DecodedTiffPreview(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val pageCount: Int,
    val hasAlphaChannel: Boolean,
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size.toLong() == width.toLong() * height.toLong())
    }
}

internal enum class TiffPreviewEncoding {
    Jpeg,
    Png,
}

internal fun DecodedTiffPreview.previewEncoding(): TiffPreviewEncoding =
    if (hasAlphaChannel) TiffPreviewEncoding.Png else TiffPreviewEncoding.Jpeg

internal fun DecodedTiffPreview.encodeDisplayImage(
    quality: Int = TIFF_PREVIEW_JPEG_QUALITY,
): ByteArray? {
    require(quality in 1..100)
    val bitmap = runCatching {
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }.getOrNull() ?: return null
    return try {
        try {
            BoundedByteArrayOutputStream(MAXIMUM_GENERATED_PREVIEW_BYTES).use { output ->
                val format = when (previewEncoding()) {
                    TiffPreviewEncoding.Jpeg -> Bitmap.CompressFormat.JPEG
                    TiffPreviewEncoding.Png -> Bitmap.CompressFormat.PNG
                }
                if (!bitmap.compress(format, quality, output)) {
                    null
                } else {
                    output.toByteArray().takeIf(ByteArray::isNotEmpty)
                }
            }
        } catch (_: BoundedOutputExceededException) {
            null
        }
    } finally {
        bitmap.recycle()
    }
}

private class BoundedByteArrayOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream() {
    init {
        require(maximumBytes > 0)
    }

    override fun write(value: Int) {
        requireCapacity(1)
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        requireCapacity(length)
        super.write(bytes, offset, length)
    }

    private fun requireCapacity(additionalBytes: Int) {
        if (
            additionalBytes < 0 ||
            size().toLong() + additionalBytes.toLong() > maximumBytes.toLong()
        ) {
            throw BoundedOutputExceededException()
        }
    }
}

private class BoundedOutputExceededException : RuntimeException()

private data class TiffDirectory(
    val width: Int,
    val height: Int,
    val bitsPerSample: List<Int>,
    val samplesPerPixel: Int,
    val compression: Int,
    val photometric: Int,
    val orientation: Int,
    val rowsPerStrip: Int,
    val stripOffsets: List<Long>,
    val stripByteCounts: List<Long>,
    val planarConfiguration: Int,
    val tiled: Boolean,
    val pageCount: Int,
) {
    fun isSupported(): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width.toLong() * height.toLong() > MAXIMUM_TIFF_SOURCE_PIXELS) return false
        if (compression != TIFF_COMPRESSION_NONE || planarConfiguration != TIFF_PLANAR_CHUNKY) return false
        if (tiled || orientation !in 1..8 || rowsPerStrip <= 0) return false
        if (samplesPerPixel !in 1..4) return false
        if (photometric !in setOf(TIFF_PHOTOMETRIC_WHITE_IS_ZERO, TIFF_PHOTOMETRIC_BLACK_IS_ZERO, TIFF_PHOTOMETRIC_RGB)) {
            return false
        }
        if (bitsPerSample.isEmpty() || bitsPerSample.any { it !in setOf(8, 16) }) return false
        if (bitsPerSample.size > 1 && bitsPerSample.distinct().size != 1) return false
        if (photometric == TIFF_PHOTOMETRIC_RGB && samplesPerPixel !in 3..4) return false
        if (photometric != TIFF_PHOTOMETRIC_RGB && samplesPerPixel !in 1..2) return false
        val expectedStrips = ceilDiv(height, rowsPerStrip)
        return stripOffsets.size >= expectedStrips && stripByteCounts.size >= expectedStrips
    }

    fun rowByteCount(): Int? {
        val bits = bitsPerSample.firstOrNull() ?: return null
        val rowBits = width.toLong() * samplesPerPixel.toLong() * bits.toLong()
        val bytes = ceilDiv(rowBits, Byte.SIZE_BITS.toLong())
        return bytes.toIntOrNull()?.takeIf { it in 1..MAXIMUM_TIFF_ROW_BYTES }
    }

    fun readPixel(row: ByteArray, sourceX: Int, byteOrder: TiffByteOrder): Int? {
        val bits = bitsPerSample.first()
        val bytesPerSample = bits / Byte.SIZE_BITS
        val pixelOffset = sourceX * samplesPerPixel * bytesPerSample
        fun sample(index: Int): Int? {
            val offset = pixelOffset + index * bytesPerSample
            return when (bits) {
                8 -> row.getOrNull(offset)?.toInt()?.and(0xff)
                16 -> row.u16(offset, byteOrder)?.ushr(8)
                else -> null
            }
        }

        return if (photometric == TIFF_PHOTOMETRIC_RGB) {
            val red = sample(0) ?: return null
            val green = sample(1) ?: return null
            val blue = sample(2) ?: return null
            val alpha = if (samplesPerPixel == 4) sample(3) ?: return null else 255
            argb(alpha, red, green, blue)
        } else {
            val encoded = sample(0) ?: return null
            val gray = if (photometric == TIFF_PHOTOMETRIC_WHITE_IS_ZERO) 255 - encoded else encoded
            val alpha = if (samplesPerPixel == 2) sample(1) ?: return null else 255
            argb(alpha, gray, gray, gray)
        }
    }
}

private data class TiffEntry(
    val type: Int,
    val count: Long,
    val valueField: ByteArray,
)

internal data class TiffSamplingPlan(
    val sampleSize: Int,
    val sampledWidth: Int,
    val sampledHeight: Int,
)

internal fun tiffSamplingPlan(
    sourceWidth: Int,
    sourceHeight: Int,
    maximumDimension: Int,
    maximumPixels: Long,
): TiffSamplingPlan {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(maximumDimension > 0)
    require(maximumPixels > 0L)
    val dimensionScale = max(
        sourceWidth.toDouble() / maximumDimension.toDouble(),
        sourceHeight.toDouble() / maximumDimension.toDouble(),
    )
    val pixelScale = sqrt(sourceWidth.toDouble() * sourceHeight.toDouble() / maximumPixels.toDouble())
    val sampleSize = ceil(max(1.0, max(dimensionScale, pixelScale))).toInt()
    return TiffSamplingPlan(
        sampleSize = sampleSize,
        sampledWidth = ceilDiv(sourceWidth, sampleSize),
        sampledHeight = ceilDiv(sourceHeight, sampleSize),
    )
}

private class BufferedTiffRangeSource(
    private val sourceSize: Long,
    private val readRange: suspend (offset: Long, length: Int) -> ByteArray,
) {
    private var windowOffset = -1L
    private var window = byteArrayOf()
    private var downloadedBytes = 0L
    private var requestCount = 0

    suspend fun read(offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length <= 0) return null
        val endExclusive = safeAdd(offset, length.toLong()) ?: return null
        if (endExclusive > sourceSize) return null
        val windowEnd = if (windowOffset < 0L) -1L else windowOffset + window.size
        if (offset < windowOffset || endExclusive > windowEnd) {
            windowOffset = offset
            val remaining = sourceSize - offset
            val requested = minOf(
                remaining,
                maxOf(length.toLong(), TIFF_RANGE_WINDOW_BYTES.toLong()),
            ).toInt()
            if (
                requestCount >= MAXIMUM_TIFF_RANGE_REQUESTS ||
                downloadedBytes + requested.toLong() > MAXIMUM_TIFF_DOWNLOADED_BYTES
            ) {
                return null
            }
            requestCount += 1
            downloadedBytes += requested.toLong()
            window = readRange(offset, requested)
            if (window.size != requested) {
                windowOffset = -1L
                window = byteArrayOf()
                return null
            }
        }
        val start = (offset - windowOffset).toInt()
        return window.copyOfRange(start, start + length)
    }
}

private enum class TiffByteOrder {
    LittleEndian,
    BigEndian;

    companion object {
        fun fromHeader(bytes: ByteArray): TiffByteOrder? = when {
            bytes.size >= 2 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() ->
                LittleEndian
            bytes.size >= 2 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() ->
                BigEndian
            else -> null
        }
    }
}

private fun ByteArray.u16(offset: Int, byteOrder: TiffByteOrder): Int? {
    if (offset < 0 || size - offset < Short.SIZE_BYTES) return null
    val first = this[offset].toInt().and(0xff)
    val second = this[offset + 1].toInt().and(0xff)
    return if (byteOrder == TiffByteOrder.LittleEndian) {
        first or (second shl Byte.SIZE_BITS)
    } else {
        (first shl Byte.SIZE_BITS) or second
    }
}

private fun ByteArray.u32(offset: Int, byteOrder: TiffByteOrder): Long? {
    if (offset < 0 || size - offset < Int.SIZE_BYTES) return null
    val values = (0 until Int.SIZE_BYTES).map { index ->
        this[offset + index].toLong().and(0xffL)
    }
    return if (byteOrder == TiffByteOrder.LittleEndian) {
        values[0] or (values[1] shl 8) or (values[2] shl 16) or (values[3] shl 24)
    } else {
        (values[0] shl 24) or (values[1] shl 16) or (values[2] shl 8) or values[3]
    }
}

private fun tiffTypeBytes(type: Int): Int? = when (type) {
    TIFF_TYPE_BYTE -> Byte.SIZE_BYTES
    TIFF_TYPE_SHORT -> Short.SIZE_BYTES
    TIFF_TYPE_LONG -> Int.SIZE_BYTES
    else -> null
}

private fun orientPoint(
    orientation: Int,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
): Pair<Int, Int> = when (orientation) {
    2 -> width - 1 - x to y
    3 -> width - 1 - x to height - 1 - y
    4 -> x to height - 1 - y
    5 -> y to x
    6 -> height - 1 - y to x
    7 -> height - 1 - y to width - 1 - x
    8 -> y to width - 1 - x
    else -> x to y
}

private fun orientedWidth(orientation: Int, width: Int, height: Int): Int =
    if (orientation in 5..8) height else width

private fun orientedHeight(orientation: Int, width: Int, height: Int): Int =
    if (orientation in 5..8) width else height

private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
    (alpha shl 24) or (red shl 16) or (green shl 8) or blue

private fun ceilDiv(value: Int, divisor: Int): Int =
    ((value.toLong() + divisor.toLong() - 1L) / divisor.toLong()).toInt()

private fun ceilDiv(value: Long, divisor: Long): Long =
    (value + divisor - 1L) / divisor

private fun Long.toIntOrNull(): Int? =
    takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

private fun safeAdd(first: Long, second: Long): Long? =
    runCatching { Math.addExact(first, second) }.getOrNull()

private const val CLASSIC_TIFF_MAGIC = 42
private const val TIFF_HEADER_BYTES = 8
private const val TIFF_DIRECTORY_COUNT_BYTES = 2
private const val TIFF_DIRECTORY_ENTRY_BYTES = 12
private const val TIFF_NEXT_DIRECTORY_OFFSET_BYTES = 4
private const val TIFF_VALUE_FIELD_BYTES = 4L
private const val TIFF_TYPE_BYTE = 1
private const val TIFF_TYPE_SHORT = 3
private const val TIFF_TYPE_LONG = 4
private const val TIFF_TAG_IMAGE_WIDTH = 256
private const val TIFF_TAG_IMAGE_HEIGHT = 257
private const val TIFF_TAG_BITS_PER_SAMPLE = 258
private const val TIFF_TAG_COMPRESSION = 259
private const val TIFF_TAG_PHOTOMETRIC = 262
private const val TIFF_TAG_STRIP_OFFSETS = 273
private const val TIFF_TAG_ORIENTATION = 274
private const val TIFF_TAG_SAMPLES_PER_PIXEL = 277
private const val TIFF_TAG_ROWS_PER_STRIP = 278
private const val TIFF_TAG_STRIP_BYTE_COUNTS = 279
private const val TIFF_TAG_PLANAR_CONFIGURATION = 284
private const val TIFF_COMPRESSION_NONE = 1
private const val TIFF_PHOTOMETRIC_WHITE_IS_ZERO = 0
private const val TIFF_PHOTOMETRIC_BLACK_IS_ZERO = 1
private const val TIFF_PHOTOMETRIC_RGB = 2
private const val TIFF_PLANAR_CHUNKY = 1
private const val MAXIMUM_TIFF_DIRECTORY_ENTRIES = 1_024
private const val MAXIMUM_TIFF_VALUES = 100_000L
private const val MAXIMUM_TIFF_TAG_BYTES = 4L * 1024L * 1024L
private const val MAXIMUM_TIFF_ROW_BYTES = 4 * 1024 * 1024
private const val MAXIMUM_TIFF_SOURCE_PIXELS = 200_000_000L
private const val MAXIMUM_TIFF_PREVIEW_PIXELS = 5_000_000L
private const val TIFF_RANGE_WINDOW_BYTES = 32 * 1024
private const val MAXIMUM_TIFF_DOWNLOADED_BYTES = 96L * 1024L * 1024L
private const val MAXIMUM_TIFF_RANGE_REQUESTS = 8_192
private const val TIFF_CANCELLATION_ROW_INTERVAL = 64
private const val TIFF_PREVIEW_JPEG_QUALITY = 92
private const val MAXIMUM_GENERATED_PREVIEW_BYTES = 12 * 1024 * 1024
private val TIFF_TILE_TAGS = setOf(322, 323, 324, 325)
