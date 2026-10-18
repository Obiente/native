package dev.obiente.nextcloudnative.app

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.max

internal enum class MediaSwipeNavigation { Previous, Next }

/**
 * Decides whether a completed one-finger drag over the fitted media should move to a neighbor.
 *
 * Zoomed media keeps drags for panning, and pinches never navigate. The drag must be clearly
 * horizontal and travel a meaningful part of the viewport so a slightly diagonal tap or scroll
 * does not change the selection. Previous follows the reading direction like the chevrons.
 */
internal fun mediaSwipeNavigationOrNull(
    drag: Offset,
    viewportWidth: Float,
    minimumDistance: Float,
    zoomedIn: Boolean,
    multiTouch: Boolean,
    rightToLeft: Boolean,
): MediaSwipeNavigation? {
    if (zoomedIn || multiTouch) return null
    if (!drag.x.isFinite() || !drag.y.isFinite() || !viewportWidth.isFinite() || viewportWidth <= 0f) return null
    val threshold = max(minimumDistance, viewportWidth * MEDIA_SWIPE_VIEWPORT_FRACTION)
    if (abs(drag.x) < threshold || abs(drag.x) < abs(drag.y) * MEDIA_SWIPE_HORIZONTAL_DOMINANCE) return null
    val towardPrevious = (drag.x > 0f) != rightToLeft
    return if (towardPrevious) MediaSwipeNavigation.Previous else MediaSwipeNavigation.Next
}

/** Fitted media follows the finger at a damped rate so the swipe has visible feedback. */
internal fun mediaSwipeFeedbackOffset(dragX: Float, viewportWidth: Float): Float {
    if (!dragX.isFinite() || !viewportWidth.isFinite() || viewportWidth <= 0f) return 0f
    val limit = viewportWidth * MEDIA_SWIPE_FEEDBACK_LIMIT_FRACTION
    return (dragX * MEDIA_SWIPE_FEEDBACK_DAMPING).coerceIn(-limit, limit)
}

private const val MEDIA_SWIPE_VIEWPORT_FRACTION = 0.18f
private const val MEDIA_SWIPE_HORIZONTAL_DOMINANCE = 1.5f
private const val MEDIA_SWIPE_FEEDBACK_DAMPING = 0.6f
private const val MEDIA_SWIPE_FEEDBACK_LIMIT_FRACTION = 0.4f
