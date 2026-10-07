package dev.obiente.nextcloudnative.app

internal const val MAX_WINDOWS_CLOUD_PLACEHOLDER_DIAGNOSTIC_RESULTS = 16

internal fun windowsCloudPlaceholderDiagnosticSampleSize(availableCount: Int): Int {
    require(availableCount >= 0)
    return minOf(availableCount, MAX_WINDOWS_CLOUD_PLACEHOLDER_DIAGNOSTIC_RESULTS)
}

internal fun windowsCloudFailedPlaceholderIndex(
    firstFailedEntryIndex: Int?,
    processedCount: Int,
    placeholderCount: Int,
): Int? {
    require(processedCount in 0..placeholderCount)
    require(firstFailedEntryIndex == null || firstFailedEntryIndex in 0 until placeholderCount)
    return firstFailedEntryIndex
        ?: processedCount.takeIf { it in 0 until placeholderCount }
        ?: (processedCount - 1).takeIf { it in 0 until placeholderCount }
}
