package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.obiente.nextcloudnative.app.design.resolveBoardDragLaneDropTarget

internal data class NativeBoardLaneStateKey(
    val resourceId: String,
    val laneKey: String,
)

internal data class NativeBoardScrollStateKey(
    val resourceId: String,
)

internal fun resolveNativeBoardLaneDropTarget(
    position: Offset,
    boardViewport: Rect,
    laneBounds: Map<String, Rect>,
    allowedLaneKeys: Set<String>,
): String? = resolveBoardDragLaneDropTarget(
    position = position,
    boardViewport = boardViewport,
    laneViewports = laneBounds,
    allowedLaneKeys = allowedLaneKeys,
)

internal fun stableNativeBoardLaneOrder(
    initialLaneKeys: List<String>,
    currentLaneKeys: List<String>,
): List<String> = currentLaneKeys.sortedBy { laneKey ->
    initialLaneKeys.indexOf(laneKey)
        .takeIf { it >= 0 }
        ?: (initialLaneKeys.size + currentLaneKeys.indexOf(laneKey))
}
