package dev.obiente.nextcloudnative.app

enum class NextcloudFileListingSource {
    Network,
    Cache,
}

data class NextcloudFileListing(
    val files: List<NextcloudFile>,
    val source: NextcloudFileListingSource,
)

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
