package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import okhttp3.Response

internal enum class DocumentWebDavError {
    Authentication,
    Permission,
    NotFound,
    AlreadyExists,
    Conflict,
    Locked,
    InsufficientStorage,
    TooLarge,
    Throttled,
    Server,
}

internal class DocumentWebDavException(
    val error: DocumentWebDavError,
    val status: Int,
    message: String,
    val retryAfterSeconds: Long? = null,
) : Exception(message)

internal fun Response.toDocumentException(operation: String): DocumentWebDavException {
    val error = when (code) {
        401 -> DocumentWebDavError.Authentication
        403 -> DocumentWebDavError.Permission
        404 -> DocumentWebDavError.NotFound
        405, 409 -> DocumentWebDavError.AlreadyExists
        412 -> DocumentWebDavError.Conflict
        413 -> DocumentWebDavError.TooLarge
        423 -> DocumentWebDavError.Locked
        429 -> DocumentWebDavError.Throttled
        507 -> DocumentWebDavError.InsufficientStorage
        else -> DocumentWebDavError.Server
    }
    val message = when (error) {
        DocumentWebDavError.Authentication -> "Sign in again before trying to $operation."
        DocumentWebDavError.Permission -> "Nextcloud did not allow this account to $operation."
        DocumentWebDavError.NotFound -> "The document no longer exists."
        DocumentWebDavError.AlreadyExists -> "A document with that name already exists."
        DocumentWebDavError.Conflict -> "The document changed on the server. Refresh before trying again."
        DocumentWebDavError.Locked -> "The document is currently locked by another operation."
        DocumentWebDavError.InsufficientStorage -> "The Nextcloud server does not have enough free storage."
        DocumentWebDavError.TooLarge -> "The document is larger than the current provider limit."
        DocumentWebDavError.Throttled -> "Nextcloud asked this upload to wait before trying again."
        DocumentWebDavError.Server -> "Nextcloud could not $operation (HTTP $code)."
    }
    return DocumentWebDavException(
        error,
        code,
        message,
        retryAfterSeconds = parseDocumentRetryAfterSeconds(header("Retry-After")),
    )
}

internal fun parseDocumentRetryAfterSeconds(
    value: String?,
    nowEpochMillis: Long = System.currentTimeMillis(),
): Long? {
    require(nowEpochMillis >= 0L)
    val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    normalized.toLongOrNull()?.let { return it.coerceIn(1L, MAX_DOCUMENT_RETRY_AFTER_SECONDS) }
    val retryEpochMillis = runCatching {
        ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull() ?: return null
    val delayMillis = (retryEpochMillis - nowEpochMillis).coerceAtLeast(1L)
    return ((delayMillis + 999L) / 1_000L).coerceIn(1L, MAX_DOCUMENT_RETRY_AFTER_SECONDS)
}

private const val MAX_DOCUMENT_RETRY_AFTER_SECONDS = 86_400L

internal data class DocumentMutationResult(val etag: String?)

internal data class DocumentReadResult(
    val byteCount: Long,
    val contentType: String?,
    val etag: String?,
)

internal data class DocumentSearchResult(
    val files: List<NextcloudFile>,
    val query: String,
    val limited: Boolean,
)

internal data class DocumentDirectoryResult(
    val files: List<NextcloudFile>,
    val limited: Boolean,
)

internal data class DocumentDirectoryAccess(
    val canCreateFiles: Boolean,
    val canCreateDirectories: Boolean,
    val permissionsKnown: Boolean = true,
)
