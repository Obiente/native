package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * Fades and lifts the shell workspace in when [destinationKey] changes. The workspace stays one
 * composition: unlike a crossfade, the outgoing screen is never composed a second time, so its
 * saveable state, movable content, and effects keep a single owner. The first composition and
 * reduced motion show the content at rest immediately.
 */
@Composable
internal fun NextcloudDestinationEntrance(
    destinationKey: NextcloudShellDestinationKey,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    NextcloudContentEntrance(destinationKey, NextcloudEntranceAxis.Vertical, modifier, content = content)
}

/**
 * Which way new content travels into place: up for peer screens, in from the side for panes and
 * deeper pages, or a plain fade when the content only finished loading.
 */
internal enum class NextcloudEntranceAxis { Vertical, Horizontal, Fade }

/**
 * Plays the entrance whenever [key] changes, keeping [content] as one composition. Detail panes
 * use the horizontal axis so a newly selected item slides in from the side of the pane.
 *
 * With [directional], the entrance follows navigation: content that opens deeper arrives from
 * the end, and returning to the key shown before the current one arrives from the start, the
 * way a back step should. Give [order] for content chosen from an ordered set, such as tabs or
 * a section list: picking a later item arrives from the end and an earlier one from the start.
 * [distance] is how far the content travels.
 */
@Composable
internal fun NextcloudContentEntrance(
    key: Any?,
    axis: NextcloudEntranceAxis,
    modifier: Modifier = Modifier,
    directional: Boolean = false,
    distance: Dp = NextcloudMotion.DestinationEntranceOffset,
    order: Int? = null,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalNextcloudReducedMotion.current
    val sharedElements = LocalNextcloudSharedElements.current
    val progress = remember { Animatable(1f) }
    var settledKey by remember { mutableStateOf(key) }
    val history = remember { NextcloudEntranceHistory(key) }
    var backward by remember { mutableStateOf(false) }
    var settledOrder by remember { mutableStateOf(order) }
    // Hide the incoming content on the frame the key changes, before the effect starts.
    val awaitingEntrance = settledKey != key && !reducedMotion
    // Marked when the change is applied, before the new content is laid out, so elements shared
    // with the previous content can fly to their new place.
    SideEffect { if (settledKey != key) sharedElements?.markNavigation() }
    LaunchedEffect(key, reducedMotion) {
        val entering = settledKey != key && !reducedMotion
        val historyBack = history.visit(key)
        val previousOrder = settledOrder
        val back = if (order != null && previousOrder != null) order < previousOrder else historyBack
        progress.snapTo(if (entering) 0f else 1f)
        backward = (directional || order != null) && back
        settledKey = key
        settledOrder = order
        if (entering) progress.animateTo(1f, NextcloudMotion.entrance(reduced = false))
    }
    Box(
        modifier.graphicsLayer {
            val fraction = if (awaitingEntrance) 0f else progress.value
            val frame = nextcloudDestinationEntranceFrame(fraction)
            alpha = frame.alpha
            val offset = frame.offsetFraction * distance.toPx() * if (backward) -1f else 1f
            when (axis) {
                NextcloudEntranceAxis.Vertical -> translationY = offset
                NextcloudEntranceAxis.Horizontal -> translationX = offset
                NextcloudEntranceAxis.Fade -> Unit
            }
        },
    ) { content() }
}

/**
 * Recent keys of one entrance, newest last, so it can tell a step back from a step forward:
 * returning to the key shown before the current one is a back step. Bounded to a short trail.
 */
internal class NextcloudEntranceHistory(initial: Any?) {
    private val keys = ArrayDeque<Any?>().apply { addLast(initial) }

    /** Records [key] as shown and reports whether reaching it was a step back. */
    fun visit(key: Any?): Boolean {
        if (keys.lastOrNull() == key) return false
        val back = keys.size >= 2 && keys[keys.size - 2] == key
        if (back) {
            keys.removeLast()
        } else {
            keys.addLast(key)
            while (keys.size > MaxEntranceHistory) keys.removeFirst()
        }
        return back
    }
}

private const val MaxEntranceHistory = 24

internal data class NextcloudDestinationEntranceFrame(val alpha: Float, val offsetFraction: Float)

/**
 * Entrance [progress] is linear time from 0 (just switched) to 1 (at rest). Opacity eases in over
 * the first part so the screen is readable early, while the lift decelerates over the whole time.
 */
internal fun nextcloudDestinationEntranceFrame(progress: Float): NextcloudDestinationEntranceFrame {
    val time = progress.coerceIn(0f, 1f)
    val fade = (time / NextcloudMotion.EntranceFadeFraction).coerceAtMost(1f)
    return NextcloudDestinationEntranceFrame(
        alpha = FastOutSlowInEasing.transform(fade),
        offsetFraction = 1f - NextcloudMotion.ContentEasing.transform(time),
    )
}
