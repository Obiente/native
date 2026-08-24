package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile

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
)
