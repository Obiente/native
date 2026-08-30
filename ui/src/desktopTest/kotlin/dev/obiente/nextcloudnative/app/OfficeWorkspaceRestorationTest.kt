package dev.obiente.nextcloudnative.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class OfficeWorkspaceRestorationTest {
    @Test
    fun recreatedWorkspaceRestoresFolderAndResolvesSelectionFromFreshMetadata() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                val scope = previewCacheDigest(NextcloudSession("https://cloud.example.test", "person", "secret"))
                listOf(390 to 844, 1280 to 800).forEach { (width, height) ->
                    var freshFile = document("Proposal.pdf", "v1", "RW")
                    var holdRefresh: CompletableDeferred<Unit>? = null
                    val requestedPaths = mutableListOf<String>()
                    val operations = OfficeWorkspaceOperations(
                        cachedFiles = { path -> if (path == "Projects/Plan")
                            NextcloudFileListing(listOf(document("Proposal.pdf", "stale", "RW")), NextcloudFileListingSource.Cache)
                            else null },
                        files = { path ->
                            requestedPaths += path
                            if (path == "Projects/Plan") holdRefresh?.await()
                            val files = when (path) {
                                "" -> listOf(folder("Projects"))
                                "Projects" -> listOf(folder("Projects/Plan"))
                                "Projects/Plan" -> listOf(freshFile)
                                else -> error("Unexpected folder")
                            }
                            NextcloudFileListing(files, NextcloudFileListingSource.Network)
                        },
                        capabilities = { NextcloudDocumentEditingCapabilities.Unavailable },
                    )
                    fun createScene(registry: SaveableStateRegistry, accountScope: String = scope) = ImageComposeScene(
                        width, height, Density(1f), coroutineContext = coroutineContext,
                    ) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            MaterialTheme { Surface {
                                OfficeWorkspaceContent(operations, accountScope, true, {}) { file, modifier ->
                                    Text("Preview: ${file.name} / ${file.etag} / ${file.permissions}", modifier)
                                }
                            } }
                        }
                    }
                    var time = 1_000_000_000L
                    suspend fun settle(scene: ImageComposeScene) {
                        repeat(12) { time += 100_000_000L; scene.render(time).close(); yield() }
                    }
                    val initialRegistry = SaveableStateRegistry(null) { true }
                    val initial = createScene(initialRegistry)
                    val (folderSaved, saved) = try {
                        settle(initial)
                        initial.click("Projects"); settle(initial)
                        initial.click("Plan"); settle(initial)
                        val folderSaved = initialRegistry.performSave()
                        initial.click("Proposal.pdf"); settle(initial)
                        assertTrue(initial.hasText("Preview: Proposal.pdf / v1 / RW"))
                        folderSaved to initialRegistry.performSave()
                    } finally { initial.close() }

                    freshFile = document("Renamed.pdf", "v2", "R")
                    holdRefresh = CompletableDeferred()
                    requestedPaths.clear()
                    val restored = createScene(SaveableStateRegistry(saved) { true })
                    try {
                        settle(restored)
                        assertTrue(requestedPaths.isNotEmpty() && requestedPaths.all { it == "Projects/Plan" })
                        assertFalse(restored.hasPreview())
                        holdRefresh.complete(Unit)
                        settle(restored)
                        assertTrue(restored.hasText("Preview: Renamed.pdf / v2 / R"))
                    } finally { restored.close() }

                    requestedPaths.clear()
                    val folderRestored = createScene(SaveableStateRegistry(folderSaved) { true })
                    try {
                        settle(folderRestored)
                        assertTrue(requestedPaths.isNotEmpty() && requestedPaths.all { it == "Projects/Plan" })
                        assertTrue(folderRestored.hasText("Renamed.pdf"))
                        assertFalse(folderRestored.hasPreview())
                        folderRestored.render(time + 100_000_000L).use { rendered ->
                            val output = Path.of("build/reports/office-restored-folder-$width.png")
                            Files.createDirectories(output.parent)
                            rendered.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
                        }
                    } finally { folderRestored.close() }

                    freshFile = freshFile.copy(fileId = 99)
                    val missing = createScene(SaveableStateRegistry(saved) { true })
                    try {
                        settle(missing)
                        assertFalse(missing.hasPreview())
                        val message = "The selected document could not be restored. Refresh or choose another document."
                        assertTrue(missing.hasText(message))
                        missing.click("Back"); settle(missing)
                        assertFalse(missing.hasText(message))
                        assertTrue(missing.hasText("Projects/Plan"))
                    } finally { missing.close() }

                    requestedPaths.clear()
                    val otherScope = previewCacheDigest(NextcloudSession("https://cloud.example.test", "other", "secret"))
                    val switched = createScene(SaveableStateRegistry(saved) { true }, otherScope)
                    try {
                        settle(switched)
                        assertTrue(requestedPaths.isNotEmpty() && requestedPaths.all { it == "" })
                        assertTrue(switched.hasText("Projects"))
                        assertFalse(switched.hasPreview())
                    } finally { switched.close() }
                }
            }
        }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }
    private fun SemanticsNode.hasText(text: String) = SemanticsProperties.Text in config &&
        config[SemanticsProperties.Text].any { it.text == text }
    private fun ImageComposeScene.hasText(text: String) = nodes().any { it.hasText(text) }
    private fun ImageComposeScene.hasPreview() = nodes().any { node -> SemanticsProperties.Text in node.config &&
        node.config[SemanticsProperties.Text].any { it.text.startsWith("Preview:") } }
    private fun ImageComposeScene.click(text: String) {
        val target = nodes().first { node -> SemanticsActions.OnClick in node.config &&
            node.descendants().any { it.hasText(text) } }
        assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun folder(path: String) = NextcloudFile(path, path.substringAfterLast('/'), true, null, 0, null, fileId = 1, hasPreview = false)
    private fun document(name: String, etag: String, permissions: String) =
        NextcloudFile("Projects/Plan/$name", name, false, "application/pdf", 12, null,
            fileId = 42, hasPreview = false, etag = etag, permissions = permissions)
}
