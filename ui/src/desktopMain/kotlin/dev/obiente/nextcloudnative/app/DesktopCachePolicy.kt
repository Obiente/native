package dev.obiente.nextcloudnative.app

internal fun combinedAutomaticCacheExcess(
    maximumBytes: Long,
    completeFileBytes: Long,
    rangeBytes: Long,
    windowsCachedBytes: Long,
    windowsPinnedBytes: Long,
): Long {
    require(maximumBytes > 0L)
    require(listOf(completeFileBytes, rangeBytes, windowsCachedBytes, windowsPinnedBytes).all { it >= 0L })
    require(windowsPinnedBytes <= windowsCachedBytes)
    val total = listOf(
        completeFileBytes,
        rangeBytes,
        windowsCachedBytes - windowsPinnedBytes,
    ).fold(0L) { accumulated, bytes ->
        if (bytes > Long.MAX_VALUE - accumulated) Long.MAX_VALUE else accumulated + bytes
    }
    return (total - maximumBytes).coerceAtLeast(0L)
}
