package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal data class NativeBoardLaneStateKey(
    val resourceId: String,
    val laneKey: String,
)

internal fun resolveNativeBoardLaneDropTarget(
    position: Offset,
    laneBounds: Map<String, Rect>,
    allowedLaneKeys: Set<String>,
): String? = laneBounds.entries.firstOrNull { (key, bounds) ->
    key in allowedLaneKeys && bounds.contains(position)
}?.key

internal fun stableNativeBoardLaneOrder(
    initialLaneKeys: List<String>,
    currentLaneKeys: List<String>,
): List<String> = currentLaneKeys.sortedBy { laneKey ->
    initialLaneKeys.indexOf(laneKey)
        .takeIf { it >= 0 }
        ?: (initialLaneKeys.size + currentLaneKeys.indexOf(laneKey))
}
