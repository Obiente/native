package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Shared board-card drag grip for typed and dynamically discovered board surfaces.
 *
 * Drag start coordinates use the window coordinate space so board implementations can compare
 * the pointer with measured lane bounds without app-specific position assumptions.
 */
@Composable
fun NextcloudBoardDragHandle(
    itemLabel: String,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bounds by remember(itemLabel) { mutableStateOf<Rect?>(null) }
    Box(
        modifier = modifier
            .size(40.dp)
            .onGloballyPositioned { coordinates ->
                bounds = coordinates.boundsInWindow()
            }
            .pointerInput(itemLabel) {
                detectDragGestures(
                    onDragStart = { localPosition ->
                        val handleBounds = bounds ?: return@detectDragGestures
                        onDragStart(
                            Offset(
                                x = handleBounds.left + localPosition.x,
                                y = handleBounds.top + localPosition.y,
                            ),
                        )
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                )
            }
            .semantics {
                contentDescription = "Drag $itemLabel"
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            NextcloudIcons.Drag,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
