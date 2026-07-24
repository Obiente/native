package dev.obiente.nextcloudnative.app

/**
 * Reads TIFF/EXIF orientation from a bounded encoded image without decoding pixel data.
 *
 * Unknown, malformed, truncated and unsupported metadata deliberately resolve to orientation 1.
 * The parser accepts JPEG APP1 EXIF and bare TIFF headers used by some RAW-derived payloads.
 */
internal fun encodedImageOrientation(bytes: ByteArray): Int {
    if (bytes.size < 8) return 1
    val tiffStart = when {
        bytes.isTiffHeaderAt(0) -> 0
        bytes.u8(0) == 0xFF && bytes.u8(1) == 0xD8 -> bytes.jpegExifTiffStart() ?: return 1
        else -> return 1
    }
    return bytes.tiffOrientation(tiffStart)
}

internal fun orientationSwapsDimensions(orientation: Int): Boolean = orientation in 5..8

private fun ByteArray.jpegExifTiffStart(): Int? {
    var cursor = 2
    while (cursor + 4 <= size) {
        if (u8(cursor) != 0xFF) return null
        while (cursor < size && u8(cursor) == 0xFF) cursor += 1
        if (cursor >= size) return null
        val marker = u8(cursor++)
        if (marker == 0xD9 || marker == 0xDA) return null
        if (marker in 0xD0..0xD8 || marker == 0x01) continue
        if (cursor + 2 > size) return null
        val segmentLength = u16BigEndian(cursor)
        if (segmentLength < 2) return null
        val payloadStart = cursor + 2
        val payloadEnd = cursor + segmentLength
        if (payloadEnd > size) return null
        if (
            marker == 0xE1 &&
            payloadEnd - payloadStart >= 14 &&
            this[payloadStart] == 'E'.code.toByte() &&
            this[payloadStart + 1] == 'x'.code.toByte() &&
            this[payloadStart + 2] == 'i'.code.toByte() &&
            this[payloadStart + 3] == 'f'.code.toByte() &&
            this[payloadStart + 4] == 0.toByte() &&
            this[payloadStart + 5] == 0.toByte()
        ) {
            val candidate = payloadStart + 6
            if (isTiffHeaderAt(candidate)) return candidate
        }
        cursor = payloadEnd
    }
    return null
}

private fun ByteArray.tiffOrientation(start: Int): Int {
    if (!isTiffHeaderAt(start) || start + 8 > size) return 1
    val littleEndian = this[start] == 'I'.code.toByte()
    if (u16(start + 2, littleEndian) != 42) return 1
    val ifdOffset = u32(start + 4, littleEndian) ?: return 1
    if (ifdOffset > MAX_EXIF_OFFSET) return 1
    val ifd = start + ifdOffset.toInt()
    if (ifd < start || ifd + 2 > size) return 1
    val count = u16(ifd, littleEndian).coerceAtMost(MAX_IFD_ENTRIES)
    repeat(count) { index ->
        val entry = ifd + 2 + index * 12
        if (entry + 12 > size) return 1
        if (u16(entry, littleEndian) != ORIENTATION_TAG) return@repeat
        if (u16(entry + 2, littleEndian) != TIFF_SHORT) return 1
        val valueCount = u32(entry + 4, littleEndian) ?: return 1
        if (valueCount < 1L) return 1
        val orientation = u16(entry + 8, littleEndian)
        return orientation.takeIf { it in 1..8 } ?: 1
    }
    return 1
}

private fun ByteArray.isTiffHeaderAt(offset: Int): Boolean {
    if (offset < 0 || offset + 4 > size) return false
    return (
        this[offset] == 'I'.code.toByte() && this[offset + 1] == 'I'.code.toByte()
        ) || (
        this[offset] == 'M'.code.toByte() && this[offset + 1] == 'M'.code.toByte()
        )
}

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

private fun ByteArray.u16BigEndian(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

private fun ByteArray.u16(offset: Int, littleEndian: Boolean): Int = if (littleEndian) {
    u8(offset) or (u8(offset + 1) shl 8)
} else {
    (u8(offset) shl 8) or u8(offset + 1)
}

private fun ByteArray.u32(offset: Int, littleEndian: Boolean): Long? {
    if (offset < 0 || offset + 4 > size) return null
    return if (littleEndian) {
        u8(offset).toLong() or
            (u8(offset + 1).toLong() shl 8) or
            (u8(offset + 2).toLong() shl 16) or
            (u8(offset + 3).toLong() shl 24)
    } else {
        (u8(offset).toLong() shl 24) or
            (u8(offset + 1).toLong() shl 16) or
            (u8(offset + 2).toLong() shl 8) or
            u8(offset + 3).toLong()
    }
}

private const val ORIENTATION_TAG = 0x0112
private const val TIFF_SHORT = 3
private const val MAX_IFD_ENTRIES = 4_096
private const val MAX_EXIF_OFFSET = 16L * 1024L * 1024L
