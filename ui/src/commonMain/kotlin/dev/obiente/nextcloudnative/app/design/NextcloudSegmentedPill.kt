package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * One selection pill shared by a segmented control's options. Options report where they are
 * placed; the row draws the pill behind them and springs it to the selected option, as the
 * sidebar highlight does. It is presentation state only: the caller still owns the selection.
 */
@Stable
internal class SegmentedSelectionPill {
    private val bounds = mutableStateMapOf<String, SegmentBounds>()
    private val left = Animatable(0f)
    private val width = Animatable(0f)
    private var settled by mutableStateOf(false)
    internal var targetId by mutableStateOf<String?>(null)

    fun place(id: String, x: Float, width: Float) {
        val placed = SegmentBounds(x, width)
        if (bounds[id] != placed) bounds[id] = placed
    }

    fun forget(id: String) {
        bounds.remove(id)
    }

    /** Draws in the options row's coordinates, so scrolling the row carries the pill along. */
    fun draw(scope: DrawScope, color: Color, cornerRadius: Float) {
        val target = targetId?.let { bounds[it] } ?: return
        val x = if (settled) left.value else target.left
        val w = if (settled) width.value else target.width
        scope.drawRoundRect(
            color = color,
            topLeft = Offset(x, 0f),
            size = Size(w, scope.size.height),
            cornerRadius = CornerRadius(cornerRadius),
        )
    }

    /** The first placement and reduced motion snap; later changes spring to the new option. */
    suspend fun follow(reducedMotion: Boolean) {
        snapshotFlow { targetId?.let { bounds[it] } }.filterNotNull().collectLatest { target ->
            if (!settled || reducedMotion) {
                left.snapTo(target.left)
                width.snapTo(target.width)
                settled = true
            } else {
                coroutineScope {
                    launch { left.animateTo(target.left, NextcloudMotion.selection(reduced = false)) }
                    width.animateTo(target.width, NextcloudMotion.selection(reduced = false))
                }
            }
        }
    }
}

internal data class SegmentBounds(val left: Float, val width: Float)

@Composable
internal fun rememberSegmentedSelectionPill(selectedId: String?): SegmentedSelectionPill {
    val pill = remember { SegmentedSelectionPill() }
    val reducedMotion = LocalNextcloudReducedMotion.current
    SideEffect { pill.targetId = selectedId }
    LaunchedEffect(pill, reducedMotion) { pill.follow(reducedMotion) }
    return pill
}
