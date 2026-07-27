package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MAX_AUTO_SCROLL_FRAME_SECONDS = 0.05f

internal data class BoardDragVerticalScrollTarget(
    val state: ScrollableState,
    val viewport: Rect,
)

internal data class BoardDragTargetRefreshFrame(
    val shouldRefresh: Boolean,
    val terminalDropReady: Boolean,
    val nextState: BoardDragTargetRefreshState,
)

internal data class BoardDragTargetRefreshState(
    val pending: Boolean = false,
) {
    fun beginFrame(terminalDropRequested: Boolean = false): BoardDragTargetRefreshFrame =
        BoardDragTargetRefreshFrame(
            shouldRefresh = pending || terminalDropRequested,
            terminalDropReady = terminalDropRequested,
            nextState = copy(pending = false),
        )

    fun afterScroll(
        horizontalConsumed: Float,
        verticalConsumed: Float,
    ): BoardDragTargetRefreshState = copy(
        pending = pending || shouldRefreshBoardDragTarget(horizontalConsumed, verticalConsumed),
    )
}

@Composable
internal fun NextcloudBoardDragAutoScroll(
    activeDragKey: Any?,
    position: Offset?,
    boardViewport: Rect?,
    horizontalScrollState: ScrollableState,
    verticalScrollTargetAt: (Offset, Rect, Float) -> BoardDragVerticalScrollTarget?,
    terminalDropRequested: Boolean,
    onTargetRefresh: () -> Unit,
    onTerminalDropReady: () -> Unit,
) {
    val density = LocalDensity.current
    val edgeThresholdPx = with(density) { 56.dp.toPx() }
    val horizontalIntentThresholdPx = with(density) { 12.dp.toPx() }
    val verticalActivationHaloPx = with(density) { 16.dp.toPx() }
    val maxVelocityPxPerSecond = with(density) { 720.dp.toPx() }
    val currentPosition by rememberUpdatedState(position)
    val currentBoardViewport by rememberUpdatedState(boardViewport)
    val currentHorizontalScrollState by rememberUpdatedState(horizontalScrollState)
    val currentVerticalScrollTargetAt by rememberUpdatedState(verticalScrollTargetAt)
    val currentTerminalDropRequested by rememberUpdatedState(terminalDropRequested)
    val currentOnTargetRefresh by rememberUpdatedState(onTargetRefresh)
    val currentOnTerminalDropReady by rememberUpdatedState(onTerminalDropReady)

    LaunchedEffect(activeDragKey) {
        if (activeDragKey == null) return@LaunchedEffect
        var previousFrameNanos = 0L
        var targetRefreshState = BoardDragTargetRefreshState()
        var horizontalDragOrigin = currentPosition?.x
        while (true) {
            val frameNanos = withFrameNanos { it }
            if (previousFrameNanos == 0L) {
                previousFrameNanos = frameNanos
                continue
            }
            val refreshFrame = targetRefreshState.beginFrame(
                terminalDropRequested = currentTerminalDropRequested,
            )
            targetRefreshState = refreshFrame.nextState
            if (refreshFrame.shouldRefresh) {
                currentOnTargetRefresh()
            }
            if (refreshFrame.terminalDropReady) {
                currentOnTerminalDropReady()
                continue
            }
            val elapsedSeconds = (
                (frameNanos - previousFrameNanos).toFloat() / NANOS_PER_SECOND
            ).coerceAtMost(MAX_AUTO_SCROLL_FRAME_SECONDS)
            previousFrameNanos = frameNanos

            val currentDragPosition = currentPosition ?: continue
            val currentViewport = currentBoardViewport ?: continue
            val dragOrigin = horizontalDragOrigin ?: currentDragPosition.x.also {
                horizontalDragOrigin = it
            }
            val horizontalVelocity = resolveBoardDragHorizontalEdgeScrollVelocity(
                pointer = currentDragPosition.x,
                dragOrigin = dragOrigin,
                viewportStart = currentViewport.left,
                viewportEnd = currentViewport.right,
                edgeThreshold = edgeThresholdPx,
                intentThreshold = horizontalIntentThresholdPx,
                maxVelocity = maxVelocityPxPerSecond,
            )
            val horizontalConsumed = if (horizontalVelocity == 0f) {
                0f
            } else {
                currentHorizontalScrollState.scrollBy(horizontalVelocity * elapsedSeconds)
            }

            val verticalScrollTarget = currentVerticalScrollTargetAt(
                currentDragPosition,
                currentViewport,
                verticalActivationHaloPx,
            )
            val verticalVelocity = verticalScrollTarget?.let { target ->
                resolveBoardDragEdgeScrollVelocity(
                    pointer = currentDragPosition.y,
                    viewportStart = target.viewport.top,
                    viewportEnd = target.viewport.bottom,
                    edgeThreshold = edgeThresholdPx,
                    maxVelocity = maxVelocityPxPerSecond,
                )
            } ?: 0f
            val verticalConsumed = if (verticalScrollTarget == null || verticalVelocity == 0f) {
                0f
            } else {
                verticalScrollTarget.state.scrollBy(verticalVelocity * elapsedSeconds)
            }

            targetRefreshState = targetRefreshState.afterScroll(
                horizontalConsumed = horizontalConsumed,
                verticalConsumed = verticalConsumed,
            )
        }
    }
}

