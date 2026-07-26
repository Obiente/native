package dev.obiente.nextcloudnative.app

const val MAX_EXTERNAL_FILE_HANDOFF_BYTES = 64L * 1024L * 1024L

enum class ExternalFileHandoffAction {
    Share,
    OpenWith,
}

data class ExternalFileHandoffCapability(
    val supportedActions: Set<ExternalFileHandoffAction>,
    val maximumFileBytes: Long,
) {
    init {
        require(supportedActions.isNotEmpty()) { "At least one external file action must be supported." }
        require(maximumFileBytes > 0L) { "The external file size limit must be positive." }
    }
}

sealed interface ExternalFileHandoffSupport {
    data class Available(val capability: ExternalFileHandoffCapability) : ExternalFileHandoffSupport
    data class Unsupported(val reason: String) : ExternalFileHandoffSupport
}

enum class ExternalFileHandoffRejection {
    Directory,
    FileTooLarge,
    UnsupportedAction,
    MissingVersion,
    VersionChanged,
    OriginalAccessRestricted,
}

sealed interface ExternalFileHandoffResult {
    data class Launched(val action: ExternalFileHandoffAction) : ExternalFileHandoffResult
    data class Rejected(
        val reason: ExternalFileHandoffRejection,
        val message: String,
    ) : ExternalFileHandoffResult
    data class NoCompatibleApplication(val action: ExternalFileHandoffAction) : ExternalFileHandoffResult
    data class Unsupported(val reason: String) : ExternalFileHandoffResult
}

fun validateExternalFileHandoff(
    file: NextcloudFile,
    action: ExternalFileHandoffAction,
    capability: ExternalFileHandoffCapability,
): ExternalFileHandoffResult.Rejected? = when {
    action !in capability.supportedActions -> ExternalFileHandoffResult.Rejected(
        ExternalFileHandoffRejection.UnsupportedAction,
        "This platform does not support ${action.displayName()} for files.",
    )
    file.isDirectory -> ExternalFileHandoffResult.Rejected(
        ExternalFileHandoffRejection.Directory,
        "Folders cannot be sent to another app as a single file.",
    )
    !file.originalAccessAllowed -> ExternalFileHandoffResult.Rejected(
        ExternalFileHandoffRejection.OriginalAccessRestricted,
        "This file allows preview only and cannot be sent to another app.",
    )
    file.etag.isNullOrBlank() -> ExternalFileHandoffResult.Rejected(
        ExternalFileHandoffRejection.MissingVersion,
        "Refresh the folder before sending this file to another app.",
    )
    file.size != null && file.size > capability.maximumFileBytes -> ExternalFileHandoffResult.Rejected(
        ExternalFileHandoffRejection.FileTooLarge,
        "${file.name} is larger than the ${formatHandoffByteLimit(capability.maximumFileBytes)} handoff limit.",
    )
    else -> null
}

fun validateDeckAttachmentHandoff(
    attachment: DeckAttachment,
    action: ExternalFileHandoffAction,
    capability: ExternalFileHandoffCapability,
): ExternalFileHandoffResult.Rejected? = when {
    action !in capability.supportedActions -> ExternalFileHandoffResult.Rejected(
        ExternalFileHandoffRejection.UnsupportedAction,
        "This platform does not support opening this attachment in another app.",
    )
    attachment.byteCount != null && attachment.byteCount > capability.maximumFileBytes ->
        ExternalFileHandoffResult.Rejected(
            ExternalFileHandoffRejection.FileTooLarge,
            "${attachment.name} is larger than the external handoff limit.",
        )
    else -> null
}

/**
 * Verifies the detached bytes after download and before exposing them to another process.
 *
 * The selected DAV ETag and downloaded ETag must match exactly. This prevents a background
 * refresh, rename race, or stale cache from silently handing off a different generation.
 */
