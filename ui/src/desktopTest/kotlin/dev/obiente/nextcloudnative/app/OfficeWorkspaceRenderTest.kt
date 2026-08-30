package dev.obiente.nextcloudnative.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class OfficeWorkspaceRenderTest {
    @Test
    fun rendersProductionBrowserAtPhoneAndDesktopSizesWithoutNetwork() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                val state = OfficeWorkspaceState(
                    files = listOf(
                        file("Projects", directory = true),
                        file("Proposal.docx"),
                        file("Budget.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                        file("Slides.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                        file("Manual.pdf", "application/pdf"),
                    ),
                    capabilities = NextcloudDocumentEditingCapabilities(
                        mapOf("suite" to NextcloudDocumentEditorCapability(
                            "suite", "Office suite", setOf("application/pdf"), emptySet(), true,
                        )),
                        emptyMap(), true,
                    ),
                    loading = false,
                    networkConfirmed = true,
                )
                listOf(390 to 844, 1280 to 800).forEach { (width, height) ->
                    val scene = ImageComposeScene(
                        width = width, height = height, density = Density(1f), coroutineContext = coroutineContext,
                    ) {
                        MaterialTheme {
                            Surface {
                                OfficeWorkspaceBrowser(state, {}, {}, {})
                            }
                        }
                    }
                    try {
                        repeat(3) { scene.render().close() }
                        scene.render().use { rendered ->
                            assertEquals(width, rendered.width)
                            assertEquals(height, rendered.height)
                            val output = Path.of("build/reports/office-workspace-$width.png")
                            Files.createDirectories(output.parent)
                            rendered.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                                Files.write(output, data.bytes)
                            }
                        }
                    } finally {
                        scene.close()
                    }
                }
            }
        }
    }

    private fun file(
        name: String,
        mime: String = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        directory: Boolean = false,
    ) = NextcloudFile(name, name, directory, mime, 100, null, fileId = 1, hasPreview = false)
}