internal fun resolveBoardDragHorizontalEdgeScrollVelocity(
    pointer: Float,
    dragOrigin: Float,
    viewportStart: Float,
    viewportEnd: Float,
    edgeThreshold: Float,
    intentThreshold: Float,
    maxVelocity: Float,
): Float {
    if (!dragOrigin.isFinite() || !intentThreshold.isFinite() || intentThreshold < 0f) {
        return 0f
    }
    val edgeVelocity = resolveBoardDragEdgeScrollVelocity(
        pointer = pointer,
        viewportStart = viewportStart,
        viewportEnd = viewportEnd,
        edgeThreshold = edgeThreshold,
        maxVelocity = maxVelocity,
    )
    val horizontalDisplacement = pointer - dragOrigin
    return when {
        edgeVelocity < 0f && horizontalDisplacement <= -intentThreshold -> edgeVelocity
        edgeVelocity > 0f && horizontalDisplacement >= intentThreshold -> edgeVelocity
        else -> 0f
    }
}

internal fun resolveBoardDragEdgeScrollVelocity(
    pointer: Float,
    viewportStart: Float,
    viewportEnd: Float,
    edgeThreshold: Float,
    maxVelocity: Float,
): Float {
    if (
        !pointer.isFinite() ||
        !viewportStart.isFinite() ||
        !viewportEnd.isFinite() ||
        !edgeThreshold.isFinite() ||
        !maxVelocity.isFinite() ||
        viewportEnd <= viewportStart ||
        edgeThreshold <= 0f ||
        maxVelocity <= 0f
    ) {
        return 0f
    }

    val distanceFromStart = pointer - viewportStart
    val distanceFromEnd = viewportEnd - pointer
    return when {
        distanceFromStart < edgeThreshold && distanceFromStart <= distanceFromEnd -> {
            val proximity = 1f - distanceFromStart.coerceIn(0f, edgeThreshold) / edgeThreshold
            -maxVelocity * proximity
        }
        distanceFromEnd < edgeThreshold -> {
            val proximity = 1f - distanceFromEnd.coerceIn(0f, edgeThreshold) / edgeThreshold
            maxVelocity * proximity
        }
        else -> 0f
    }
}

internal fun <Key> resolveBoardDragVerticalLane(
    position: Offset,
    boardViewport: Rect,
    laneViewports: Map<Key, Rect>,
    verticalActivationHalo: Float,
): Key? {
    if (
        !position.isFinite() ||
        !boardViewport.isFinite() ||
        !verticalActivationHalo.isFinite() ||
        verticalActivationHalo < 0f ||
        position.x !in boardViewport.left..boardViewport.right
    ) {
        return null
    }

    return laneViewports.entries.firstOrNull { (_, laneViewport) ->
        laneViewport.isFinite() &&
            laneViewport.right >= boardViewport.left &&
            laneViewport.left <= boardViewport.right &&
            laneViewport.bottom >= boardViewport.top &&
            laneViewport.top <= boardViewport.bottom &&
            position.x in laneViewport.left..laneViewport.right &&
            position.y in
            (maxOf(boardViewport.top, laneViewport.top) - verticalActivationHalo)..
            (minOf(boardViewport.bottom, laneViewport.bottom) + verticalActivationHalo)
    }?.key
}

internal fun <Key> resolveBoardDragLaneDropTarget(
    position: Offset,
    boardViewport: Rect,
    laneViewports: Map<Key, Rect>,
    allowedLaneKeys: Set<Key>,
): Key? {
    if (
        !position.isFinite() ||
        !boardViewport.isFinite() ||
        position.x !in boardViewport.left..boardViewport.right ||
        position.y !in boardViewport.top..boardViewport.bottom
    ) {
        return null
    }
    val visibleLanesAtPointerHeight = laneViewports.entries.filter { (_, laneViewport) ->
        laneViewport.isFinite() &&
            laneViewport.right >= boardViewport.left &&
            laneViewport.left <= boardViewport.right &&
            position.y in
            maxOf(boardViewport.top, laneViewport.top)..minOf(boardViewport.bottom, laneViewport.bottom)
    }
    val directlyHitLane = visibleLanesAtPointerHeight.firstOrNull { (_, laneViewport) ->
        position.x in laneViewport.left..laneViewport.right
    }
    if (directlyHitLane != null) {
        return directlyHitLane.key.takeIf(allowedLaneKeys::contains)
    }
    return visibleLanesAtPointerHeight
        .asSequence()
        .filter { (key) -> key in allowedLaneKeys }
        .minByOrNull { (_, laneViewport) -> laneViewport.horizontalDistanceTo(position.x) }
        ?.key
}

internal fun shouldRefreshBoardDragTarget(
    horizontalConsumed: Float,
    verticalConsumed: Float,
): Boolean =
    (horizontalConsumed.isFinite() && horizontalConsumed != 0f) ||
        (verticalConsumed.isFinite() && verticalConsumed != 0f)

private fun Offset.isFinite(): Boolean = x.isFinite() && y.isFinite()

private fun Rect.isFinite(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        right > left && bottom > top

private fun Rect.horizontalDistanceTo(x: Float): Float = when {
    x < left -> left - x
    x > right -> x - right
    else -> 0f
}
