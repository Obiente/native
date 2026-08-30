package dev.obiente.nextcloudnative.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class WorkspaceReviewUiTest {
    @Test
    fun duplicateDestinationsHaveDistinctAnnouncementsAndSelectTheirOwnResources() = onSceneThread {
        val resources = listOf(
            ResourceSpec("chores", "Chores", Confidence.verified),
            ResourceSpec("teams", "Teams", Confidence.verified),
        )
        val schema = NativeAppSchema("1", AppIdentity("example", "Example", "1"), Confidence.verified,
            resources = resources)
        val destinations = resources.map { resource ->
            DynamicNavigationDestination(resource.id, "API Trash", resource.id, "read-${resource.id}") to
                ViewSpec(resource.id, "Trash", resource.id, NativeComponent.collectionList, "read-${resource.id}", Confidence.verified)
        }
        val selected = mutableListOf<String>()
        listOf(390 to 844, 1280 to 800).forEach { (width, height) ->
            val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
                MaterialTheme { Surface {
                    DynamicCollectionHeaderActions(schema, "Example", null, emptyList(), destinations, true,
                        {}, { _, _ -> }, { destination, _ -> selected += destination.resourceId })
                } }
            }
            try {
                scene.settle()
                listOf("Chores" to "chores", "Teams" to "teams").forEach { (label, resourceId) ->
                    val item = scene.nodes().single { node ->
                        SemanticsProperties.ContentDescription in node.config &&
                            node.config[SemanticsProperties.ContentDescription] == listOf("Open $label Trash")
                    }
                    assertTrue(item.config[SemanticsActions.OnClick].action!!.invoke())
                    assertEquals(resourceId, selected.last())
                }
                scene.capture("workspace-menu-$width")
            } finally { scene.close() }
        }
    }

    @Test
    fun editorDiscoveryFailureKeepsNativePreviewRowsClickable() = onSceneThread {
        val file = NextcloudFile("Manual.pdf", "Manual.pdf", false, "application/pdf", 100, null,
            fileId = 1, hasPreview = true, etag = "v1")
        val workspace = OfficeWorkspace(OfficeWorkspaceOperations(
            cachedFiles = { null },
            files = { NextcloudFileListing(listOf(file), NextcloudFileListingSource.Network) },
            capabilities = { error("Editor discovery failed") },
        ))
        workspace.load("")
        listOf(390 to 844, 1280 to 800).forEach { (width, height) ->
            var opened: NextcloudFile? = null
            val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
                MaterialTheme { Surface {
                    OfficeWorkspaceBrowser(workspace.state.value, {}, { opened = it }, {})
                } }
            }
            try {
                scene.settle()
                val row = scene.nodes().single { node ->
                    SemanticsActions.OnClick in node.config && SemanticsProperties.Text in node.config &&
                        node.config[SemanticsProperties.Text].any { it.text == "Manual.pdf" }
                }
                assertFalse(SemanticsProperties.Disabled in row.config)
                assertTrue(row.config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals(file, opened)
                scene.capture("office-discovery-failure-$width")
            } finally { scene.close() }
        }
    }

    private fun onSceneThread(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher -> runBlocking(dispatcher, block) }
    }

    private fun ImageComposeScene.settle() { repeat(6) { render(1_000_000_000L + it * 100_000_000L).close() } }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
        return semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }
    }

    private fun ImageComposeScene.capture(name: String) {
        render(2_000_000_000L).use { rendered ->
            val output = Path.of("build/reports/$name.png")
            Files.createDirectories(output.parent)
            rendered.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
        }
    }
}
