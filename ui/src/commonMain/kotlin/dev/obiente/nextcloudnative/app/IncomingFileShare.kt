package dev.obiente.nextcloudnative.app

const val MAX_INCOMING_SHARE_FILES = 100
const val MAX_INCOMING_SHARE_FILE_NAME_LENGTH = 240

/** Converts an untrusted provider display name into one safe Files path segment. */
fun safeIncomingShareFileName(rawName: String, fallbackIndex: Int): String {
    require(fallbackIndex >= 0)
    val cleaned = rawName
        .map { character ->
            when {
                character == '/' || character == '\\' || character == '\u0000' || character.isISOControl() -> '_'
                else -> character
            }
        }
        .joinToString("")
        .trim()
        .trim { it == '.' || it == '_' }
        .take(MAX_INCOMING_SHARE_FILE_NAME_LENGTH)
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
    val extensionIndex = safeName.lastIndexOf('.').takeIf { it > 0 && it < safeName.lastIndex }
    val stem = extensionIndex?.let { safeName.substring(0, it) } ?: safeName
    val extension = extensionIndex?.let { safeName.substring(it) }.orEmpty()
    return buildList(limit) {
        add(safeName)
        for (copyIndex in 1 until limit) {
            val suffix = " ($copyIndex)$extension"
            val boundedStem = stem.take((MAX_INCOMING_SHARE_FILE_NAME_LENGTH - suffix.length).coerceAtLeast(1))
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
