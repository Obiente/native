package dev.obiente.nextcloudnative.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class OfficeWorkspaceDiscoveryUiTest {
    @Test
    fun nativePreviewAndFolderNavigationRemainUsableWhileEditorDiscoveryStalls() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                listOf(390 to 844, 1280 to 800).forEach { (width, height) ->
                    val listingReady = CompletableDeferred<Unit>()
                    val editorsReady = CompletableDeferred<Unit>()
                    var editorStarts = 0
                    var editorStops = 0
                    val operations = OfficeWorkspaceOperations(
                        cachedFiles = { NextcloudFileListing(listOf(document("Manual.pdf", "cached")), NextcloudFileListingSource.Cache) },
                        files = { path ->
                            listingReady.await()
                            val files = if (path.isEmpty()) listOf(document("Manual.pdf", "fresh"),
                                NextcloudFile("Projects", "Projects", true, null, 0, null, fileId = 2, hasPreview = false))
                            else listOf(document("Projects/Plan.pdf", "fresh"))
                            NextcloudFileListing(files, NextcloudFileListingSource.Network)
                        },
                        capabilities = {
                            editorStarts++
                            try { editorsReady.await(); NextcloudDocumentEditingCapabilities.Unavailable }
                            finally { editorStops++ }
                        },
                    )
                    val scope = previewCacheDigest(NextcloudSession("https://cloud.example.test", "person", "secret"))
                    val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
                        MaterialTheme { Surface {
                            OfficeWorkspaceContent(operations, scope, true, {}) { file, modifier ->
                                Text("Preview: ${file.name} / ${file.etag}", modifier)
                            }
                        } }
                    }
                    var time = 1_000_000_000L
                    suspend fun settle() {
                        repeat(12) { time += 100_000_000L; scene.render(time).close(); yield() }
                    }
                    try {
                        settle()
                        assertTrue(SemanticsProperties.Disabled in scene.clickTarget("Manual.pdf").config)
                        listingReady.complete(Unit)
                        settle()
                        assertTrue(editorStarts > 0)
                        assertFalse(editorsReady.isCompleted)
                        assertFalse(SemanticsProperties.Disabled in scene.clickTarget("Manual.pdf").config)
                        assertFalse(SemanticsProperties.Disabled in scene.clickTarget("Refresh").config)
                        scene.render(time + 100_000_000L).use { rendered ->
                            val output = Path.of("build/reports/office-pending-discovery-$width.png")
                            Files.createDirectories(output.parent)
                            rendered.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
                        }
                        scene.click("Manual.pdf"); settle()
                        assertTrue(scene.nodes().any { it.hasText("Preview: Manual.pdf / fresh") })
                        scene.click("Back"); settle()
                        scene.click("Projects"); settle()
                        assertTrue(scene.nodes().any { it.hasText("Plan.pdf") })
                        assertTrue(editorStops >= 2)
                        assertFalse(editorsReady.isCompleted)
                        scene.click("Plan.pdf"); settle()
                        assertTrue(scene.nodes().any { it.hasText("Preview: Plan.pdf / fresh") })
                    } finally { scene.close() }
                    repeat(3) { yield() }
                    assertEquals(editorStarts, editorStops)
                }
            }
        }
    }

    @Test
    fun pendingEditorOnlyTypesDoNotShowAPrematureEmptyFolderMessage() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                val editorsReady = CompletableDeferred<Unit>()
                val custom = document("Canvas.design", "fresh").copy(mimeType = "application/x-design")
                val operations = OfficeWorkspaceOperations(
                    cachedFiles = { null },
                    files = { NextcloudFileListing(listOf(custom), NextcloudFileListingSource.Network) },
                    capabilities = {
                        editorsReady.await()
                        NextcloudDocumentEditingCapabilities(
                            editors = mapOf("suite" to NextcloudDocumentEditorCapability("suite", "Suite",
                                setOf("application/x-design"), emptySet(), true)),
                            creators = emptyMap(), supportsFileId = true,
                        )
                    },
                )
                val scope = previewCacheDigest(NextcloudSession("https://cloud.example.test", "person", "secret"))
                val scene = ImageComposeScene(390, 844, Density(1f), coroutineContext = coroutineContext) {
                    MaterialTheme { Surface {
                        OfficeWorkspaceContent(operations, scope, true, {}) { _, _ -> }
                    } }
                }
                var time = 1_000_000_000L
                suspend fun settle() {
                    repeat(12) { time += 100_000_000L; scene.render(time).close(); yield() }
                }
                try {
                    settle()
                    assertTrue(scene.nodes().any { it.hasText("Checking the server's document editors...") })
                    assertFalse(scene.nodes().any { it.hasText("No Office documents in this folder. Open another folder or refresh.") })
                    editorsReady.complete(Unit)
                    settle()
                    assertFalse(SemanticsProperties.Disabled in scene.clickTarget("Canvas.design").config)
                    assertFalse(scene.nodes().any { it.hasText("Checking the server's document editors...") })
                } finally { scene.close() }
            }
        }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }
    private fun SemanticsNode.hasText(text: String) = SemanticsProperties.Text in config &&
        config[SemanticsProperties.Text].any { it.text == text }
    private fun ImageComposeScene.clickTarget(text: String) = nodes().first { node ->
        SemanticsActions.OnClick in node.config && node.descendants().any { it.hasText(text) }
    }
    private fun ImageComposeScene.click(text: String) {
        val target = clickTarget(text)
        assertFalse(SemanticsProperties.Disabled in target.config)
        assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun document(path: String, etag: String) = NextcloudFile(
        path, path.substringAfterLast('/'), false, "application/pdf", 12, null,
        fileId = 1, hasPreview = false, etag = etag, permissions = "R",
    )
}
