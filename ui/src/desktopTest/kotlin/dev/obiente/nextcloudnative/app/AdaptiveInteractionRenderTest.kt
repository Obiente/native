package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveInteractionRenderTest {
    @Test
    fun productionWorkspaceRendersAtCompactTabletAndDesktopSizes() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                for ((width, height) in listOf(390 to 844, 840 to 900, 1280 to 800)) {
                    for (dark in listOf(false, true)) {
                        val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
                            NextcloudNativeTheme(darkTheme = dark) {
                                Surface(color = MaterialTheme.colorScheme.background) {
                                    MarketingBudgetDashboardScenario(
                                        if (width >= 900) MarketingCaptureScenario.BudgetDashboardDesktopLight
                                        else MarketingCaptureScenario.BudgetDashboardMobileLight,
                                    )
                                }
                            }
                        }
                        try {
                            warmUp(scene)
                            capture(scene, "workspace-$width-${if (dark) "dark" else "light"}")
                            if (width == 390) {
                                val driver = NativeSceneTestDriver(scene)
                                driver.click("Dashboard. Open sections for Budget")
                                assertTrue(driver.has("Accounts"), "The section chooser must open through its real control")
                                capture(scene, "budget-sections-open-$width-${if (dark) "dark" else "light"}")
                            }
                        } finally { scene.close() }
                    }
                }
            }
        }
    }

    @Test
    fun pointerScrollChangesTheProductionCanvasAndButtonsAndKeysSelectFitAndActualSize() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                val bytes = requireNotNull(javaClass.getResourceAsStream("/marketing/raw-render-fixture.png")).use { it.readBytes() }
                val image = Image.makeFromEncoded(bytes).toComposeImageBitmap()
                val state = mutableStateOf(MediaViewportTransform())
                val scene = ImageComposeScene(1280, 800, Density(1f), coroutineContext = coroutineContext) {
                    NextcloudNativeTheme(darkTheme = true) {
                        MediaImageCanvas(image, "Synthetic photo fixture", state.value, { state.value = it }, Modifier.fillMaxSize())
                    }
                }
                try {
                    warmUp(scene)
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 350f), scrollDelta = Offset(0f, -4f))
                    warmUp(scene)
                    assertTrue(state.value.zoom > 1f, "Wheel input must reach the shared image canvas")
                    val beforeDrag = state.value.offset
                    scene.sendPointerEvent(PointerEventType.Press, Offset(500f, 350f))
                    scene.sendPointerEvent(PointerEventType.Move, Offset(600f, 410f))
                    scene.sendPointerEvent(PointerEventType.Move, Offset(660f, 440f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(660f, 440f))
                    warmUp(scene)
                    assertTrue(state.value.offset != beforeDrag, "Mouse dragging must pan the zoomed canvas")
                    capture(scene, "media-pointer-zoom")
                    val driver = NativeSceneTestDriver(scene)
                    driver.click("Fit image to window")
                    assertEquals(MediaViewportTransform(), state.value, "The visible Fit button must reset scale and pan")
                    capture(scene, "media-fit-button")
                    driver.click("Actual size, one image pixel per screen pixel")
                    val actualZoom = mediaActualSizeZoom(androidx.compose.ui.geometry.Size(1280f, 800f), androidx.compose.ui.geometry.Size(image.width.toFloat(), image.height.toFloat()))
                    assertEquals(actualZoom, state.value.zoom, "The visible 1:1 button must use image pixel size")
                    assertEquals(Offset.Zero, state.value.offset)
                    capture(scene, "media-actual-size-button")
                    scene.sendPointerEvent(PointerEventType.Press, Offset(500f, 350f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(500f, 350f))
                    key(scene, Key.Zero)
                    warmUp(scene)
                    assertEquals(1f, state.value.zoom, "Fit shortcut must reset zoom")
                    assertEquals(Offset.Zero, state.value.offset)
                    key(scene, Key.Equals)
                    warmUp(scene)
                    assertEquals(1.25f, state.value.zoom, "Keyboard zoom must reach the focused canvas")
                    key(scene, Key.DirectionRight)
                    warmUp(scene)
                    assertTrue(state.value.offset.x < 0f, "Arrow keys pan the zoomed image")
                    key(scene, Key.Zero)
                    warmUp(scene)
                    assertEquals(MediaViewportTransform(), state.value)
                    key(scene, Key.One)
                    warmUp(scene)
                    assertEquals(actualZoom, state.value.zoom)
                    capture(scene, "media-actual-size-keyboard")
                } finally { scene.close() }
            }
        }
    }

    private suspend fun warmUp(scene: ImageComposeScene) {
        repeat(5) { scene.render(System.nanoTime()).close(); delay(16) }
    }

    // Synthetic event construction is version-bound; production consumes public input events.
    @OptIn(InternalComposeUiApi::class)
    private fun key(scene: ImageComposeScene, key: Key) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
    }

    private fun capture(scene: ImageComposeScene, name: String) {
        scene.render(System.nanoTime()).use { image ->
            val output = Path.of("build/reports/adaptive-interactions/$name.png")
            Files.createDirectories(output.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
        }
    }
}
