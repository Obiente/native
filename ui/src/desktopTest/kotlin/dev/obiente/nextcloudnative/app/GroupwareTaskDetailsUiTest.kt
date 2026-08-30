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
import kotlinx.coroutines.yield
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class GroupwareTaskDetailsUiTest {
    private val task = GroupwareTask(
        "/remote.php/dav/calendars/person/tasks/123", "\"v1\"",
        "/remote.php/dav/calendars/person/tasks/", "task-1",
        title = "A long task title that must remain readable. ".repeat(12),
        due = "20260831", description = "Full task description with important details.\n".repeat(80),
        rawCalendar = "",
    )

    @Test
    fun longDetailsScrollWhileActionsStayVisibleAtPhoneLandscapeAndDesktopSizes() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                listOf(Triple(390, 844, 1f), Triple(844, 390, 1.5f), Triple(1280, 800, 1f)).forEach { (width, height, fontScale) ->
                    var edits = 0
                    var deletes = 0
                    val scene = ImageComposeScene(width, height, Density(1f, fontScale), coroutineContext = coroutineContext) {
                        MaterialTheme { Surface {
                            TaskDetailsDialog(task, true, true, false, "End of task details",
                                onDismiss = {}, onEdit = { edits++ }, onDelete = { deletes++ })
                        } }
                    }
                    var time = 1_000_000_000L
                    suspend fun settle() {
                        repeat(12) { time += 100_000_000L; scene.render(time).close(); yield() }
                    }
                    try {
                        settle()
                        assertTrue(scene.nodes().any { it.hasText(task.title) })
                        assertTrue(scene.nodes().any { it.hasText(task.description!!) })
                        fun assertActionsVisible() {
                            listOf("Edit", "Delete").forEach { label ->
                                val bounds = scene.button(label).boundsInWindow
                                assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.top >= 0 &&
                                    bounds.bottom <= height && bounds.left >= 0 && bounds.right <= width, "$width/$height/$label: $bounds")
                            }
                        }
                        assertActionsVisible()
                        val scroll = scene.nodes().single { SemanticsProperties.VerticalScrollAxisRange in it.config }
                        assertTrue(scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0)
                        assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 100_000f))
                        settle()
                        val range = scene.nodes().single { SemanticsProperties.VerticalScrollAxisRange in it.config }
                            .config[SemanticsProperties.VerticalScrollAxisRange]
                        assertTrue(range.value() > 0)
                        assertEquals(range.maxValue(), range.value())
                        val tail = scene.nodes().single { it.hasText("End of task details") }.boundsInWindow
                        assertTrue(tail.height > 0 && tail.top >= 0 && tail.bottom <= height)
                        assertActionsVisible()
                        assertTrue(scene.button("Edit").config[SemanticsActions.OnClick].action!!.invoke())
                        assertTrue(scene.button("Delete").config[SemanticsActions.OnClick].action!!.invoke())
                        assertEquals(1, edits)
                        assertEquals(1, deletes)
                        scene.render(time + 100_000_000L).use { rendered ->
                            val output = Path.of("build/reports/task-details-$width.png")
                            Files.createDirectories(output.parent)
                            rendered.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
                        }
                    } finally { scene.close() }
                }
            }
        }
    }

    @Test
    fun readOnlyBusyAndRecurringTaskGuardsRemainOnDialogActions() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                listOf(Triple(false, true, false), Triple(true, true, true), Triple(true, false, false))
                    .forEach { (writable, deleteSafe, busy) ->
                        val scene = ImageComposeScene(390, 844, Density(1f), coroutineContext = coroutineContext) {
                            MaterialTheme { Surface {
                                TaskDetailsDialog(task, writable, deleteSafe, busy, null, {}, {}, {})
                            } }
                        }
                        try {
                            repeat(3) { scene.render().close(); yield() }
                            assertEquals(!writable || busy, SemanticsProperties.Disabled in scene.button("Edit").config)
                            assertEquals(!writable || busy || !deleteSafe, SemanticsProperties.Disabled in scene.button("Delete").config)
                        } finally { scene.close() }
                    }
            }
        }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }
    private fun SemanticsNode.hasText(text: String) = SemanticsProperties.Text in config &&
        config[SemanticsProperties.Text].any { it.text == text }
    private fun ImageComposeScene.button(text: String) = nodes().first { node ->
        SemanticsActions.OnClick in node.config && node.descendants().any { it.hasText(text) }
    }
}