fun validateDownloadedExternalFile(
    selected: NextcloudFile,
    downloaded: NextcloudFileContent,
    maximumBytes: Long,
): ExternalFileHandoffResult.Rejected? {
    require(maximumBytes > 0L)
    if (downloaded.bytes.size.toLong() > maximumBytes) {
        return ExternalFileHandoffResult.Rejected(
            ExternalFileHandoffRejection.FileTooLarge,
            "The downloaded file is larger than the external handoff limit.",
        )
    }
    val selectedEtag = selected.etag?.trim()?.takeIf(String::isNotEmpty)
        ?: return ExternalFileHandoffResult.Rejected(
            ExternalFileHandoffRejection.MissingVersion,
            "Refresh the folder before sending this file to another app.",
        )
    val downloadedEtag = downloaded.etag?.trim()?.takeIf(String::isNotEmpty)
        ?: return ExternalFileHandoffResult.Rejected(
            ExternalFileHandoffRejection.VersionChanged,
            "The downloaded file version could not be verified. Refresh and try again.",
        )
    if (downloadedEtag != selectedEtag) {
        return ExternalFileHandoffResult.Rejected(
            ExternalFileHandoffRejection.VersionChanged,
            "The file changed while it was being prepared. Refresh and try again.",
        )
    }
    return null
}

/** Produces a display-friendly filename that can never escape the private share-cache directory. */
fun sanitizeExternalFileName(name: String): String {
    val basename = name.substringAfterLast('/').substringAfterLast('\\')
    val sanitized = buildString {
        basename.forEach { character ->
            append(
                when {
                    character.isISOControl() || character.category == CharCategory.FORMAT -> '_'
                    character == '/' || character == '\\' || character == ':' -> '_'
                    else -> character
                },
            )
        }
    }.trim().trim('.')
    val usable = sanitized.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "nextcloud-file"
    if (usable.length <= MAX_EXTERNAL_FILENAME_LENGTH) return usable

    val extensionIndex = usable.lastIndexOf('.').takeIf { it in 1 until usable.lastIndex }
    val extension = extensionIndex?.let(usable::substring).orEmpty().take(MAX_EXTERNAL_EXTENSION_LENGTH)
    val stemLength = (MAX_EXTERNAL_FILENAME_LENGTH - extension.length).coerceAtLeast(1)
    return usable.take(stemLength).trimEnd('.', ' ') + extension
}

/** Removes parameters and rejects malformed values before a MIME type reaches an Android intent. */
fun sanitizeExternalMimeType(value: String?): String {
    val candidate = value
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.length in 3..MAX_EXTERNAL_MIME_LENGTH }
        ?: return DEFAULT_EXTERNAL_MIME_TYPE
    if (candidate.count { it == '/' } != 1) return DEFAULT_EXTERNAL_MIME_TYPE
    val type = candidate.substringBefore('/')
    val subtype = candidate.substringAfter('/')
    if (type.isBlank() || subtype.isBlank()) return DEFAULT_EXTERNAL_MIME_TYPE
    if (!type.all(::isSafeMimeCharacter) || !subtype.all(::isSafeMimeCharacter)) return DEFAULT_EXTERNAL_MIME_TYPE
    return candidate
}

private fun isSafeMimeCharacter(character: Char): Boolean =
    character in 'a'..'z' || character in '0'..'9' || character in "!#$&^_.+-"

private fun ExternalFileHandoffAction.displayName(): String = when (this) {
    ExternalFileHandoffAction.Share -> "sharing"
    ExternalFileHandoffAction.OpenWith -> "opening with another app"
}

private fun formatHandoffByteLimit(bytes: Long): String = when {
    bytes % (1024L * 1024L) == 0L -> "${bytes / (1024L * 1024L)} MiB"
    bytes % 1024L == 0L -> "${bytes / 1024L} KiB"
    else -> "$bytes byte"
}

private const val MAX_EXTERNAL_FILENAME_LENGTH = 180
private const val MAX_EXTERNAL_EXTENSION_LENGTH = 24
private const val MAX_EXTERNAL_MIME_LENGTH = 127
private const val DEFAULT_EXTERNAL_MIME_TYPE = "application/octet-stream"
