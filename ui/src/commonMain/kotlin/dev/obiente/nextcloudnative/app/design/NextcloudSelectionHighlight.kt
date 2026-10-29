package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One selection highlight shared by every entry of a sidebar, such as the app shell or an app's
 * own destination list. Entries report where they are laid out; the container draws a single pill
 * behind them that springs from where it was to the selected entry. Geometry is read at draw
 * time, so the pill follows scrolling and resizing. This holds presentation state only; the host
 * still owns which destination is selected, and entries draw no selected background themselves.
 */
@Stable
internal class NextcloudSelectionHighlightState<K : Any>(initialKey: K) {
    private val entries = mutableMapOf<K, LayoutCoordinates>()
    private var container: LayoutCoordinates? = null
    private var layoutRevision by mutableIntStateOf(0)
    private var targetKey by mutableStateOf(initialKey)
    private var fromRect by mutableStateOf<Rect?>(null)
    private val progress = Animatable(1f)

    /** Attach to the layout that also carries [drawModifier]; pill geometry is relative to it. */
    fun containerModifier(): Modifier = Modifier.onGloballyPositioned {
        container = it
        layoutRevision++
    }

    fun entryModifier(key: K): Modifier = Modifier.onGloballyPositioned {
        entries[key] = it
        // Only the selected entry's geometry is drawn; other entries need no redraw.
        if (key == targetKey) layoutRevision++
    }

    /** Moves the pill to [key]; reduced motion and a first appearance place it without travel. */
    suspend fun moveTo(key: K, reducedMotion: Boolean) {
        if (key == targetKey) {
            if (reducedMotion) settle()
            return
        }
        val start = visibleRect()
        targetKey = key
        if (reducedMotion || start == null || rectOf(key) == null) {
            settle()
            return
        }
        fromRect = start
        progress.snapTo(0f)
        progress.animateTo(1f, NextcloudMotion.selection(reduced = false))
        fromRect = null
    }

    private suspend fun settle() {
        fromRect = null
        progress.snapTo(1f)
    }

    private fun rectOf(key: K): Rect? {
        val parent = container?.takeIf { it.isAttached } ?: return null
        val entry = entries[key]?.takeIf { it.isAttached } ?: return null
        // Clipped bounds keep the pill inside the scroll viewport when an entry is partly hidden.
        return parent.localBoundingBoxOf(entry, clipBounds = true).takeUnless { it.isEmpty }
    }

    private fun visibleRect(): Rect? {
        val target = rectOf(targetKey) ?: return null
        val start = fromRect ?: return target
        return lerp(start, target, progress.value)
    }

    /** Lazy lists need no [scrollState]: the selected entry's relayout redraws the pill as it scrolls. */
    fun drawModifier(fill: Color, outline: Color, cornerRadius: Dp, alpha: Float = 1f, scrollState: ScrollState? = null): Modifier =
        Modifier.drawBehind {
            // Read so scrolling and entry relayout redraw the pill in place.
            layoutRevision
            scrollState?.value
            val rect = visibleRect() ?: return@drawBehind
            val radius = cornerRadius.toPx()
            drawRoundRect(fill, rect.topLeft, rect.size, CornerRadius(radius), alpha = alpha)
            // A hairline inside the fill keeps the edge crisp on light and dark backgrounds.
            val stroke = 1.dp.toPx()
            val inset = stroke / 2f
            drawRoundRect(
                color = outline,
                topLeft = Offset(rect.left + inset, rect.top + inset),
                size = Size((rect.width - stroke).coerceAtLeast(0f), (rect.height - stroke).coerceAtLeast(0f)),
                cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
                alpha = alpha,
                style = Stroke(width = stroke),
            )
        }
}

@Composable
internal fun <K : Any> rememberNextcloudSelectionHighlight(selectedKey: K): NextcloudSelectionHighlightState<K> {
    val reducedMotion = LocalNextcloudReducedMotion.current
    val state = remember { NextcloudSelectionHighlightState(selectedKey) }
    LaunchedEffect(state, selectedKey, reducedMotion) {
        state.moveTo(selectedKey, reducedMotion)
    }
    return state
}
