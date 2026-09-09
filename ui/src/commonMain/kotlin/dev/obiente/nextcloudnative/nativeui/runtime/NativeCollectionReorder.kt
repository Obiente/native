package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal fun moveNativeCollectionRecordToVisibleTarget(
    orderedRecordIds: List<String>,
    recordId: String,
    rowBounds: Map<String, Rect>,
    pointerPosition: Offset,
    visibleItemKeys: Set<String>,
): List<String>? {
    val targetId = nativeVisibleReorderTargetId(
        orderedRecordIds = orderedRecordIds,
        rowBounds = rowBounds,
        pointerPosition = pointerPosition,
        visibleItemKeys = visibleItemKeys,
    ) ?: return null
    if (targetId == recordId) return null

    val targetIndex = orderedRecordIds.indexOf(targetId)
    if (targetIndex < 0) return null
    return moveNativeCollectionRecordToIndex(
        orderedRecordIds = orderedRecordIds,
        recordId = recordId,
        targetIndex = targetIndex,
    )
}
