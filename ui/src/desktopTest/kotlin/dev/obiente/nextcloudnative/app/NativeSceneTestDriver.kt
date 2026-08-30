package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Synthetic native scene input through rendered control bounds and accessibility semantics. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal class NativeSceneTestDriver(val scene: ImageComposeScene) {
    suspend fun settle() {
        repeat(10) { scene.render(System.nanoTime()).close(); delay(16) }
    }

    fun nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) {
            add(node)
            node.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
    }

    fun node(label: String): SemanticsNode? = nodes().lastOrNull { candidate ->
        candidate.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            candidate.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }

    fun has(label: String): Boolean = node(label) != null

    suspend fun click(label: String) {
        val target = assertNotNull(node(label), "No rendered native control labeled '$label'")
        click(target.boundsInRoot.center)
    }

    suspend fun click(position: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, position)
        scene.sendPointerEvent(PointerEventType.Release, position)
        settle()
    }

    suspend fun replaceText(previous: String, value: String) {
        val target = assertNotNull(nodes().lastOrNull {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == previous &&
                it.config.getOrNull(SemanticsActions.SetText)?.action != null
        }, "No editable native field with the expected synthetic value")
        assertTrue(target.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
        settle()
    }

    fun capture(name: String) {
        scene.render(System.nanoTime()).use { image ->
            val output = Path.of("build/reports/native-workflow-interactions/$name.png")
            Files.createDirectories(output.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
        }
    }
}

internal fun nativeSceneTest(
    width: Int,
    height: Int,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
    assertions: suspend NativeSceneTestDriver.() -> Unit,
) {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
        runBlocking(dispatcher) {
            val scene = ImageComposeScene(width, height, Density(1f, fontScale), coroutineContext = coroutineContext) {
                NextcloudNativeTheme(darkTheme = false) { content() }
            }
            try {
                val driver = NativeSceneTestDriver(scene)
                driver.settle()
                assertions(driver)
            } finally { scene.close() }
        }
    }
}
