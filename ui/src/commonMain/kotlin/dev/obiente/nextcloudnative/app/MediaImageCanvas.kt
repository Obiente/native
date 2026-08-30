package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** No platform services or account access: this component only presents an image. */
@Composable
internal fun MediaImageCanvas(
    image: ImageBitmap,
    description: String,
    transform: MediaViewportTransform,
    onTransform: (MediaViewportTransform) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewport by remember { mutableStateOf(Size.Zero) }
    val imageSize = Size(image.width.toFloat(), image.height.toFloat())
    val bounded = transform.bounded(viewport, imageSize)
    val currentTransform by rememberUpdatedState(bounded)
    val currentOnTransform by rememberUpdatedState(onTransform)
    val focusRequester = remember { FocusRequester() }
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    val pixelPercent = mediaPixelScalePercent(bounded.zoom, viewport, imageSize)
    fun change(factor: Float = 1f, pan: Offset = Offset.Zero, anchor: Offset = center) {
        currentOnTransform(currentTransform.transform(factor, pan, anchor, viewport, imageSize))
    }
    LaunchedEffect(viewport, imageSize) {
        currentOnTransform(currentTransform.bounded(viewport, imageSize))
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier.clipToBounds()) {
        Box(
            Modifier.fillMaxSize()
                .testTag("media-image-canvas")
                .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
                .focusRequester(focusRequester)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.Plus, Key.Equals -> change(factor = 1.25f)
                        Key.Minus -> change(factor = 0.8f)
                        Key.Zero -> currentOnTransform(MediaViewportTransform())
                        Key.One -> currentOnTransform(MediaViewportTransform(mediaActualSizeZoom(viewport, imageSize)))
                        Key.DirectionLeft -> if (bounded.zoom > 1f) change(pan = Offset(64f, 0f)) else return@onKeyEvent false
                        Key.DirectionRight -> if (bounded.zoom > 1f) change(pan = Offset(-64f, 0f)) else return@onKeyEvent false
                        Key.DirectionUp -> if (bounded.zoom > 1f) change(pan = Offset(0f, 64f)) else return@onKeyEvent false
                        Key.DirectionDown -> if (bounded.zoom > 1f) change(pan = Offset(0f, -64f)) else return@onKeyEvent false
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .focusable()
                .semantics { stateDescription = "$pixelPercent percent of actual size. Plus and minus zoom, 0 fits, 1 shows actual size, arrow keys pan." }
                .pointerInput(viewport, imageSize) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val pointer = event.changes.firstOrNull() ?: continue
                                if (pointer.scrollDelta.y != 0f && !pointer.isConsumed) {
                                    focusRequester.requestFocus()
                                    change(factor = mediaWheelZoomFactor(pointer.scrollDelta.y), anchor = pointer.position)
                                    pointer.consume()
                                }
                            }
                        }
                    }
                }
                .pointerInput(viewport, imageSize) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        change(factor = zoom, pan = pan, anchor = centroid)
                    }
                }
                .pointerInput(viewport, imageSize) {
                    detectTapGestures(
                        onPress = { focusRequester.requestFocus() },
                        onDoubleTap = { anchor ->
                            focusRequester.requestFocus()
                            if (currentTransform.zoom > 1f) currentOnTransform(MediaViewportTransform())
                            else change(factor = 2.5f, anchor = anchor)
                        },
                    )
                },
        ) {
            Image(
                bitmap = image,
                contentDescription = description,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = bounded.zoom
                    scaleY = bounded.zoom
                    translationX = bounded.offset.x
                    translationY = bounded.offset.y
                },
                contentScale = ContentScale.Fit,
            )
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            color = Color(0xE625232B),
            contentColor = Color.White,
            shape = RoundedCornerShape(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { change(factor = 0.8f) },
                    enabled = bounded.zoom > mediaMinimumZoom(viewport, imageSize),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                ) { Icon(Icons.Outlined.ZoomOut, "Zoom out") }
                Text("$pixelPercent%")
                IconButton(
                    onClick = { change(factor = 1.25f) },
                    enabled = bounded.zoom < mediaMaximumZoom(viewport, imageSize),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                ) { Icon(Icons.Outlined.ZoomIn, "Zoom in") }
                TextButton(
                    onClick = { currentOnTransform(MediaViewportTransform()) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    modifier = Modifier.semantics { contentDescription = "Fit image to window" },
                ) { Text("Fit") }
                TextButton(
                    onClick = { currentOnTransform(MediaViewportTransform(mediaActualSizeZoom(viewport, imageSize))) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    modifier = Modifier.semantics { contentDescription = "Actual size, one image pixel per screen pixel" },
                ) { Text("1:1") }
            }
        }
    }
}
