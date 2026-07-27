package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal class BoardDragPinOwner {
    private val pinnedHandles = mutableListOf<PinnableContainer.PinnedHandle>()

    fun acquire(vararg containers: PinnableContainer?) {
        release()
        val acquiredContainers = mutableListOf<PinnableContainer>()
        try {
            containers.filterNotNull().forEach { container ->
                if (acquiredContainers.none { acquired -> acquired === container }) {
                    acquiredContainers += container
                    pinnedHandles += container.pin()
                }
            }
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    fun release() {
        val handles = pinnedHandles.toList()
        pinnedHandles.clear()
        handles.asReversed().forEach { handle -> handle.release() }
    }
}

/**
 * Shared board-card drag grip for typed and dynamically discovered board surfaces.
 *
 * Drag start coordinates use the window coordinate space so board implementations can compare
 * the pointer with measured lane bounds without app-specific position assumptions.
 */
@Composable
fun NextcloudBoardDragHandle(
    itemLabel: String,
    dragActive: Boolean,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    additionalPinnableContainer: PinnableContainer? = null,
    modifier: Modifier = Modifier,
) {
    var bounds by remember(itemLabel) { mutableStateOf<Rect?>(null) }
    val pinOwner = remember(itemLabel) { BoardDragPinOwner() }
    val pinnableContainer = LocalPinnableContainer.current
    val currentPinnableContainer by rememberUpdatedState(pinnableContainer)
    val currentAdditionalPinnableContainer by rememberUpdatedState(additionalPinnableContainer)
    val currentDragActive by rememberUpdatedState(dragActive)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    DisposableEffect(pinOwner) {
        onDispose(pinOwner::release)
    }
    LaunchedEffect(dragActive, pinOwner) {
        if (!dragActive) {
            pinOwner.release()
        }
    }
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
                        pinOwner.acquire(
                            currentPinnableContainer,
                            currentAdditionalPinnableContainer,
                        )
                        try {
                            currentOnDragStart(
                                Offset(
                                    x = handleBounds.left + localPosition.x,
                                    y = handleBounds.top + localPosition.y,
                                ),
                            )
                        } catch (error: Throwable) {
                            pinOwner.release()
                            throw error
                        }
                    },
                    onDragEnd = {
                        try {
                            currentOnDragEnd()
                        } catch (error: Throwable) {
                            pinOwner.release()
                            throw error
                        }
                        if (!currentDragActive) {
                            pinOwner.release()
                        }
                    },
                    onDragCancel = {
                        try {
                            currentOnDragCancel()
                        } finally {
                            pinOwner.release()
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        try {
                            currentOnDrag(amount)
                        } catch (error: Throwable) {
                            pinOwner.release()
                            throw error
                        }
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
