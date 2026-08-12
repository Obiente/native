package dev.obiente.nextcloudnative.app

enum class NextcloudFileListingSource {
    Network,
    Cache,
}

data class NextcloudFileListing(
    val files: List<NextcloudFile>,
    val source: NextcloudFileListingSource,
)

/**
 * A WebDAV folder listing was rejected by the server.
 *
 * The typed status lets shared UI distinguish a missing folder from permission and server failures
 * without inspecting platform-specific exception messages.
 */
class NextcloudFileListingHttpException(
    val status: Int,
) : Exception("WebDAV folder listing failed (HTTP $status).") {
    init {
        require(status in 100..599 && status !in 200..299) {
            "A folder-listing HTTP failure requires a non-success status."
        }
    }
}

/** A server-backed Files search was rejected by the WebDAV endpoint. */
class NextcloudFileSearchHttpException(
    val status: Int,
) : Exception("File search failed (HTTP $status).") {
    init {
        require(status in 100..599 && status !in 200..299) {
            "A file-search HTTP failure requires a non-success status."
        }
    }
}

internal fun nextcloudFileListingSummary(
    source: NextcloudFileListingSource?,
    visibleCount: Int,
    totalCount: Int,
    filtered: Boolean,
): String = buildString {
    require(visibleCount >= 0 && totalCount >= 0 && visibleCount <= totalCount) {
        "File listing counts are invalid."
    }
    if (source == NextcloudFileListingSource.Cache) append("Cached · ")
    if (filtered) append("$visibleCount of $totalCount") else append("$totalCount items")
}

internal fun nextcloudFileRefreshFailure(hasRetainedFiles: Boolean, cause: Throwable): String =
    if (hasRetainedFiles) {
        "Could not refresh this folder. Showing the previous contents."
    } else {
        cause.message ?: "Could not list this folder."
    }
