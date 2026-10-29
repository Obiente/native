package dev.obiente.nextcloudnative.contracts

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

internal fun readSelectedTarFiles(archive: ByteArray): Map<String, ByteArray> {
    val selected = linkedMapOf<String, ByteArray>()
    GZIPInputStream(ByteArrayInputStream(archive)).use { input ->
        var totalUncompressed = 0L
        var pendingLongPath: String? = null
        while (true) {
            val header = input.readExactlyOrNull(TAR_BLOCK_BYTES) ?: break
            if (header.all { byte -> byte == 0.toByte() }) break
            val name = header.tarText(0, 100)
            val prefix = header.tarText(345, 155)
            val headerName = if (prefix.isBlank()) name else "$prefix/$name"
            val size = header.tarOctal(124, 12)
            check(size >= 0) { "An app package entry has an invalid size." }
            requireWithinLimit(size, MAX_TAR_ENTRY_BYTES) { "An app package entry is too large." }
            totalUncompressed += size
            requireWithinLimit(totalUncompressed, MAX_TAR_UNCOMPRESSED_BYTES) { "The app package expands beyond the safety limit." }
            val type = header[156].toInt().toChar()
            if (type == 'L') {
                requireWithinLimit(size, MAX_TAR_PATH_BYTES) { "An app package path exceeds the safety limit." }
                pendingLongPath = decodeTarLongPath(input.readExactly(size.toInt()))
                val longPathPadding = (TAR_BLOCK_BYTES - (size % TAR_BLOCK_BYTES)) % TAR_BLOCK_BYTES
                input.skipExactly(longPathPadding)
                continue
            }
            if (type in TAR_METADATA_TYPES) {
                input.skipExactly(size)
                val metadataPadding = (TAR_BLOCK_BYTES - (size % TAR_BLOCK_BYTES)) % TAR_BLOCK_BYTES
                input.skipExactly(metadataPadding)
                continue
            }
            val fullName = pendingLongPath ?: headerName
            pendingLongPath = null
            if (isCanonicalTarRootDirectory(fullName, type)) {
                input.skipExactly(size)
                val rootPadding = (TAR_BLOCK_BYTES - (size % TAR_BLOCK_BYTES)) % TAR_BLOCK_BYTES
                input.skipExactly(rootPadding)
                continue
            }
            validateArchivePath(fullName)
            val staticMetadata = fullName.endsWith("/appinfo/routes.php") ||
                (fullName.contains("/lib/Controller/") && fullName.endsWith("Controller.php"))
            val keep = (type == '\u0000' || type == '0') && (
                fullName.endsWith("/appinfo/info.xml") ||
                    fullName.substringAfterLast('/').matches(OPEN_API_FILE_PATTERN) ||
                    staticMetadata
                )
            if (keep) {
                val selectedFileLimit = if (staticMetadata) MAX_STATIC_METADATA_FILE_BYTES else MAX_SELECTED_FILE_BYTES
                requireWithinLimit(size, selectedFileLimit) { "A contract metadata file exceeds the safety limit." }
                requireWithinLimit(selected.size + 1L, MAX_SELECTED_ARCHIVE_FILES.toLong()) {
                    "The app package has too many contract metadata files."
                }
                selected[fullName] = input.readExactly(size.toInt())
            } else {
                input.skipExactly(size)
            }
            val padding = (TAR_BLOCK_BYTES - (size % TAR_BLOCK_BYTES)) % TAR_BLOCK_BYTES
            input.skipExactly(padding)
        }
        check(pendingLongPath == null) { "The app package ends with an incomplete path record." }
    }
    return selected
}

private inline fun requireWithinLimit(value: Long, limit: Long, message: () -> String) {
    if (value > limit) throw ContractSourceTooLargeException(limit, message())
}

private fun validateArchivePath(path: String) {
    require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path) { "The app package contains an unsafe path." }
    require(path.split('/').none { segment -> segment == "." || segment == ".." }) {
        "The app package contains a traversing path."
    }
}

private fun ByteArray.tarText(offset: Int, length: Int): String =
    copyOfRange(offset, offset + length).takeWhile { byte -> byte != 0.toByte() }.toByteArray()
        .decodeToString()
        .trim()

private fun ByteArray.tarOctal(offset: Int, length: Int): Long {
    val value = tarText(offset, length).trim().trimStart('0')
    return if (value.isEmpty()) 0L else value.toLong(8)
}

private fun InputStream.readExactlyOrNull(length: Int): ByteArray? {
    val first = read()
    if (first == -1) return null
    val result = ByteArray(length)
    result[0] = first.toByte()
    var offset = 1
    while (offset < length) {
        val read = read(result, offset, length - offset)
        check(read > 0) { "The app package is truncated." }
        offset += read
    }
    return result
}

private fun InputStream.readExactly(length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(result, offset, length - offset)
        check(read > 0) { "The app package is truncated." }
        offset += read
    }
    return result
}

private fun InputStream.skipExactly(length: Long) {
    var remaining = length
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        check(read > 0) { "The app package is truncated." }
        remaining -= read
    }
}

private const val TAR_BLOCK_BYTES = 512
private const val MAX_TAR_ENTRY_BYTES = 128L * 1024L * 1024L
private const val MAX_TAR_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
private const val MAX_STATIC_METADATA_FILE_BYTES = 1L * 1024L * 1024L
private const val MAX_SELECTED_ARCHIVE_FILES = 320
private val TAR_METADATA_TYPES = setOf('g', 'x', 'K')
private const val MAX_TAR_PATH_BYTES = 4_096L
