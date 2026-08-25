package dev.obiente.nextcloudnative.app

const val MAX_INCOMING_SHARE_FILES = 100
const val MAX_INCOMING_SHARE_FILE_NAME_LENGTH = 240
const val MAX_INCOMING_SHARE_FILE_NAME_BYTES = 255

/** Converts an untrusted provider display name into one safe Files path segment. */
fun safeIncomingShareFileName(rawName: String, fallbackIndex: Int): String {
    require(fallbackIndex >= 0)
    val cleaned = rawName
        .map { character ->
            when {
                character == '/' || character == '\\' || character == '\u0000' ||
                    character.isISOControl() || character.category == CharCategory.FORMAT -> '_'
                else -> character
            }
        }
        .joinToString("")
        .trim()
        .trim { it == '.' || it == '_' }
        .boundIncomingShareName()
    return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." }
        ?: "shared-file-${fallbackIndex + 1}"
}

/**
 * Returns deterministic non-overwriting names for one shared file. The original name is always
 * first; numbered copies preserve the final extension.
 */
fun incomingShareUploadNameCandidates(displayName: String, limit: Int = 100): List<String> {
    require(limit in 1..1000)
    val safeName = safeIncomingShareFileName(displayName, 0)
    val extensionIndex = safeName.incomingShareExtensionIndex()
    val stem = extensionIndex?.let { safeName.substring(0, it) } ?: safeName
    val extension = extensionIndex?.let { safeName.substring(it) }.orEmpty()
    return buildList(limit) {
        add(safeName)
        for (copyIndex in 1 until limit) {
            val suffix = " ($copyIndex)$extension"
            val boundedStem = stem
                .take((MAX_INCOMING_SHARE_FILE_NAME_LENGTH - suffix.length).coerceAtLeast(1))
                .takeUtf8Bytes(
                    (MAX_INCOMING_SHARE_FILE_NAME_BYTES - suffix.encodeToByteArray().size).coerceAtLeast(1),
                )
            add(boundedStem + suffix)
        }
    }
}

fun incomingShareRemotePath(destinationPath: String, fileName: String): String {
    val destination = canonicalIncomingShareDestinationPath(destinationPath)
    val safeName = safeIncomingShareFileName(fileName, 0)
    return if (destination.isEmpty()) safeName else "$destination/$safeName"
}

fun canonicalIncomingShareDestinationPath(destinationPath: String): String =
    requireNotNull(canonicalRemoteFolderPath(destinationPath)) {
        "The upload destination is invalid."
    }

private fun String.takeUtf8Bytes(maximumBytes: Int): String {
    require(maximumBytes > 0)
    if (encodeToByteArray().size <= maximumBytes) return this
    var low = 0
    var high = length
    while (low < high) {
        val middle = (low + high + 1) / 2
        val candidate = take(middle).dropLastWhile(Char::isHighSurrogate)
        if (candidate.encodeToByteArray().size <= maximumBytes) low = middle else high = middle - 1
    }
    return take(low).dropLastWhile(Char::isHighSurrogate)
}

private fun String.boundIncomingShareName(): String {
    val extensionIndex = incomingShareExtensionIndex()
    val extension = extensionIndex?.let { substring(it) }.orEmpty()
    val stem = extensionIndex?.let { substring(0, it) } ?: this
    val boundedStem = stem
        .take((MAX_INCOMING_SHARE_FILE_NAME_LENGTH - extension.length).coerceAtLeast(1))
        .takeUtf8Bytes((MAX_INCOMING_SHARE_FILE_NAME_BYTES - extension.encodeToByteArray().size).coerceAtLeast(1))
    return boundedStem + extension
}

private fun String.incomingShareExtensionIndex(): Int? = lastIndexOf('.').takeIf { index ->
    index > 0 && index < lastIndex &&
        substring(index).length <= MAX_INCOMING_SHARE_FILE_NAME_LENGTH / 2 &&
        substring(index).encodeToByteArray().size <= MAX_INCOMING_SHARE_EXTENSION_BYTES
}

private const val MAX_INCOMING_SHARE_EXTENSION_BYTES = 64
