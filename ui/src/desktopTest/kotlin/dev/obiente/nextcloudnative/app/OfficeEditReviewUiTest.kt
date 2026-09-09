package dev.obiente.nextcloudnative.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class OfficeEditReviewUiTest {
    @Test
    fun changedSourceKeepsItsErrorVisibleAndWithholdsEditEvenForCustomOfficeTypes() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                val file = NextcloudFile("drawing.design", "drawing.design", false, "application/x-design", 10, null,
                    fileId = 42, hasPreview = false, etag = "v1", permissions = "RW")
                val request = NextcloudDocumentEditSessionRequest("/", 42, "editor", "v1")
                val message = OfficeEditSourceChangedException().message!!
                listOf(390 to 844, 1280 to 800).forEach { (width, height) ->
                    val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
                        MaterialTheme { Surface {
                            DocumentWorkflowBar(file, OfficeEditSessionPlan.Ready(request), emptyList(),
                                DocumentEditUiState.Failed(message), { error("Edit must be withheld") },
                                false, DocumentExternalOpenUiState.Idle, {})
                        } }
                    }
                    try {
                        repeat(6) { scene.render(1_000_000_000L + it * 100_000_000L).close() }
                        fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
                        val nodes = scene.semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }
                        assertTrue(nodes.any { node -> SemanticsProperties.Text in node.config &&
                            node.config[SemanticsProperties.Text].any { it.text == message } })
                        assertFalse(nodes.any { SemanticsActions.OnClick in it.config })
                        scene.render(2_000_000_000L).use { rendered ->
                            val output = Path.of("build/reports/office-source-changed-$width.png")
                            Files.createDirectories(output.parent)
                            rendered.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
                        }
                    } finally { scene.close() }
                }
            }
        }
    }
}
