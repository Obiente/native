package dev.obiente.nextcloudnative.app

/**
 * Selecting a record without a destination keeps the current collection on screen. Its path
 * bindings still belong to that collection and must survive the selection. Otherwise a child's
 * generic `id` can replace the parent's generic `id` when the collection reloads.
 */
internal fun resolveDynamicRecordSelectionParameters(
    currentViewId: String,
    nextViewId: String,
    currentParameters: Map<String, String>,
    explicitTargetParameters: Map<String, String>?,
    fallbackTargetParameters: Map<String, String>,
): Map<String, String> = explicitTargetParameters
    ?: if (nextViewId == currentViewId) currentParameters else fallbackTargetParameters
